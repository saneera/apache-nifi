package com.flowdeployer.flows;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.flowdeployer.config.DeployerProperties.Environment;
import com.flowdeployer.nifi.RegistryService;
import com.flowdeployer.nifi.RegistryService.RegistryFlow;
import com.flowdeployer.web.Dtos.FlowChange;
import com.flowdeployer.web.Dtos.FlowStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Same decision the script makes: compare the local hash with the hash stored on the Registry flow. */
@Service
public class FlowStatusService {

    private final FlowCatalog catalog;
    private final RegistryService registry;

    public FlowStatusService(FlowCatalog catalog, RegistryService registry) {
        this.catalog = catalog;
        this.registry = registry;
    }

    public List<FlowStatus> status(Environment env, String bucketId) {
        Map<String, RegistryFlow> remote = StringUtils.hasText(bucketId)
                ? registry.bucketFlows(env, bucketId).stream()
                        .collect(Collectors.toMap(RegistryFlow::name, Function.identity(), (a, b) -> a))
                : Map.of();

        return catalog.scan().stream().map(f -> {
            RegistryFlow r = remote.get(f.name());
            FlowChange change = r == null ? FlowChange.NEW
                    : f.hash().equalsIgnoreCase(r.hash() == null ? "" : r.hash()) ? FlowChange.UNCHANGED
                    : FlowChange.CHANGED;
            return new FlowStatus(
                    f.name(),
                    f.path().getFileName().toString(),
                    change,
                    f.hash(),
                    r == null ? null : r.hash(),
                    r == null ? null : r.versionCount(),
                    f.parameterContextName());
        }).toList();
    }
}
