package com.flowdeployer.config;

import java.util.List;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything the deployer needs, bound from application.yml ("deployer.*").
 * Credentials should come from environment variables populated by a Kubernetes Secret.
 */
@ConfigurationProperties(prefix = "deployer")
public record DeployerProperties(List<Environment> environments, Flows flows, Deploy deploy, Audit audit,
                                 Security security) {

    public record Environment(
            String id,
            String name,
            String description,
            String cluster,
            String nifiUrl,
            String registryUrl,
            /** Written into every Remote Process Group's target URIs in the flow (TARGET_RPG_URL in the script). */
            String rpgUrl,
            /** NiFi single-user credentials used for all API calls (from the Secret). */
            String username,
            String password,
            /** Shown in the UI only — e.g. "secret/nifi-single-user". */
            @DefaultValue("secret/nifi-single-user") String credentialsRef,
            String defaultBucket,
            @DefaultValue("#9BA8B8") String color,
            /** Equivalent of curl -k. Prefer adding the cluster CA to the truststore. */
            @DefaultValue("false") boolean insecureSkipTlsVerify,
            /** Optional bearer token for a secured NiFi Registry. Empty = no auth header (matches the script). */
            String registryToken,
            /** Registry URL as NiFi itself reaches it, used when creating a Registry Client (defaults to registry-url). */
            String registryClientUrl,
            /** Id of a controller-level SSL Context Service for an HTTPS Registry (auto-detected when empty). */
            String registryClientSslContextService) {
    }

    public record Flows(
            /** Directory holding the *.json flow definitions (what the script read as /flows). */
            @DefaultValue("/flows") String dir) {
    }

    public record Deploy(
            /** POSITION_STEP default: horizontal gap between newly imported process groups. */
            @DefaultValue("400") int positionStep,
            /** Seconds between polls (controller services, update requests). */
            @DefaultValue("3") int pollIntervalSeconds,
            /** Max controller-service polls before giving up (the script used 30). */
            @DefaultValue("30") int controllerServicePolls,
            /** Fail the flow if controller services stay DISABLED (invalid) — the script returned 1. */
            @DefaultValue("true") boolean failOnDisabledServices,
            /** Max seconds to wait for a NiFi version-change / parameter update request. */
            @DefaultValue("600") int updateRequestTimeoutSeconds,
            /** Push non-sensitive parameter values from the flow file into existing NiFi parameter contexts. */
            @DefaultValue("true") boolean syncParameters,
            /** Also start Remote Process Group transmission when starting a flow. */
            @DefaultValue("true") boolean transmitRemoteGroups,
            /** Stop the whole deployment at the first failed flow (like set -e). */
            @DefaultValue("true") boolean stopOnFailure) {
    }

    public record Audit(
            /** JSON-lines audit file; empty = in-memory only. Mount a PVC here. */
            @DefaultValue("/data/audit.jsonl") String file,
            /** How many past deployments to keep in memory / show in the UI. */
            @DefaultValue("50") int history) {
    }

    public record Security(
            /** Header set by the auth proxy in front (oauth2-proxy, ingress auth) with the user's name. */
            @DefaultValue("X-Forwarded-User") String userHeader) {
    }

    public Optional<Environment> environment(String id) {
        return environments == null ? Optional.empty()
                : environments.stream().filter(e -> e.id().equals(id)).findFirst();
    }
}
