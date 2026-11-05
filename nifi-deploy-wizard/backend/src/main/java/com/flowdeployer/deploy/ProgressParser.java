package com.flowdeployer.deploy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.flowdeployer.web.Dtos.ProgressEvent;
import com.flowdeployer.web.Dtos.SummaryEvent;

/**
 * Turns deploy-script log lines into progress events. One instance per log stream (it is stateful).
 *
 * Preferred: machine-readable markers emitted by the script (see scripts/script-changes.sh):
 *   ::progress flow=gateway-ingest stage=REGISTRY_UPLOAD status=done detail=v15
 *   ::summary total=5 deployed=3 skipped=2
 *
 * Fallback: recognises the existing human log lines ("Processing X", "No changes detected",
 * "All controller services enabled", "Done: X", ...) so the UI still moves before the script is changed.
 */
public class ProgressParser {

    public sealed interface Parsed permits Progress, Summary { }
    public record Progress(ProgressEvent event) implements Parsed { }
    public record Summary(SummaryEvent event) implements Parsed { }

    public record Result(List<Parsed> events, boolean isMarker) { }

    static final List<String> STAGES = List.of(
            "PREPARE", "HASH", "REGISTRY_UPLOAD", "IMPORT", "CONTROLLER_SERVICES", "PARAMETERS", "START");
    private static final Set<String> STATUSES = Set.of("started", "done", "failed", "skipped");

    private static final Pattern MARKER = Pattern.compile("::(progress|summary)\\s+(.*)$");
    private static final Pattern KV = Pattern.compile("(\\w+)=(\\S+)");
    private static final Pattern DETAIL = Pattern.compile("\\bdetail=(.*)$");

    // Fallback patterns based on the current script's log messages.
    private static final Pattern PROCESSING = Pattern.compile("Processing\\s+(\\S+)");
    private static final Pattern DONE = Pattern.compile("\\bDone:\\s+(\\S+)");
    private static final Pattern POLL = Pattern.compile("Poll\\s+(\\d+)/(\\d+).*");
    private static final Pattern TOTAL = Pattern.compile("Total\\s*:\\s*(\\d+)");
    private static final Pattern DEPLOYED = Pattern.compile("Deployed\\s*:\\s*(\\d+)");
    private static final Pattern SKIPPED = Pattern.compile("Skipped\\s*:\\s*(\\d+)");

    private boolean sawMarkers;
    private String currentFlow;
    private final Map<String, Integer> fallbackStage = new HashMap<>();
    private final int[] summary = {-1, -1, -1};

    public Result parse(String line) {
        Matcher m = MARKER.matcher(line);
        if (m.find()) {
            sawMarkers = true;
            return new Result(parseMarker(m.group(1), m.group(2)), true);
        }
        return new Result(sawMarkers ? List.of() : fallback(line), false);
    }

    private List<Parsed> parseMarker(String kind, String rest) {
        Map<String, String> kv = new HashMap<>();
        Matcher dm = DETAIL.matcher(rest);
        String detail = null;
        if (dm.find()) {
            detail = dm.group(1).trim();
            rest = rest.substring(0, dm.start());
        }
        Matcher km = KV.matcher(rest);
        while (km.find()) {
            kv.put(km.group(1).toLowerCase(Locale.ROOT), km.group(2));
        }
        if (kind.equals("summary")) {
            return List.of(new Summary(new SummaryEvent(
                    intOr(kv.get("total")), intOr(kv.get("deployed")), intOr(kv.get("skipped")))));
        }
        String flow = kv.get("flow");
        String stage = kv.getOrDefault("stage", "").toUpperCase(Locale.ROOT);
        String status = kv.getOrDefault("status", "").toLowerCase(Locale.ROOT);
        if (flow == null || !STAGES.contains(stage) || !STATUSES.contains(status)) {
            return List.of();
        }
        return List.of(new Progress(new ProgressEvent(flow, stage, status, detail)));
    }

    private List<Parsed> fallback(String line) {
        List<Parsed> out = new ArrayList<>();
        Matcher m;
        if ((m = PROCESSING.matcher(line)).find() && line.contains("[SECTION]")) {
            currentFlow = m.group(1);
            advance(out, currentFlow, "PREPARE", null);
        } else if (currentFlow == null) {
            // nothing flow-specific yet
        } else if (line.contains("No changes detected")) {
            out.add(new Progress(new ProgressEvent(currentFlow, "HASH", "skipped", "no changes")));
        } else if (line.contains("Local hash") || line.contains("Registry hash")) {
            advance(out, currentFlow, "HASH", null);
        } else if (line.contains("registry flow") || line.contains("registry version") || line.contains("Uploaded")) {
            advance(out, currentFlow, "REGISTRY_UPLOAD", null);
        } else if (line.contains("Import") || line.contains("Update request") || line.contains("process group")) {
            advance(out, currentFlow, "IMPORT", null);
        } else if ((m = POLL.matcher(line)).find() || line.contains("Enable request sent")) {
            advance(out, currentFlow, "CONTROLLER_SERVICES", m.find(0) ? m.group(0) : null);
        } else if (line.contains("All controller services enabled")) {
            complete(out, currentFlow, "CONTROLLER_SERVICES");
        } else if (line.toLowerCase(Locale.ROOT).contains("parameter context")) {
            advance(out, currentFlow, "PARAMETERS", null);
        } else if ((m = DONE.matcher(line)).find()) {
            complete(out, m.group(1), "START");
        }

        if ((m = TOTAL.matcher(line)).find()) summary[0] = Integer.parseInt(m.group(1));
        if ((m = DEPLOYED.matcher(line)).find()) summary[1] = Integer.parseInt(m.group(1));
        if ((m = SKIPPED.matcher(line)).find()) summary[2] = Integer.parseInt(m.group(1));
        if (summary[0] >= 0 && summary[1] >= 0 && summary[2] >= 0) {
            out.add(new Summary(new SummaryEvent(summary[0], summary[1], summary[2])));
            summary[0] = summary[1] = summary[2] = -1;
        }
        return out;
    }

    /** Marks all earlier stages done and the given stage started (only ever moves forward). */
    private void advance(List<Parsed> out, String flow, String stage, String detail) {
        int target = STAGES.indexOf(stage);
        int current = fallbackStage.getOrDefault(flow, -1);
        if (target < current) return;
        for (int i = Math.max(current, 0); i < target; i++) {
            out.add(new Progress(new ProgressEvent(flow, STAGES.get(i), "done", null)));
        }
        out.add(new Progress(new ProgressEvent(flow, stage, "started", detail)));
        fallbackStage.put(flow, target);
    }

    private void complete(List<Parsed> out, String flow, String stage) {
        int target = STAGES.indexOf(stage);
        int current = fallbackStage.getOrDefault(flow, -1);
        for (int i = Math.max(current, 0); i <= target; i++) {
            out.add(new Progress(new ProgressEvent(flow, STAGES.get(i), "done", null)));
        }
        fallbackStage.put(flow, target + 1);
    }

    private static int intOr(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
