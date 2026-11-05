package com.flowdeployer.web;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import com.flowdeployer.config.DeployerProperties;
import com.flowdeployer.deploy.Deployment;
import com.flowdeployer.deploy.Deployment.Event;
import com.flowdeployer.deploy.DeploymentManager;
import com.flowdeployer.web.Dtos.DeploymentRecord;
import com.flowdeployer.web.Dtos.DeploymentRequest;
import com.flowdeployer.web.Dtos.DeploymentStarted;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/deployments")
public class DeploymentController {

    private final DeployerProperties props;
    private final DeploymentManager manager;
    private final ExecutorService executor;

    public DeploymentController(DeployerProperties props, DeploymentManager manager, ExecutorService deployExecutor) {
        this.props = props;
        this.manager = manager;
        this.executor = deployExecutor;
    }

    @PostMapping
    public DeploymentStarted start(@Valid @RequestBody DeploymentRequest req, HttpServletRequest http) {
        var env = props.environment(req.environmentId())
                .orElseThrow(() -> new ApiController.NotFoundException("Unknown environment: " + req.environmentId()));
        Deployment d = manager.start(env, req, user(http));
        return new DeploymentStarted(d.id());
    }

    @GetMapping
    public List<DeploymentRecord> recent() {
        return manager.recent();
    }

    @GetMapping("/{id}")
    public ResponseEntity<DeploymentRecord> get(@PathVariable String id) {
        return ResponseEntity.of(manager.record(id));
    }

    /** Stops after the flow currently being deployed (never interrupts a NiFi update half-way). */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable String id) {
        manager.cancel(id);
        return ResponseEntity.accepted().build();
    }

    /**
     * Server-Sent Events: replays everything so far, then streams live.
     * Events: log {line} · progress {flow, stage, status, detail} · summary {...} · phase {phase}
     */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id) {
        Deployment d = manager.get(id).orElseThrow(() ->
                new ApiController.NotFoundException("Deployment " + id + " is no longer in memory (see history)"));
        SseEmitter emitter = new SseEmitter(0L);
        BlockingQueue<Event> queue = d.subscribe();
        Runnable cleanup = () -> d.unsubscribe(queue);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        executor.submit(() -> {
            try {
                while (true) {
                    Event e = queue.poll(15, TimeUnit.SECONDS);
                    if (e == null) {
                        emitter.send(SseEmitter.event().comment("keep-alive"));
                        continue;
                    }
                    emitter.send(SseEmitter.event().name(e.type()).data(e.data(), MediaType.APPLICATION_JSON));
                    if (e.terminal()) {
                        emitter.complete();
                        return;
                    }
                }
            } catch (IOException | IllegalStateException ex) {
                // browser went away — the deployment itself keeps running
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                cleanup.run();
            }
        });
        return emitter;
    }

    /** Identity from the auth proxy header, then container auth, else "anonymous". */
    private String user(HttpServletRequest http) {
        String h = http.getHeader(props.security().userHeader());
        if (StringUtils.hasText(h)) return h.length() > 100 ? h.substring(0, 100) : h;
        if (http.getRemoteUser() != null) return http.getRemoteUser();
        return "anonymous";
    }
}
