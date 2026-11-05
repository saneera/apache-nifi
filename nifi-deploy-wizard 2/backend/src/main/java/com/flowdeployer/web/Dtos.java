package com.flowdeployer.web;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** API contract — kept in sync with frontend/src/api/types.ts. */
public final class Dtos {
    private Dtos() { }

    public record EnvironmentInfo(String id, String name, String description, String cluster, String nifiUrl,
                                  String registryUrl, String rpgUrl, String credentialsRef, String defaultBucket,
                                  String color) { }

    public record CheckResult(String key, String label, boolean ok, String detail) { }

    public record RegistryClient(String id, String name) { }

    public record Bucket(String id, String name, String description, int flowCount) { }

    public record BucketResolveResult(boolean found, Bucket bucket) { }

    public record CreateBucketRequest(
            @NotBlank @Size(max = 200) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*") String name,
            @Size(max = 1000) String description) { }

    public record CreateBucketResult(boolean created, Bucket bucket) { }

    public enum FlowChange { NEW, CHANGED, UNCHANGED }

    public record FlowStatus(String name, String file, FlowChange status, String localHash, String registryHash,
                             Integer registryVersion, String parameterContext) { }

    public record Parameter(String name, String value, boolean sensitive, String description) { }

    public record ParameterContext(String name, List<Parameter> parameters) { }

    public record DeploymentRequest(
            @NotBlank String environmentId,
            @NotBlank String registryClientId,
            @NotBlank String bucketId,
            @NotBlank String bucketName,
            @NotEmpty List<@Pattern(regexp = "[A-Za-z0-9._ -]+") String> flows,
            Map<String, Map<String, String>> parameterOverrides,
            boolean startFlow,
            @Min(0) int positionStep,
            @Size(max = 500) String comment) { }

    public record DeploymentStarted(String deploymentId) { }

    /** A deployment as shown in history and kept in the audit log. */
    public record DeploymentRecord(String id, String environmentId, String environmentName, String bucketName,
                                   List<String> flows, String startedBy, String startedAt, String finishedAt,
                                   String phase, SummaryEvent summary, String comment) { }

    // ---- SSE payloads ----
    public record LogEvent(String line) { }

    public record ProgressEvent(String flow, String stage, String status, String detail) { }

    public record SummaryEvent(int total, int deployed, int skipped, int failed) { }

    public record PhaseEvent(String phase) { }

}
