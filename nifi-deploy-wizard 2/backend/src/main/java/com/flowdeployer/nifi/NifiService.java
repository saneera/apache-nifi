package com.flowdeployer.nifi;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowdeployer.config.DeployerProperties.Environment;
import com.flowdeployer.web.Dtos.CheckResult;
import com.flowdeployer.web.Dtos.RegistryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * NiFi REST API — every call the deploy script made, in Java.
 * All calls authenticate with a cached single-user token and retry once on 401 (token expired).
 */
@Service
public class NifiService {

    private static final Logger log = LoggerFactory.getLogger(NifiService.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private record CachedToken(String token, Instant expires) { }

    private final RestClients clients;
    private final RegistryService registry;
    private final Map<String, CachedToken> tokens = new ConcurrentHashMap<>();

    public NifiService(RestClients clients, RegistryService registry) {
        this.clients = clients;
        this.registry = registry;
    }

    // ------------------------------------------------------------------ auth + plumbing

    private RestClient nifi(Environment env) {
        return clients.forUrl(env.nifiUrl(), env.insecureSkipTlsVerify());
    }

    /** POST /nifi-api/access/token (authenticate). Cached for 30 minutes. */
    public String token(Environment env) {
        CachedToken cached = tokens.get(env.id());
        if (cached != null && cached.expires().isAfter(Instant.now())) {
            return cached.token();
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", env.username());
        form.add("password", env.password());
        String token = nifi(env).post()
                .uri("/nifi-api/access/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("NiFi returned an empty access token");
        }
        tokens.put(env.id(), new CachedToken(token.trim(), Instant.now().plusSeconds(1800)));
        return token.trim();
    }

    private <T> T authed(Environment env, Function<String, T> call) {
        try {
            return call.apply("Bearer " + token(env));
        } catch (HttpClientErrorException.Unauthorized e) {
            tokens.remove(env.id());
            return call.apply("Bearer " + token(env));
        }
    }

    public JsonNode get(Environment env, String uri, Object... vars) {
        return authed(env, b -> nifi(env).get().uri(uri, vars)
                .header(HttpHeaders.AUTHORIZATION, b).retrieve().body(JsonNode.class));
    }

    public JsonNode post(Environment env, Object body, String uri, Object... vars) {
        return authed(env, b -> nifi(env).post().uri(uri, vars)
                .header(HttpHeaders.AUTHORIZATION, b).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(JsonNode.class));
    }

    public JsonNode put(Environment env, Object body, String uri, Object... vars) {
        return authed(env, b -> nifi(env).put().uri(uri, vars)
                .header(HttpHeaders.AUTHORIZATION, b).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(JsonNode.class));
    }

    public void delete(Environment env, String uri, Object... vars) {
        authed(env, b -> nifi(env).delete().uri(uri, vars)
                .header(HttpHeaders.AUTHORIZATION, b).retrieve().toBodilessEntity());
    }

    // ------------------------------------------------------------------ wizard reads

    /** Mirrors wait_for_nifi + authenticate + get_root_pg in the script, plus a Registry ping. */
    public List<CheckResult> checks(Environment env) {
        List<CheckResult> out = new ArrayList<>();
        tokens.remove(env.id()); // force a fresh login so the check is real

        boolean authed = false;
        try {
            token(env);
            authed = true;
            out.add(new CheckResult("nifi", "NiFi API reachable", true, "POST /nifi-api/access/token"));
            out.add(new CheckResult("auth", "Authenticated", true, "single-user · token ok"));
        } catch (Exception e) {
            log.warn("NiFi auth check failed for {}: {}", env.id(), e.getMessage());
            out.add(new CheckResult("nifi", "NiFi API reachable", false, shorten(e)));
            out.add(new CheckResult("auth", "Authenticated", false, "skipped"));
        }

        try {
            int n = registry.listBuckets(env, false).size();
            out.add(new CheckResult("registry", "Registry reachable", true, n + " buckets"));
        } catch (Exception e) {
            out.add(new CheckResult("registry", "Registry reachable", false, shorten(e)));
        }

        if (authed) {
            try {
                out.add(new CheckResult("root", "Root process group", true, rootProcessGroupId(env)));
            } catch (Exception e) {
                out.add(new CheckResult("root", "Root process group", false, shorten(e)));
            }
        } else {
            out.add(new CheckResult("root", "Root process group", false, "skipped"));
        }
        return out;
    }

    /** GET /nifi-api/controller/registry-clients */
    /** GET /nifi-api/controller/registry-clients — only NiFi Registry clients (skips GitHub/GitLab/Azure DevOps/Bitbucket). */
    public List<RegistryClient> registryClients(Environment env) {
        JsonNode body = get(env, "/nifi-api/controller/registry-clients");
        List<RegistryClient> valid = new ArrayList<>();
        List<RegistryClient> invalid = new ArrayList<>();
        if (body != null) {
            for (JsonNode r : body.path("registries")) {
                JsonNode c = r.path("component");
                if (!c.path("type").asText("").endsWith("NifiRegistryFlowRegistryClient")) continue;
                JsonNode errors = c.path("validationErrors");
                boolean ok = !errors.isArray() || errors.isEmpty();
                String url = c.path("properties").path("url").asText("");
                String label = c.path("name").asText() + (url.isBlank() ? "" : " → " + url) + (ok ? "" : " [INVALID]");
                (ok ? valid : invalid).add(new RegistryClient(r.path("id").asText(), label));
            }
        }
        valid.addAll(invalid); // valid ones first, so the preselected client is a working one
        return valid;
    }

    // ------------------------------------------------------------------ process groups

    /** get_root_pg */
    public String rootProcessGroupId(Environment env) {
        return get(env, "/nifi-api/flow/process-groups/root").path("processGroupFlow").path("id").asText();
    }

    /** Child process groups of a parent: GET /flow/process-groups/{id} → .processGroupFlow.flow.processGroups */
    public JsonNode childGroups(Environment env, String parentId) {
        return get(env, "/nifi-api/flow/process-groups/{id}", parentId)
                .path("processGroupFlow").path("flow").path("processGroups");
    }

    /** find_pg: child of the root PG whose name equals the flow name. */
    public Optional<String> findProcessGroup(JsonNode children, String name) {
        for (JsonNode pg : children) {
            if (name.equals(pg.path("component").path("name").asText())) {
                return Optional.of(pg.path("component").path("id").asText(pg.path("id").asText()));
            }
        }
        return Optional.empty();
    }

    /** get_next_position: to the right of the right-most existing group. Returns {x, y}. */
    public double[] nextPosition(JsonNode children, int step) {
        double maxX = Double.NEGATIVE_INFINITY;
        double y = 0;
        for (JsonNode pg : children) {
            JsonNode pos = pg.path("position").isMissingNode() ? pg.path("component").path("position") : pg.path("position");
            double x = pos.path("x").asDouble(0);
            if (x > maxX) {
                maxX = x;
                y = pos.path("y").asDouble(0);
            }
        }
        return maxX == Double.NEGATIVE_INFINITY ? new double[]{0, 0} : new double[]{maxX + step, y};
    }

    public JsonNode processGroup(Environment env, String pgId) {
        return get(env, "/nifi-api/process-groups/{id}", pgId);
    }

    /** import_flow: create a version-controlled child PG under the parent. Returns the new PG id. */
    public String importVersionedFlow(Environment env, String parentId, String name, String comments,
                                      double x, double y, String registryClientId, String bucketId,
                                      String flowId, int version) {
        ObjectNode body = JSON.objectNode();
        body.putObject("revision").put("version", 0);
        ObjectNode component = body.putObject("component");
        component.put("name", name);
        if (comments != null && !comments.isBlank()) component.put("comments", comments);
        component.putObject("position").put("x", x).put("y", y);
        ObjectNode vci = component.putObject("versionControlInformation");
        vci.put("registryId", registryClientId);
        vci.put("bucketId", bucketId);
        vci.put("flowId", flowId);
        vci.put("version", String.valueOf(version)); // String in NiFi 2.x
        JsonNode created = post(env, body,
                "/nifi-api/process-groups/{id}/process-groups?parameterContextHandlingStrategy=KEEP_EXISTING", parentId);
        return created.path("id").asText();
    }

    /**
     * update_flow_version: POST /versions/update-requests/process-groups/{pg}, poll until complete, then delete
     * the request. Throws if NiFi reports a failure.
     */
    public void changeVersion(Environment env, String pgId, String registryClientId, String bucketId, String flowId,
                              int version, Poller poller) {
        long revision = processGroup(env, pgId).path("revision").path("version").asLong();
        ObjectNode body = JSON.objectNode();
        body.putObject("processGroupRevision").put("version", revision);
        ObjectNode vci = body.putObject("versionControlInformation");
        vci.put("groupId", pgId);
        vci.put("registryId", registryClientId);
        vci.put("bucketId", bucketId);
        vci.put("flowId", flowId);
        vci.put("version", String.valueOf(version));

        JsonNode req = post(env, body, "/nifi-api/versions/update-requests/process-groups/{id}", pgId).path("request");
        String requestId = req.path("requestId").asText();
        try {
            poller.until("version update", () -> {
                JsonNode r = get(env, "/nifi-api/versions/update-requests/{id}", requestId).path("request");
                failIfFailed(r, "Version update");
                return r.path("complete").asBoolean(false) ? r : null;
            });
        } finally {
            quietly(() -> delete(env, "/nifi-api/versions/update-requests/{id}", requestId));
        }
    }

    // ------------------------------------------------------------------ controller services

    /** PUT /flow/process-groups/{pg}/controller-services {state: ENABLED} */
    public void enableControllerServices(Environment env, String pgId) {
        ObjectNode body = JSON.objectNode();
        body.put("id", pgId);
        body.put("state", "ENABLED");
        put(env, body, "/nifi-api/flow/process-groups/{id}/controller-services", pgId);
    }

    public record ServiceCounts(int total, int enabled, int enabling, int disabled, List<String> disabledNames) { }

    public ServiceCounts controllerServiceCounts(Environment env, String pgId) {
        JsonNode list = get(env,
                "/nifi-api/flow/process-groups/{id}/controller-services?includeAncestorGroups=false&includeDescendantGroups=true",
                pgId).path("controllerServices");
        int total = 0, enabled = 0, enabling = 0, disabled = 0;
        List<String> disabledNames = new ArrayList<>();
        for (JsonNode cs : list) {
            total++;
            String state = cs.path("component").path("state").asText();
            switch (state) {
                case "ENABLED" -> enabled++;
                case "ENABLING" -> enabling++;
                default -> {
                    disabled++;
                    String name = cs.path("component").path("name").asText();
                    JsonNode errors = cs.path("component").path("validationErrors");
                    disabledNames.add(errors.isArray() && !errors.isEmpty() ? name + " (" + errors.get(0).asText() + ")" : name);
                }
            }
        }
        return new ServiceCounts(total, enabled, enabling, disabled, disabledNames);
    }

    // ------------------------------------------------------------------ parameters

    /**
     * update_parameter_contexts: sets non-sensitive parameter values on the PG's bound parameter context.
     * Only parameters that already exist and whose value differs are sent. Returns the names changed.
     */
    public List<String> updateParameterContext(Environment env, String pgId, Map<String, String> desired, Poller poller) {
        String ctxId = processGroup(env, pgId).path("component").path("parameterContext").path("id").asText("");
        if (ctxId.isEmpty() || desired.isEmpty()) return List.of();

        JsonNode ctx = get(env, "/nifi-api/parameter-contexts/{id}", ctxId);
        ArrayNode changes = JSON.arrayNode();
        List<String> changed = new ArrayList<>();
        for (JsonNode p : ctx.path("component").path("parameters")) {
            JsonNode param = p.path("parameter");
            String name = param.path("name").asText();
            if (param.path("sensitive").asBoolean(false) || !desired.containsKey(name)) continue;
            String want = desired.get(name);
            String have = param.path("value").isNull() ? null : param.path("value").asText(null);
            if (want != null && !want.equals(have)) {
                ObjectNode np = changes.addObject().putObject("parameter");
                np.put("name", name);
                np.put("value", want);
                np.put("sensitive", false);
                changed.add(name);
            }
        }
        if (changed.isEmpty()) return changed;

        ObjectNode body = JSON.objectNode();
        body.put("id", ctxId);
        body.set("revision", ctx.path("revision"));
        ObjectNode component = body.putObject("component");
        component.put("id", ctxId);
        component.set("parameters", changes);

        JsonNode req = post(env, body, "/nifi-api/parameter-contexts/{id}/update-requests", ctxId).path("request");
        String requestId = req.path("requestId").asText();
        try {
            poller.until("parameter update", () -> {
                JsonNode r = get(env, "/nifi-api/parameter-contexts/{c}/update-requests/{r}", ctxId, requestId).path("request");
                failIfFailed(r, "Parameter update");
                return r.path("complete").asBoolean(false) ? r : null;
            });
        } finally {
            quietly(() -> delete(env, "/nifi-api/parameter-contexts/{c}/update-requests/{r}", ctxId, requestId));
        }
        return changed;
    }

    // ------------------------------------------------------------------ run state

    /** control_pg_state: PUT /flow/process-groups/{pg} {state: RUNNING|STOPPED} */
    public void setRunState(Environment env, String pgId, String state) {
        ObjectNode body = JSON.objectNode();
        body.put("id", pgId);
        body.put("state", state);
        put(env, body, "/nifi-api/flow/process-groups/{id}", pgId);
    }

    /** PUT /remote-process-groups/process-group/{pg}/run-status {state: TRANSMITTING} */
    public void transmitRemoteGroups(Environment env, String pgId) {
        ObjectNode body = JSON.objectNode();
        body.put("state", "TRANSMITTING");
        put(env, body, "/nifi-api/remote-process-groups/process-group/{id}/run-status", pgId);
    }

    // ------------------------------------------------------------------ helpers

    private static void failIfFailed(JsonNode request, String what) {
        String reason = request.path("failureReason").asText("");
        if (!reason.isBlank() && !"null".equals(reason)) {
            throw new IllegalStateException(what + " failed: " + reason);
        }
    }

    private static void quietly(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.debug("Ignored cleanup error: {}", e.getMessage());
        }
    }

    static String shorten(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return m.length() > 140 ? m.substring(0, 140) + "…" : m;
    }
}
