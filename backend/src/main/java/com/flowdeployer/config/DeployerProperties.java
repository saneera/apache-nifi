package com.flowdeployer.config;

import java.util.List;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything the wizard needs to know, bound from application.yml ("deployer.*").
 * Credentials should come from environment variables populated by a Kubernetes Secret.
 */
@ConfigurationProperties(prefix = "deployer")
public record DeployerProperties(List<Environment> environments, Flows flows, Kubernetes kubernetes) {

    public record Environment(
            String id,
            String name,
            String description,
            String cluster,
            String nifiUrl,
            String registryUrl,
            String rpgUrl,
            /** Username/password used by the backend for checks and Registry lookups. */
            String username,
            String password,
            /** Shown in the UI only — e.g. "secret/nifi-single-user". */
            @DefaultValue("secret/nifi-single-user") String credentialsRef,
            String defaultBucket,
            @DefaultValue("#9BA8B8") String color,
            /** Equivalent of curl -k. Use only where the cluster CA can't be added to the truststore. */
            @DefaultValue("false") boolean insecureSkipTlsVerify,
            /** Optional bearer token for a secured NiFi Registry. Empty = no auth header (matches the script). */
            String registryToken,
            /** Optional namespace override for this environment's Job. */
            String jobNamespace) {
    }

    public record Flows(
            /** Directory holding the *.json flow definitions — the same files the Job sees at /flows. */
            @DefaultValue("/flows") String dir) {
    }

    public record Kubernetes(
            @DefaultValue("default") String namespace,
            /** Path to the Job YAML used as the template for every deployment. */
            @DefaultValue("/config/job-template.yaml") String jobTemplate,
            @DefaultValue("nifi-deploy") String jobNamePrefix,
            @DefaultValue("86400") int ttlSecondsAfterFinished,
            /** How long to wait for the Job's pod to start before giving up. */
            @DefaultValue("300") int podStartTimeoutSeconds) {
    }

    public Optional<Environment> environment(String id) {
        return environments == null ? Optional.empty()
                : environments.stream().filter(e -> e.id().equals(id)).findFirst();
    }
}
