package com.flowdeployer.deploy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowdeployer.config.DeployerProperties;
import com.flowdeployer.config.DeployerProperties.Environment;
import com.flowdeployer.flows.FlowCatalog;
import com.flowdeployer.flows.FlowCatalog.FlowFile;
import com.flowdeployer.web.Dtos.DeploymentRequest;
import com.flowdeployer.web.Dtos.DeploymentStarted;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Launches the existing deploy script as a Kubernetes Job.
 *
 * The Job spec comes from a template YAML (your current Job manifest). For each deployment the backend
 * gives it a unique name and adds/overrides the env vars below on the FIRST container. Credentials are
 * NOT passed from here — keep them in the template as secretKeyRef / envFrom.
 */
@Service
public class DeploymentService {

    private static final Logger log = LoggerFactory.getLogger(DeploymentService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    public static final String LABEL_APP = "app.kubernetes.io/managed-by";
    public static final String LABEL_APP_VALUE = "flow-deployer";
    public static final String LABEL_ENV = "flow-deployer/environment";

    private final KubernetesClient k8s;
    private final DeployerProperties props;
    private final FlowCatalog catalog;
    private final ObjectMapper mapper;

    public DeploymentService(KubernetesClient k8s, DeployerProperties props, FlowCatalog catalog, ObjectMapper mapper) {
        this.k8s = k8s;
        this.props = props;
        this.catalog = catalog;
        this.mapper = mapper;
    }

    public String namespaceFor(Environment env) {
        return StringUtils.hasText(env.jobNamespace()) ? env.jobNamespace() : props.kubernetes().namespace();
    }

    public DeploymentStarted launch(Environment env, DeploymentRequest req) {
        Map<String, FlowFile> flows = catalog.asMap();
        List<String> unknown = req.flows().stream().filter(f -> !flows.containsKey(f)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown flows: " + String.join(", ", unknown));
        }
        Map<String, Map<String, String>> overrides = sanitiseOverrides(req, flows);

        Job job = loadTemplate();
        String name = jobName();
        String ns = namespaceFor(env);

        job.getMetadata().setName(name);
        job.getMetadata().setGenerateName(null);
        job.getMetadata().setNamespace(ns);
        job.getMetadata().setResourceVersion(null);
        labels(job.getMetadata().getLabels(), env, l -> job.getMetadata().setLabels(l));
        Map<String, String> annotations = job.getMetadata().getAnnotations() == null
                ? new HashMap<>() : new HashMap<>(job.getMetadata().getAnnotations());
        annotations.put("flow-deployer/flows", String.join(",", req.flows()));
        annotations.put("flow-deployer/bucket", req.bucketName());
        if (StringUtils.hasText(req.comment())) {
            annotations.put("flow-deployer/comment", req.comment());
        }
        job.getMetadata().setAnnotations(annotations);

        job.getSpec().setTtlSecondsAfterFinished(props.kubernetes().ttlSecondsAfterFinished());
        // Pod template labels too, so the pod is easy to find and filter.
        var podMeta = job.getSpec().getTemplate().getMetadata();
        if (podMeta != null) {
            labels(podMeta.getLabels(), env, podMeta::setLabels);
        }

        Container c = job.getSpec().getTemplate().getSpec().getContainers().get(0);
        c.setEnv(mergeEnv(c.getEnv(), jobEnv(env, req, overrides)));

        k8s.batch().v1().jobs().inNamespace(ns).resource(job).create();
        log.info("Launched job {}/{} for env={} flows={}", ns, name, env.id(), req.flows());
        return new DeploymentStarted(name, ns);
    }

    /** Env vars handed to the deploy script. Names match the script's CONFIG section. */
    private Map<String, String> jobEnv(Environment env, DeploymentRequest req, Map<String, Map<String, String>> overrides) {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("NIFI_URL", env.nifiUrl());
        e.put("REGISTRY_URL", env.registryUrl());
        e.put("TARGET_RPG_URL", env.rpgUrl());
        e.put("REGISTRY_ID", req.registryClientId());
        e.put("REGISTRY_BUCKET_ID", req.bucketId());
        e.put("BUCKET_NAME", req.bucketName());
        e.put("FLOWS", String.join(",", req.flows()));
        e.put("START_FLOW", Boolean.toString(req.startFlow()));
        e.put("POSITION_STEP", Integer.toString(req.positionStep()));
        e.put("DEPLOY_COMMENT", req.comment() == null ? "" : req.comment());
        try {
            e.put("PARAM_OVERRIDES", mapper.writeValueAsString(overrides));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
        e.put("PROGRESS_MARKERS", "true");
        return e;
    }

    /** Drops empty maps and refuses any attempt to set a sensitive parameter from the UI. */
    private Map<String, Map<String, String>> sanitiseOverrides(DeploymentRequest req, Map<String, FlowFile> flows) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (req.parameterOverrides() == null) return out;
        req.parameterOverrides().forEach((flow, params) -> {
            if (params == null || params.isEmpty() || !req.flows().contains(flow)) return;
            List<String> sensitive = catalog.sensitiveParameterNames(flows.get(flow));
            List<String> bad = params.keySet().stream().filter(sensitive::contains).toList();
            if (!bad.isEmpty()) {
                throw new IllegalArgumentException("Sensitive parameters cannot be overridden from the UI: " + bad);
            }
            out.put(flow, new LinkedHashMap<>(params));
        });
        return out;
    }

    private Job loadTemplate() {
        Path p = Path.of(props.kubernetes().jobTemplate());
        try (InputStream in = Files.newInputStream(p)) {
            Job job = k8s.batch().v1().jobs().load(in).item();
            if (job.getSpec() == null || job.getSpec().getTemplate().getSpec().getContainers().isEmpty()) {
                throw new IllegalStateException("Job template has no containers: " + p);
            }
            return job;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read job template " + p, e);
        }
    }

    private String jobName() {
        String suffix = Integer.toString(ThreadLocalRandom.current().nextInt(36 * 36 * 36, 36 * 36 * 36 * 36), 36);
        String name = props.kubernetes().jobNamePrefix() + "-" + LocalDateTime.now().format(STAMP) + "-" + suffix;
        return name.length() > 63 ? name.substring(0, 63) : name; // DNS label limit for the pod's job-name label
    }

    private static void labels(Map<String, String> existing, Environment env, java.util.function.Consumer<Map<String, String>> setter) {
        Map<String, String> l = existing == null ? new HashMap<>() : new HashMap<>(existing);
        l.put(LABEL_APP, LABEL_APP_VALUE);
        l.put(LABEL_ENV, env.id());
        setter.accept(l);
    }

    private static List<EnvVar> mergeEnv(List<EnvVar> existing, Map<String, String> values) {
        List<EnvVar> out = new ArrayList<>();
        if (existing != null) {
            existing.stream().filter(v -> !values.containsKey(v.getName())).forEach(out::add);
        }
        values.forEach((k, v) -> out.add(new EnvVarBuilder().withName(k).withValue(v).build()));
        return out;
    }
}
