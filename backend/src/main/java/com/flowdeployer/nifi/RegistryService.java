package com.flowdeployer.nifi;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowdeployer.config.DeployerProperties.Environment;
import com.flowdeployer.web.Dtos.Bucket;
import com.flowdeployer.web.Dtos.CreateBucketResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** NiFi Registry REST calls: buckets (list / find / create) and the flows inside a bucket. */
@Service
public class RegistryService {

    private static final Logger log = LoggerFactory.getLogger(RegistryService.class);
    /** The script stores the flow hash in the Registry flow's description as "flow-hash:<hash>". */
    public static final String HASH_PREFIX = "flow-hash:";

    public record RegistryFlow(String id, String name, String hash, int versionCount) { }

    private final RestClients clients;

    public RegistryService(RestClients clients) {
        this.clients = clients;
    }

    private RestClient registry(Environment env) {
        return clients.forUrl(env.registryUrl(), env.insecureSkipTlsVerify());
    }

    private RestClient.RequestHeadersSpec<?> auth(Environment env, RestClient.RequestHeadersSpec<?> spec) {
        if (StringUtils.hasText(env.registryToken())) {
            spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + env.registryToken());
        }
        return spec;
    }

    /** GET /nifi-registry-api/buckets — optionally with a flow count per bucket. */
    public List<Bucket> listBuckets(Environment env, boolean withCounts) {
        JsonNode body = auth(env, registry(env).get().uri("/nifi-registry-api/buckets"))
                .retrieve().body(JsonNode.class);
        List<Bucket> out = new ArrayList<>();
        if (body != null) {
            for (JsonNode b : body) {
                String id = b.path("identifier").asText();
                int count = withCounts ? safeFlowCount(env, id) : 0;
                out.add(new Bucket(id, b.path("name").asText(), b.path("description").asText(null), count));
            }
        }
        return out;
    }

    public Optional<Bucket> findBucket(Environment env, String name) {
        return listBuckets(env, false).stream().filter(b -> b.name().equals(name)).findFirst()
                .map(b -> new Bucket(b.id(), b.name(), b.description(), safeFlowCount(env, b.id())));
    }

    /**
     * Find-or-create. POST /nifi-registry-api/buckets and read .identifier from the response.
     * If someone created it in the meantime (409), the existing bucket is returned instead.
     */
    public CreateBucketResult createBucket(Environment env, String name, String description) {
        Optional<Bucket> existing = findBucket(env, name);
        if (existing.isPresent()) {
            return new CreateBucketResult(false, existing.get());
        }
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("name", name);
        if (StringUtils.hasText(description)) {
            payload.put("description", description);
        }
        try {
            JsonNode created = auth(env, registry(env).post()
                    .uri("/nifi-registry-api/buckets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload))
                    .retrieve().body(JsonNode.class);
            if (created == null || !created.hasNonNull("identifier")) {
                throw new IllegalStateException("Registry did not return a bucket identifier");
            }
            log.info("Created Registry bucket {} ({}) in {}", name, created.get("identifier").asText(), env.id());
            return new CreateBucketResult(true, new Bucket(created.get("identifier").asText(), name, description, 0));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                return findBucket(env, name).map(b -> new CreateBucketResult(false, b)).orElseThrow(() -> e);
            }
            throw e;
        }
    }

    /** GET /nifi-registry-api/buckets/{id}/flows */
    public List<RegistryFlow> bucketFlows(Environment env, String bucketId) {
        JsonNode body = auth(env, registry(env).get().uri("/nifi-registry-api/buckets/{id}/flows", bucketId))
                .retrieve().body(JsonNode.class);
        List<RegistryFlow> out = new ArrayList<>();
        if (body != null) {
            for (JsonNode f : body) {
                String desc = f.path("description").asText("");
                String hash = desc.startsWith(HASH_PREFIX) ? desc.substring(HASH_PREFIX.length()).trim() : null;
                out.add(new RegistryFlow(f.path("identifier").asText(), f.path("name").asText(), hash,
                        f.path("versionCount").asInt(0)));
            }
        }
        return out;
    }

    private int safeFlowCount(Environment env, String bucketId) {
        try {
            return bucketFlows(env, bucketId).size();
        } catch (Exception e) {
            return 0;
        }
    }
}
