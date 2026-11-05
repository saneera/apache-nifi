package com.flowdeployer.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowdeployer.flows.FlowCatalog;
import com.flowdeployer.flows.FlowCatalog.FlowFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlowPreparerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final FlowPreparer preparer = new FlowPreparer(mapper);

    private static final String FLOW = """
            {
              "flowContents": {
                "name": "gateway-ingest",
                "parameterContextName": "gw-params",
                "remoteProcessGroups": [ { "targetUris": "https://old:8443/nifi" } ],
                "processGroups": [ { "remoteProcessGroups": [ { "targetUris": "https://old2/nifi", "targetUri": "x" } ] } ]
              },
              "parameterContexts": {
                "gw-params": {
                  "name": "gw-params",
                  "parameters": [
                    { "name": "batch.size", "value": "500", "sensitive": false },
                    { "name": "listen.port", "value": "9443", "sensitive": false },
                    { "name": "secret", "value": null, "sensitive": true }
                  ]
                }
              }
            }
            """;

    private FlowFile load(Path dir) throws Exception {
        Path p = dir.resolve("gateway-ingest.json");
        Files.writeString(p, FLOW);
        byte[] bytes = Files.readAllBytes(p);
        JsonNode root = mapper.readTree(bytes);
        return new FlowFile("gateway-ingest", p, FlowCatalog.sha256(bytes), "gw-params", root);
    }

    @Test
    void rewritesRemoteProcessGroupsRecursively(@TempDir Path dir) throws Exception {
        var prepared = preparer.prepare(load(dir), "https://red:8443/nifi", Map.of());
        JsonNode fc = prepared.snapshot().path("flowContents");
        assertThat(fc.at("/remoteProcessGroups/0/targetUris").asText()).isEqualTo("https://red:8443/nifi");
        assertThat(fc.at("/processGroups/0/remoteProcessGroups/0/targetUris").asText()).isEqualTo("https://red:8443/nifi");
        assertThat(fc.at("/processGroups/0/remoteProcessGroups/0/targetUri").asText()).isEqualTo("https://red:8443/nifi");
    }

    @Test
    void appliesOverridesButNeverSensitive(@TempDir Path dir) throws Exception {
        var prepared = preparer.prepare(load(dir), null, Map.of("batch.size", "750", "secret", "nope"));
        JsonNode params = prepared.snapshot().at("/parameterContexts/gw-params/parameters");
        assertThat(params.get(0).path("value").asText()).isEqualTo("750");
        assertThat(params.get(2).path("value").isNull()).isTrue();
        assertThat(prepared.desiredParameters()).containsEntry("batch.size", "750")
                .containsEntry("listen.port", "9443").doesNotContainKey("secret");
    }

    @Test
    void hashIsFileSha256WithoutOverridesAndChangesWithThem(@TempDir Path dir) throws Exception {
        FlowFile f = load(dir);
        String plain = preparer.prepare(f, null, Map.of()).hash();
        String withOverride = preparer.prepare(f, null, Map.of("batch.size", "750")).hash();
        assertThat(plain).isEqualTo(FlowCatalog.sha256(Files.readAllBytes(f.path())));
        assertThat(withOverride).isNotEqualTo(plain);
        assertThat(preparer.prepare(f, null, Map.of("batch.size", "750")).hash()).isEqualTo(withOverride);
    }

    @Test
    void doesNotMutateTheCatalogCopy(@TempDir Path dir) throws Exception {
        FlowFile f = load(dir);
        preparer.prepare(f, "https://new/nifi", Map.of("batch.size", "1"));
        assertThat(f.root().at("/flowContents/remoteProcessGroups/0/targetUris").asText()).isEqualTo("https://old:8443/nifi");
    }
}
