package com.flowdeployer.deploy;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import com.flowdeployer.web.Dtos.DeploymentRecord;
import com.flowdeployer.web.Dtos.DeploymentRequest;
import com.flowdeployer.web.Dtos.LogEvent;
import com.flowdeployer.web.Dtos.PhaseEvent;
import com.flowdeployer.web.Dtos.ProgressEvent;
import com.flowdeployer.web.Dtos.SummaryEvent;

/**
 * One deployment run. Keeps every event so a browser that (re)connects — after a refresh, or from
 * another machine — gets the full history replayed, then live updates.
 */
public class Deployment {

    public enum Phase { Running, Succeeded, Failed, Cancelled }

    public record Event(String type, Object data) {
        public boolean terminal() {
            return "phase".equals(type) && data instanceof PhaseEvent p && !Phase.Running.name().equals(p.phase());
        }
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final String id;
    private final String environmentId;
    private final String environmentName;
    private final DeploymentRequest request;
    private final String startedBy;
    private final Instant startedAt = Instant.now();
    private volatile Instant finishedAt;
    private volatile Phase phase = Phase.Running;
    private volatile SummaryEvent summary;
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    private final List<Event> events = new ArrayList<>();
    private final List<BlockingQueue<Event>> subscribers = new CopyOnWriteArrayList<>();

    public Deployment(String id, String environmentId, String environmentName, DeploymentRequest request, String startedBy) {
        this.id = id;
        this.environmentId = environmentId;
        this.environmentName = environmentName;
        this.request = request;
        this.startedBy = startedBy;
        emit(new Event("phase", new PhaseEvent(Phase.Running.name())));
    }

    // ---------------------------------------------------------------- events

    private synchronized void emit(Event e) {
        events.add(e);
        for (BlockingQueue<Event> q : subscribers) {
            q.offer(e);
        }
    }

    /** Returns a queue pre-filled with the history; new events are appended as they happen. */
    public synchronized BlockingQueue<Event> subscribe() {
        BlockingQueue<Event> q = new LinkedBlockingQueue<>(events);
        subscribers.add(q);
        return q;
    }

    public void unsubscribe(BlockingQueue<Event> q) {
        subscribers.remove(q);
    }

    public void info(String msg) {
        emit(new Event("log", new LogEvent("[INFO]    " + TS.format(Instant.now()) + " " + msg)));
    }

    public void warn(String msg) {
        emit(new Event("log", new LogEvent("[WARN]    " + TS.format(Instant.now()) + " " + msg)));
    }

    public void error(String msg) {
        emit(new Event("log", new LogEvent("[ERROR]   " + TS.format(Instant.now()) + " " + msg)));
    }

    public void section(String msg) {
        emit(new Event("log", new LogEvent("[SECTION] " + TS.format(Instant.now()) + " " + msg)));
    }

    public void progress(String flow, String stage, String status, String detail) {
        emit(new Event("progress", new ProgressEvent(flow, stage, status, detail)));
    }

    public void summary(SummaryEvent s) {
        this.summary = s;
        emit(new Event("summary", s));
    }

    public void finish(Phase p) {
        this.phase = p;
        this.finishedAt = Instant.now();
        emit(new Event("phase", new PhaseEvent(p.name())));
    }

    // ---------------------------------------------------------------- state

    public void requestCancel() {
        cancelRequested.set(true);
        warn("Cancel requested — stopping after the current flow");
    }

    public boolean cancelRequested() {
        return cancelRequested.get();
    }

    public boolean finished() {
        return phase != Phase.Running;
    }

    public String id() { return id; }
    public String environmentId() { return environmentId; }
    public DeploymentRequest request() { return request; }
    public String startedBy() { return startedBy; }

    public DeploymentRecord record() {
        return new DeploymentRecord(id, environmentId, environmentName, request.bucketName(), request.flows(),
                startedBy, startedAt.toString(), finishedAt == null ? null : finishedAt.toString(), phase.name(),
                summary, request.comment());
    }
}
