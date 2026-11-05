package com.flowdeployer.deploy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowdeployer.config.DeployerProperties;
import com.flowdeployer.web.Dtos.DeploymentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Append-only JSON-lines audit trail: one line when a deployment starts, one when it finishes.
 * Also logged through SLF4J (logger "AUDIT") so it reaches your log aggregation.
 */
@Component
public class AuditLog {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");
    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

    private final ObjectMapper mapper;
    private final Path file;

    public AuditLog(ObjectMapper mapper, DeployerProperties props) {
        this.mapper = mapper;
        this.file = StringUtils.hasText(props.audit().file()) ? Path.of(props.audit().file()) : null;
    }

    public void record(String event, DeploymentRecord r) {
        try {
            String line = mapper.writeValueAsString(new Entry(event, r));
            audit.info(line);
            if (file != null) {
                if (file.getParent() != null) Files.createDirectories(file.getParent());
                Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            log.error("Could not write audit entry for {}: {}", r.id(), e.getMessage());
        }
    }

    /** Last N finished deployments from the file, oldest first — seeds the history after a restart. */
    public List<DeploymentRecord> loadFinished(int max) {
        List<DeploymentRecord> out = new ArrayList<>();
        if (file == null || !Files.exists(file)) return out;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = Math.max(0, lines.size() - max * 2); i < lines.size(); i++) {
                try {
                    Entry e = mapper.readValue(lines.get(i), Entry.class);
                    if ("finished".equals(e.event())) out.add(e.deployment());
                } catch (IOException ignored) {
                    // skip malformed line
                }
            }
        } catch (IOException e) {
            log.warn("Could not read audit file {}: {}", file, e.getMessage());
        }
        return out.size() > max ? out.subList(out.size() - max, out.size()) : out;
    }

    public record Entry(String event, DeploymentRecord deployment) { }
}
