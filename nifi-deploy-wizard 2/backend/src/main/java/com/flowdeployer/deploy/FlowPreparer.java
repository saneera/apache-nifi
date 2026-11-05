package com.flowdeployer.deploy;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowdeployer.flows.FlowCatalog;
import com.flowdeployer.flows.FlowCatalog.FlowFile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * prepare_flow + inject_parameters + calculate_hash.
 *
 * - Remote Process Groups get the environment's TARGET_RPG_URL as their target URI(s).
 * - Non-sensitive parameter overrides from the wizard are written into the snapshot's parameter contexts.
 * - The hash is sha256 of the original file (plus the overrides, when there are any), so the wizard's
 *   New / Changed / Unchanged status is computed exactly the same way.
 *
 * If your script's prepare_flow did more (e.g. other URL rewrites), add it to {@link #prepare}.
 */
@Component
public class FlowPreparer {

    public record Prepared(ObjectNode snapshot, String hash, Map<String, String> desiredParameters) { }

    private final ObjectMapper mapper;

    public FlowPreparer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Prepared prepare(FlowFile flow, String rpgUrl, Map<String, String> overrides) {
        ObjectNode snapshot = flow.root().deepCopy();

        if (StringUtils.hasText(rpgUrl)) {
            rewriteRemoteGroups(snapshot.path("flowContents"), rpgUrl);
        }

        Map<String, String> desired = new HashMap<>();
        if (flow.parameterContextName() != null) {
            JsonNode params = snapshot.path("parameterContexts").path(flow.parameterContextName()).path("parameters");
            for (JsonNode p : params) {
                if (p.path("sensitive").asBoolean(false)) continue;
                String name = p.path("name").asText();
                if (overrides != null && overrides.containsKey(name)) {
                    ((ObjectNode) p).put("value", overrides.get(name));
                }
                if (!p.path("value").isNull() && !p.path("value").isMissingNode()) {
                    desired.put(name, p.path("value").asText());
                }
            }
        }
        return new Prepared(snapshot, hash(flow, overrides), desired);
    }

    /** sha256(file) — or sha256(file + overrides JSON) when this flow has overrides. */
    public String hash(FlowFile flow, Map<String, String> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return flow.hash();
        }
        try {
            byte[] file = Files.readAllBytes(flow.path());
            byte[] ovr = mapper.writeValueAsBytes(new TreeMap<>(overrides));
            byte[] all = new byte[file.length + ovr.length];
            System.arraycopy(file, 0, all, 0, file.length);
            System.arraycopy(ovr, 0, all, file.length, ovr.length);
            return FlowCatalog.sha256(all);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + flow.path(), e);
        }
    }

    /** Walks flowContents and every nested processGroups[] setting targetUri(s) on remoteProcessGroups[]. */
    private static void rewriteRemoteGroups(JsonNode group, String url) {
        if (group == null || group.isMissingNode()) return;
        for (JsonNode rpg : group.path("remoteProcessGroups")) {
            ObjectNode o = (ObjectNode) rpg;
            o.put("targetUris", url);
            if (o.has("targetUri")) o.put("targetUri", url);
        }
        for (JsonNode child : group.path("processGroups")) {
            rewriteRemoteGroups(child, url);
        }
    }
}
