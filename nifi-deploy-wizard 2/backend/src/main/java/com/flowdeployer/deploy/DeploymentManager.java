package com.flowdeployer.deploy;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;

import com.flowdeployer.config.DeployerProperties;
import com.flowdeployer.config.DeployerProperties.Environment;
import com.flowdeployer.flows.FlowCatalog;
import com.flowdeployer.flows.FlowCatalog.FlowFile;
import com.flowdeployer.web.Dtos.DeploymentRecord;
import com.flowdeployer.web.Dtos.DeploymentRequest;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Starts deployments on a background thread (so they survive the browser closing), allows one running
 * deployment per environment, keeps recent history for the UI, and writes the audit trail.
 */
@Service
public class DeploymentManager {

    private static final Logger log = LoggerFactory.getLogger(DeploymentManager.class);
    private static final DateTimeFormatter ID_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Raised when the environment already has a running deployment. */
    public static class BusyException extends RuntimeException {
        public BusyException(String m) {
            super(m);
        }
    }

    private final DeploymentRunner runner;
    private final FlowCatalog catalog;
    private final AuditLog audit;
    private final ExecutorService executor;
    private final int historySize;

    private final Map<String, String> runningByEnv = new ConcurrentHashMap<>();
    private final Map<String, Deployment> live = Collections.synchronizedMap(new LinkedHashMap<>());
    private final List<DeploymentRecord> pastRecords = Collections.synchronizedList(new ArrayList<>());

    public DeploymentManager(DeploymentRunner runner, FlowCatalog catalog, AuditLog audit, ExecutorService deployExecutor,
                             DeployerProperties props) {
        this.runner = runner;
        this.catalog = catalog;
        this.audit = audit;
        this.executor = deployExecutor;
        this.historySize = props.audit().history();
    }

    @PostConstruct
    void loadHistory() {
        pastRecords.addAll(audit.loadFinished(historySize));
    }

    public Deployment start(Environment env, DeploymentRequest req, String user) {
        validate(req);
        String id = "dep-" + LocalDateTime.now().format(ID_TS) + "-"
                + Integer.toString(ThreadLocalRandom.current().nextInt(1296, 46656), 36);

        String existing = runningByEnv.putIfAbsent(env.id(), id);
        if (existing != null) {
            Deployment other = live.get(existing);
            throw new BusyException("A deployment is already running on " + env.name()
                    + (other != null ? " (started by " + other.startedBy() + ", " + existing + ")" : ""));
        }

        Deployment d = new Deployment(id, env.id(), env.name(), req, user);
        live.put(id, d);
        trim();
        audit.record("started", d.record());
        log.info("Deployment {} started by {} on {}: {}", id, user, env.id(), req.flows());

        executor.submit(() -> {
            try {
                runner.run(d, env);
            } catch (Exception e) { // runner handles its own errors; this is a last resort
                log.error("Deployment {} crashed", id, e);
                if (!d.finished()) {
                    d.error("Internal error: " + e.getMessage());
                    d.finish(Deployment.Phase.Failed);
                }
            } finally {
                runningByEnv.remove(env.id(), id);
                audit.record("finished", d.record());
            }
        });
        return d;
    }

    private void validate(DeploymentRequest req) {
        Map<String, FlowFile> flows = catalog.asMap();
        List<String> unknown = req.flows().stream().filter(f -> !flows.containsKey(f)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown flows: " + String.join(", ", unknown));
        }
        if (req.parameterOverrides() != null) {
            req.parameterOverrides().forEach((flow, params) -> {
                FlowFile f = flows.get(flow);
                if (f == null || params == null) return;
                List<String> bad = params.keySet().stream().filter(catalog.sensitiveParameterNames(f)::contains).toList();
                if (!bad.isEmpty()) {
                    throw new IllegalArgumentException("Sensitive parameters cannot be set from the UI: " + bad);
                }
            });
        }
    }

    public Optional<Deployment> get(String id) {
        return Optional.ofNullable(live.get(id));
    }

    public Optional<DeploymentRecord> record(String id) {
        Deployment d = live.get(id);
        if (d != null) return Optional.of(d.record());
        synchronized (pastRecords) {
            return pastRecords.stream().filter(r -> r.id().equals(id)).findFirst();
        }
    }

    /** Newest first: in-memory deployments (with full event history) then older ones from the audit file. */
    public List<DeploymentRecord> recent() {
        List<DeploymentRecord> out = new ArrayList<>();
        synchronized (live) {
            live.values().forEach(d -> out.add(d.record()));
        }
        synchronized (pastRecords) {
            pastRecords.stream().filter(r -> out.stream().noneMatch(o -> o.id().equals(r.id()))).forEach(out::add);
        }
        out.sort((a, b) -> b.startedAt().compareTo(a.startedAt()));
        return out.size() > historySize ? out.subList(0, historySize) : out;
    }

    public void cancel(String id) {
        Deployment d = live.get(id);
        if (d == null) throw new IllegalArgumentException("Unknown or expired deployment: " + id);
        if (!d.finished()) d.requestCancel();
    }

    /** Keep at most historySize deployments (with their events) in memory; drop the oldest finished ones. */
    private void trim() {
        synchronized (live) {
            var it = live.entrySet().iterator();
            while (live.size() > historySize && it.hasNext()) {
                Deployment d = it.next().getValue();
                if (d.finished()) {
                    pastRecords.add(d.record());
                    it.remove();
                }
            }
        }
    }
}
