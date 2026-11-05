package com.flowdeployer.web;

import java.util.List;

import com.flowdeployer.config.DeployerProperties;
import com.flowdeployer.config.DeployerProperties.Environment;
import com.flowdeployer.flows.FlowCatalog;
import com.flowdeployer.flows.FlowStatusService;
import com.flowdeployer.nifi.NifiService;
import com.flowdeployer.nifi.RegistryService;
import com.flowdeployer.web.Dtos.Bucket;
import com.flowdeployer.web.Dtos.BucketResolveResult;
import com.flowdeployer.web.Dtos.CheckResult;
import com.flowdeployer.web.Dtos.CreateBucketRequest;
import com.flowdeployer.web.Dtos.CreateBucketResult;
import com.flowdeployer.web.Dtos.EnvironmentInfo;
import com.flowdeployer.web.Dtos.FlowStatus;
import com.flowdeployer.web.Dtos.ParameterContext;
import com.flowdeployer.web.Dtos.RegistryClient;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final DeployerProperties props;
    private final NifiService nifi;
    private final RegistryService registry;
    private final FlowCatalog catalog;
    private final FlowStatusService flowStatus;

    public ApiController(DeployerProperties props, NifiService nifi, RegistryService registry, FlowCatalog catalog,
                         FlowStatusService flowStatus) {
        this.props = props;
        this.nifi = nifi;
        this.registry = registry;
        this.catalog = catalog;
        this.flowStatus = flowStatus;
    }

    private Environment env(String id) {
        return props.environment(id).orElseThrow(() -> new NotFoundException("Unknown environment: " + id));
    }

    @GetMapping("/environments")
    public List<EnvironmentInfo> environments() {
        return props.environments().stream().map(e -> new EnvironmentInfo(
                e.id(), e.name(), e.description(), e.cluster(), e.nifiUrl(), e.registryUrl(), e.rpgUrl(),
                e.credentialsRef(), e.defaultBucket(), e.color())).toList();
    }

    @PostMapping("/environments/{envId}/check")
    public List<CheckResult> check(@PathVariable String envId) {
        return nifi.checks(env(envId));
    }

    @GetMapping("/environments/{envId}/registry-clients")
    public List<RegistryClient> registryClients(@PathVariable String envId) {
        return nifi.registryClients(env(envId));
    }

    @GetMapping("/environments/{envId}/buckets")
    public List<Bucket> buckets(@PathVariable String envId) {
        return registry.listBuckets(env(envId), true);
    }

    @GetMapping("/environments/{envId}/buckets/resolve")
    public BucketResolveResult resolveBucket(@PathVariable String envId, @RequestParam String name) {
        return registry.findBucket(env(envId), name)
                .map(b -> new BucketResolveResult(true, b))
                .orElse(new BucketResolveResult(false, null));
    }

    /** Find-or-create: returns created=false when a bucket with that name already exists. */
    @PostMapping("/environments/{envId}/buckets")
    public CreateBucketResult createBucket(@PathVariable String envId, @Valid @RequestBody CreateBucketRequest req) {
        return registry.createBucket(env(envId), req.name().trim(), req.description());
    }

    @GetMapping("/environments/{envId}/flows")
    public List<FlowStatus> flows(@PathVariable String envId, @RequestParam(required = false) String bucketId) {
        return flowStatus.status(env(envId), bucketId);
    }

    @GetMapping("/flows/{name}/parameters")
    public ResponseEntity<ParameterContext> parameters(@PathVariable String name) {
        var flow = catalog.byName(name).orElseThrow(() -> new NotFoundException("Unknown flow: " + name));
        return catalog.parameterContext(flow)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}
