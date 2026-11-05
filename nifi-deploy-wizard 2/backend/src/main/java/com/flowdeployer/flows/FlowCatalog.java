package com.flowdeployer.flows;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowdeployer.config.DeployerProperties;
import com.flowdeployer.web.Dtos.Parameter;
import com.flowdeployer.web.Dtos.ParameterContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads the flow definitions from the flows directory (the same files the Job processes as /flows/*.json).
 *
 * HASH: sha256 of the raw file bytes — identical to `sha256sum "$ORIGINAL_FLOW" | cut -d' ' -f1`.
 * The deploy script must store that same value in the Registry flow description ("flow-hash:<hash>")
 * for the NEW / CHANGED / UNCHANGED status to line up. See scripts/script-changes.sh.
 */
@Component
public class FlowCatalog {

    private static final Logger log = LoggerFactory.getLogger(FlowCatalog.class);

    public record FlowFile(String name, Path path, String hash, String parameterContextName, JsonNode root) { }

    private final Path dir;
    private final ObjectMapper mapper;

    public FlowCatalog(DeployerProperties props, ObjectMapper mapper) {
        this.dir = Path.of(props.flows().dir());
        this.mapper = mapper;
    }

    /** Scans the directory on every call — files are few and this keeps the UI in sync with the volume. */
    public List<FlowFile> scan() {
        List<FlowFile> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            log.warn("Flows directory {} does not exist", dir);
            return out;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : files) {
                try {
                    out.add(read(p));
                } catch (IOException e) {
                    log.warn("Skipping unreadable flow file {}: {}", p, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list flows directory " + dir, e);
        }
        out.sort(Comparator.comparing(FlowFile::name));
        return out;
    }

    public Optional<FlowFile> byName(String name) {
        return scan().stream().filter(f -> f.name().equals(name)).findFirst();
    }

    private FlowFile read(Path p) throws IOException {
        byte[] bytes = Files.readAllBytes(p);
        JsonNode root = mapper.readTree(bytes);
        // Same lookup as the script: .flowContents.name // .header.flowName
        String name = text(root.path("flowContents").path("name"))
                .or(() -> text(root.path("header").path("flowName")))
                .orElse(p.getFileName().toString().replaceFirst("\\.json$", ""));
        String ctx = text(root.path("flowContents").path("parameterContextName")).orElse(null);
        return new FlowFile(name, p, sha256(bytes), ctx, root);
    }

    /** Reads the parameter context embedded in a NiFi 2.x flow export ("parameterContexts" map). */
    public Optional<ParameterContext> parameterContext(FlowFile flow) {
        if (flow.parameterContextName() == null) {
            return Optional.empty();
        }
        JsonNode ctx = flow.root().path("parameterContexts").path(flow.parameterContextName());
        if (ctx.isMissingNode()) {
            return Optional.of(new ParameterContext(flow.parameterContextName(), List.of()));
        }
        List<Parameter> params = new ArrayList<>();
        for (Iterator<JsonNode> it = ctx.path("parameters").elements(); it.hasNext(); ) {
            JsonNode p = it.next();
            boolean sensitive = p.path("sensitive").asBoolean(false);
            params.add(new Parameter(
                    p.path("name").asText(),
                    sensitive ? null : p.path("value").asText(null), // never send sensitive values to the browser
                    sensitive,
                    p.path("description").asText(null)));
        }
        params.sort(Comparator.comparing(Parameter::name));
        return Optional.of(new ParameterContext(flow.parameterContextName(), params));
    }

    /** Names of sensitive parameters, used to reject overrides that try to set them. */
    public List<String> sensitiveParameterNames(FlowFile flow) {
        return parameterContext(flow).map(c -> c.parameters().stream()
                .filter(Parameter::sensitive).map(Parameter::name).toList()).orElse(List.of());
    }

    private static Optional<String> text(JsonNode n) {
        return n.isTextual() && !n.asText().isBlank() ? Optional.of(n.asText()) : Optional.empty();
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Map<String, FlowFile> asMap() {
        Map<String, FlowFile> m = new java.util.LinkedHashMap<>();
        scan().forEach(f -> m.put(f.name(), f));
        return m;
    }
}
