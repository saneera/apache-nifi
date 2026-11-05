package com.flowdeployer.nifi;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.web.client.RestClient;

/** Read-only calls against the NiFi REST API — the same endpoints the deploy script uses. */
@Service
public class NifiService {

    private static final Logger log = LoggerFactory.getLogger(NifiService.class);

    private record CachedToken(String token, Instant expires) { }

    private final RestClients clients;
    private final RegistryService registry;
    private final Map<String, CachedToken> tokens = new ConcurrentHashMap<>();

    public NifiService(RestClients clients, RegistryService registry) {
        this.clients = clients;
        this.registry = registry;
    }

    private RestClient nifi(Environment env) {
        return clients.forUrl(env.nifiUrl(), env.insecureSkipTlsVerify());
    }

    /** POST /nifi-api/access/token (single-user auth). Cached for 10 minutes. */
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
        tokens.put(env.id(), new CachedToken(token.trim(), Instant.now().plusSeconds(600)));
        return token.trim();
    }

    private String bearer(Environment env) {
        return "Bearer " + token(env);
    }

    /** Mirrors wait_for_nifi + authenticate + get_root_pg in the script, plus a Registry ping. */
    public List<CheckResult> checks(Environment env) {
        List<CheckResult> out = new ArrayList<>();
        tokens.remove(env.id()); // force a fresh login so the check is real

        String token = null;
        try {
            token = token(env);
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

        if (token != null) {
            try {
                JsonNode root = nifi(env).get()
                        .uri("/nifi-api/flow/process-groups/root")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .retrieve()
                        .body(JsonNode.class);
                String id = root == null ? "?" : root.path("processGroupFlow").path("id").asText("?");
                out.add(new CheckResult("root", "Root process group", true, id));
            } catch (Exception e) {
                out.add(new CheckResult("root", "Root process group", false, shorten(e)));
            }
        } else {
            out.add(new CheckResult("root", "Root process group", false, "skipped"));
        }
        return out;
    }

    /** GET /nifi-api/controller/registry-clients */
    public List<RegistryClient> registryClients(Environment env) {
        JsonNode body = nifi(env).get()
                .uri("/nifi-api/controller/registry-clients")
                .header(HttpHeaders.AUTHORIZATION, bearer(env))
                .retrieve()
                .body(JsonNode.class);
        List<RegistryClient> out = new ArrayList<>();
        if (body != null) {
            for (JsonNode r : body.path("registries")) {
                out.add(new RegistryClient(r.path("id").asText(), r.path("component").path("name").asText()));
            }
        }
        return out;
    }

    static String shorten(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return m.length() > 140 ? m.substring(0, 140) + "…" : m;
    }
}
