package com.flowdeployer.deploy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowdeployer.config.DeployerProperties;
import com.flowdeployer.config.DeployerProperties.Environment;
import com.flowdeployer.deploy.FlowPreparer.Prepared;
import com.flowdeployer.flows.FlowCatalog;
import com.flowdeployer.flows.FlowCatalog.FlowFile;
import com.flowdeployer.nifi.NifiService;
import com.flowdeployer.nifi.NifiService.ServiceCounts;
import com.flowdeployer.nifi.Poller;
import com.flowdeployer.nifi.RegistryService;
import com.flowdeployer.nifi.RegistryService.RegistryFlow;
import com.flowdeployer.web.Dtos.DeploymentRequest;
import com.flowdeployer.web.Dtos.SummaryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

/**
 * The deploy script's main loop, step for step:
 *
 *   wait_for_nifi · authenticate · get_root_pg · get_next_position · cache_registry_flows
 *   for each selected flow:
 *     PREPARE              prepare_flow + inject_parameters (RPG url, overrides)
 *     HASH                 calculate_hash · lookup_registry_flow · get_registry_hash · find_pg → skip if unchanged
 *     REGISTRY_UPLOAD      create_registry_flow (if new) · get_latest_version · upload_registry_version · update_registry_hash
 *     IMPORT               import_flow (new PG) | update_flow_version (existing PG)
 *     CONTROLLER_SERVICES  enable_controller_services + poll (max 30)
 *     PARAMETERS           update_parameter_contexts
 *     START                control_pg_state
 *   summary
 */
@Component
public class DeploymentRunner {

    private static final Logger log = LoggerFactory.getLogger(DeploymentRunner.class);

    private final NifiService nifi;
    private final RegistryService registry;
    private final FlowCatalog catalog;
    private final FlowPreparer preparer;
    private final DeployerProperties.Deploy cfg;

    public DeploymentRunner(NifiService nifi, RegistryService registry, FlowCatalog catalog, FlowPreparer preparer,
                            DeployerProperties props) {
        this.nifi = nifi;
        this.registry = registry;
        this.catalog = catalog;
        this.preparer = preparer;
        this.cfg = props.deploy();
    }

    /** Thrown inside a flow to abort it; the stage is already marked failed. */
    private static final class FlowFailed extends RuntimeException {
        FlowFailed(String m, Throwable cause) {
            super(m, cause, false, false);
        }
    }

    public void run(Deployment d, Environment env) {
        DeploymentRequest req = d.request();
        int deployed = 0, skipped = 0, failed = 0;
        Deployment.Phase result = Deployment.Phase.Succeeded;

        try {
            d.section("Deploying " + req.flows().size() + " flow(s) to " + env.name() + " · started by " + d.startedBy());
            d.info("NiFi " + env.nifiUrl() + " · Registry " + env.registryUrl());

            d.section("Waiting for NiFi API");
            nifi.token(env);
            d.info("NiFi API is ready");

            String rootPg = nifi.rootProcessGroupId(env);
            JsonNode children = nifi.childGroups(env, rootPg);
            int step = req.positionStep() > 0 ? req.positionStep() : cfg.positionStep();
            double[] pos = nifi.nextPosition(children, step);
            Map<String, RegistryFlow> regFlows = registryFlowsByName(env, req.bucketId());
            d.info("Root PG " + rootPg + " · bucket " + req.bucketName() + " (" + req.bucketId() + ") · "
                    + regFlows.size() + " flow(s) in Registry");

            Map<String, FlowFile> files = catalog.asMap();

            for (String flowName : req.flows()) {
                if (d.cancelRequested()) {
                    d.warn("Cancelled before " + flowName);
                    result = Deployment.Phase.Cancelled;
                    break;
                }
                FlowFile file = files.get(flowName);
                if (file == null) {
                    d.error("Flow file for " + flowName + " no longer exists");
                    d.progress(flowName, "PREPARE", "failed", "file missing");
                    failed++;
                    if (cfg.stopOnFailure()) { result = Deployment.Phase.Failed; break; }
                    continue;
                }
                try {
                    Outcome o = deployFlow(d, env, req, file, rootPg, children, regFlows, pos);
                    if (o == Outcome.SKIPPED) {
                        skipped++;
                    } else {
                        deployed++;
                        if (o == Outcome.IMPORTED) {
                            pos[0] += step;
                        }
                    }
                } catch (FlowFailed e) {
                    failed++;
                    log.warn("Flow {} failed in deployment {}", flowName, d.id(), e.getCause());
                    if (cfg.stopOnFailure()) {
                        d.error("Stopping: stop-on-failure is enabled");
                        result = Deployment.Phase.Failed;
                        break;
                    }
                }
            }
            if (failed > 0) result = Deployment.Phase.Failed;
        } catch (Exception e) {
            log.error("Deployment {} failed", d.id(), e);
            d.error(describe(e));
            result = Deployment.Phase.Failed;
        }

        d.section("Deployment Summary");
        d.info("Total    : " + req.flows().size());
        d.info("Deployed : " + deployed);
        d.info("Skipped  : " + skipped);
        d.info("Failed   : " + failed);
        d.summary(new SummaryEvent(req.flows().size(), deployed, skipped, failed));
        d.finish(result);
    }

    private enum Outcome { SKIPPED, IMPORTED, UPDATED }

    private Outcome deployFlow(Deployment d, Environment env, DeploymentRequest req, FlowFile file, String rootPg,
                               JsonNode rootChildren, Map<String, RegistryFlow> regFlows, double[] pos) {
        String name = file.name();
        Stages s = new Stages(d, name);
        Poller poller = Poller.of(Duration.ofSeconds(cfg.pollIntervalSeconds()),
                Duration.ofSeconds(cfg.updateRequestTimeoutSeconds()), msg -> { }, () -> false);
        // NiFi update requests are never interrupted mid-way (that can leave a PG stopped); cancel acts between flows.

        d.section("Processing " + name);

        // PREPARE
        s.start("PREPARE", null);
        Map<String, String> overrides = req.parameterOverrides() == null ? Map.of()
                : req.parameterOverrides().getOrDefault(name, Map.of());
        Prepared prepared = s.run(() -> preparer.prepare(file, env.rpgUrl(), overrides));
        if (!overrides.isEmpty()) d.info("Parameter overrides: " + String.join(", ", overrides.keySet()));

        // HASH
        s.start("HASH", null);
        RegistryFlow regFlow = regFlows.get(name);
        String regHash = regFlow == null ? null : regFlow.hash();
        Optional<String> pgId = nifi.findProcessGroup(rootChildren, name);
        d.info("Local hash    : " + prepared.hash());
        d.info("Registry hash : " + (regHash == null ? "-" : regHash));
        d.info("Process group : " + pgId.orElse("not found"));
        if (prepared.hash().equals(regHash) && pgId.isPresent()) {
            d.info("No changes detected - skipping " + name);
            s.skip("no changes");
            return Outcome.SKIPPED;
        }

        // REGISTRY_UPLOAD
        s.start("REGISTRY_UPLOAD", null);
        String flowId = regFlow != null ? regFlow.id() : s.run(() -> {
            d.info("Creating new registry flow " + name);
            return registry.createFlow(env, req.bucketId(), name, prepared.hash());
        });
        int next = s.run(() -> registry.latestVersion(env, req.bucketId(), flowId)) + 1;
        String comment = req.comment() == null || req.comment().isBlank()
                ? "version " + next + " | hash " + prepared.hash().substring(0, 12) + " | by " + d.startedBy()
                : req.comment();
        s.run(() -> {
            registry.uploadVersion(env, req.bucketId(), flowId, next, comment, prepared.snapshot());
            registry.updateFlowHash(env, req.bucketId(), flowId, name, prepared.hash());
            return null;
        });
        d.info("Uploaded registry version " + next + " (flow " + flowId + ")");
        s.done("v" + next);

        // IMPORT / UPDATE
        s.start("IMPORT", pgId.isPresent() ? "updating to v" + next : "importing v" + next);
        String pg;
        Outcome outcome;
        if (pgId.isEmpty()) {
            pg = s.run(() -> nifi.importVersionedFlow(env, rootPg, name, comment, pos[0], pos[1],
                    req.registryClientId(), req.bucketId(), flowId, next));
            d.info("Imported process group " + pg + " at x=" + (int) pos[0] + " y=" + (int) pos[1]);
            outcome = Outcome.IMPORTED;
        } else {
            pg = pgId.get();
            d.info("Update request sent for process group " + pg + " – polling…");
            s.run(() -> {
                nifi.changeVersion(env, pg, req.registryClientId(), req.bucketId(), flowId, next, poller);
                return null;
            });
            d.info("Process group now at v" + next);
            outcome = Outcome.UPDATED;
        }
        s.done("v" + next);

        // CONTROLLER_SERVICES
        s.start("CONTROLLER_SERVICES", null);
        s.run(() -> {
            nifi.enableControllerServices(env, pg);
            return null;
        });
        d.info("Enable request sent - polling...");
        enableAndWait(d, s, env, pg);

        // PARAMETERS
        s.start("PARAMETERS", null);
        if (cfg.syncParameters() || !overrides.isEmpty()) {
            Map<String, String> desired = cfg.syncParameters() ? prepared.desiredParameters() : overrides;
            List<String> changed = s.run(() -> nifi.updateParameterContext(env, pg, desired, poller));
            if (changed.isEmpty()) {
                d.info("Parameter context already up to date");
                s.done("no changes");
            } else {
                d.info("Parameter context updated: " + String.join(", ", changed));
                s.done(changed.size() + " updated");
            }
        } else {
            s.done("sync disabled");
        }

        // START
        s.start("START", null);
        if (req.startFlow()) {
            s.run(() -> {
                nifi.setRunState(env, pg, "RUNNING");
                return null;
            });
            d.info("Process group started");
            if (cfg.transmitRemoteGroups()) {
                try {
                    nifi.transmitRemoteGroups(env, pg);
                    d.info("Remote process groups transmitting");
                } catch (Exception e) {
                    d.warn("Could not enable RPG transmission: " + describe(e));
                }
            }
            s.done("running");
        } else {
            d.info("Left as-is (START_FLOW=false)");
            s.done("not started");
        }

        d.info("Done: " + name);
        return outcome;
    }

    /** enable_controller_services poll loop — same rules as the script. */
    private void enableAndWait(Deployment d, Stages s, Environment env, String pg) {
        int max = cfg.controllerServicePolls();
        for (int i = 1; i <= max; i++) {
            final int poll = i;
            ServiceCounts c = s.run(() -> nifi.controllerServiceCounts(env, pg));
            String line = "Poll " + poll + "/" + max + ": enabled=" + c.enabled() + " enabling=" + c.enabling()
                    + " disabled=" + c.disabled() + " total=" + c.total();
            d.info(line);
            d.progress(s.flow, "CONTROLLER_SERVICES", "started", line);

            if (c.enabled() == c.total()) {
                d.info("All controller services enabled");
                s.done(c.total() + " enabled");
                return;
            }
            // Stuck: nothing enabling, some still disabled after 3 polls (invalid config).
            if (c.enabling() == 0 && c.disabled() > 0 && poll > 3) {
                d.warn("Some services stuck in DISABLED: " + String.join(", ", c.disabledNames()));
                d.warn("Check NiFi bulletins for errors");
                if (cfg.failOnDisabledServices()) {
                    s.fail("stuck DISABLED", null);
                }
                s.done(c.enabled() + "/" + c.total() + " enabled");
                return;
            }
            sleep(cfg.pollIntervalSeconds());
        }
        d.warn("Timed out waiting for controller services to enable");
        if (cfg.failOnDisabledServices()) s.fail("timed out", null);
        s.done("timed out");
    }

    private Map<String, RegistryFlow> registryFlowsByName(Environment env, String bucketId) {
        Map<String, RegistryFlow> m = new java.util.HashMap<>();
        for (RegistryFlow f : registry.bucketFlows(env, bucketId)) {
            m.putIfAbsent(f.name(), f);
        }
        return m;
    }

    private static void sleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted");
        }
    }

    static String describe(Throwable e) {
        if (e instanceof RestClientResponseException r) {
            String body = r.getResponseBodyAsString();
            return "HTTP " + r.getStatusCode().value() + (body.isBlank() ? "" : ": "
                    + (body.length() > 300 ? body.substring(0, 300) + "…" : body));
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** Tracks the current stage of one flow and turns exceptions into a failed stage. */
    private static final class Stages {
        private final Deployment d;
        final String flow;
        private String current;

        Stages(Deployment d, String flow) {
            this.d = d;
            this.flow = flow;
        }

        void start(String stage, String detail) {
            if (current != null) done(null);
            current = stage;
            d.progress(flow, stage, "started", detail);
        }

        void done(String detail) {
            if (current == null) return;
            d.progress(flow, current, "done", detail);
            current = null;
        }

        void skip(String detail) {
            d.progress(flow, current, "skipped", detail);
            current = null;
        }

        void fail(String detail, Throwable cause) {
            d.progress(flow, current, "failed", detail);
            d.error(flow + " failed at " + current + (detail != null ? ": " + detail : ""));
            current = null;
            throw new FlowFailed(detail, cause);
        }

        <T> T run(java.util.function.Supplier<T> step) {
            try {
                return step.get();
            } catch (FlowFailed e) {
                throw e;
            } catch (Exception e) {
                fail(describe(e), e);
                return null; // unreachable
            }
        }
    }
}
