package com.flowdeployer.deploy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import com.flowdeployer.deploy.ProgressParser.Parsed;
import com.flowdeployer.deploy.ProgressParser.Progress;
import com.flowdeployer.deploy.ProgressParser.Summary;
import com.flowdeployer.web.Dtos.FailureEvent;
import com.flowdeployer.web.Dtos.LogEvent;
import com.flowdeployer.web.Dtos.PhaseEvent;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Streams a Job's pod log to the browser as Server-Sent Events:
 *   phase    {phase, podName}         Pending → Running → Succeeded | Failed
 *   log      {line}                   every non-marker log line
 *   progress {flow, stage, status, detail}
 *   summary  {total, deployed, skipped}
 *   failure  {message}                stream-level problems (pod never started, etc.)
 *
 * Re-subscribing (e.g. page refresh) replays the whole log from the start, so the UI rebuilds its state.
 */
@Service
public class LogStreamService {

    private static final Logger log = LoggerFactory.getLogger(LogStreamService.class);
    private static final Duration POLL = Duration.ofSeconds(2);

    private final KubernetesClient k8s;
    private final ExecutorService executor;

    public LogStreamService(KubernetesClient k8s, ExecutorService logStreamExecutor) {
        this.k8s = k8s;
        this.executor = logStreamExecutor;
    }

    public SseEmitter stream(String namespace, String jobName, Duration podStartTimeout) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout; we complete it ourselves
        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(e -> cancelled.set(true));

        executor.submit(() -> {
            try {
                run(namespace, jobName, podStartTimeout, emitter, cancelled);
                emitter.complete();
            } catch (ClientGone e) {
                log.debug("Client left log stream for {}", jobName);
            } catch (Exception e) {
                log.warn("Log stream for {} failed: {}", jobName, e.getMessage());
                try {
                    send(emitter, "failure", new FailureEvent(e.getMessage()));
                    emitter.complete();
                } catch (Exception ignored) {
                    emitter.completeWithError(e);
                }
            }
        });
        return emitter;
    }

    private void run(String ns, String jobName, Duration podStartTimeout, SseEmitter emitter, AtomicBoolean cancelled)
            throws Exception {
        Job job = k8s.batch().v1().jobs().inNamespace(ns).withName(jobName).get();
        if (job == null) {
            throw new IllegalArgumentException("Job " + jobName + " not found in namespace " + ns);
        }
        String container = job.getSpec().getTemplate().getSpec().getContainers().get(0).getName();

        send(emitter, "phase", new PhaseEvent("Pending", null));
        Pod pod = waitForPod(ns, jobName, podStartTimeout, emitter, cancelled);
        String podName = pod.getMetadata().getName();
        send(emitter, "phase", new PhaseEvent("Running", podName));

        ProgressParser parser = new ProgressParser();
        try (LogWatch watch = k8s.pods().inNamespace(ns).withName(podName).inContainer(container).watchLog();
             BufferedReader reader = new BufferedReader(new InputStreamReader(watch.getOutput(), StandardCharsets.UTF_8))) {
            String line;
            while (!cancelled.get() && (line = reader.readLine()) != null) {
                ProgressParser.Result r = parser.parse(line);
                if (!r.isMarker()) {
                    send(emitter, "log", new LogEvent(line));
                }
                for (Parsed p : r.events()) {
                    if (p instanceof Progress pr) send(emitter, "progress", pr.event());
                    else if (p instanceof Summary s) send(emitter, "summary", s.event());
                }
            }
        }
        if (cancelled.get()) throw new ClientGone();

        send(emitter, "phase", new PhaseEvent(finalPhase(ns, jobName, podName), podName));
    }

    /** Waits until the Job's newest pod is running or finished; reports image-pull / scheduling problems. */
    private Pod waitForPod(String ns, String jobName, Duration timeout, SseEmitter emitter, AtomicBoolean cancelled)
            throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        String lastReason = null;
        while (Instant.now().isBefore(deadline)) {
            if (cancelled.get()) throw new ClientGone();
            List<Pod> pods = k8s.pods().inNamespace(ns).withLabel("job-name", jobName).list().getItems();
            Pod pod = pods.stream()
                    .max(Comparator.comparing((Pod p) -> String.valueOf(p.getMetadata().getCreationTimestamp())))
                    .orElse(null);
            if (pod != null) {
                String phase = pod.getStatus() == null ? null : pod.getStatus().getPhase();
                if ("Running".equals(phase) || "Succeeded".equals(phase) || "Failed".equals(phase)) {
                    return pod;
                }
                String reason = waitingReason(pod);
                if (reason != null && !reason.equals(lastReason)) {
                    send(emitter, "log", new LogEvent("[deployer] pod " + pod.getMetadata().getName() + ": " + reason));
                    lastReason = reason;
                    if (reason.startsWith("ErrImagePull") || reason.startsWith("ImagePullBackOff")
                            || reason.startsWith("CreateContainerConfigError")) {
                        throw new IllegalStateException("Pod cannot start: " + reason);
                    }
                }
            }
            Thread.sleep(POLL.toMillis());
        }
        throw new IllegalStateException("Timed out waiting for the job's pod to start");
    }

    private static String waitingReason(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) return null;
        return pod.getStatus().getContainerStatuses().stream()
                .filter(cs -> cs.getState() != null && cs.getState().getWaiting() != null)
                .map(cs -> {
                    var w = cs.getState().getWaiting();
                    return w.getReason() + (w.getMessage() != null ? " – " + w.getMessage() : "");
                })
                .findFirst().orElse(null);
    }

    /** After the log ends, wait briefly for the pod/job status to settle. */
    private String finalPhase(String ns, String jobName, String podName) throws InterruptedException {
        for (int i = 0; i < 15; i++) {
            Pod pod = k8s.pods().inNamespace(ns).withName(podName).get();
            String phase = pod == null || pod.getStatus() == null ? null : pod.getStatus().getPhase();
            if ("Succeeded".equals(phase) || "Failed".equals(phase)) return phase;
            Job job = k8s.batch().v1().jobs().inNamespace(ns).withName(jobName).get();
            if (job != null && job.getStatus() != null) {
                if (job.getStatus().getSucceeded() != null && job.getStatus().getSucceeded() > 0) return "Succeeded";
                if (job.getStatus().getFailed() != null && job.getStatus().getFailed() > 0) return "Failed";
            }
            Thread.sleep(POLL.toMillis());
        }
        return "Failed";
    }

    private static void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            throw new ClientGone();
        }
    }

    /** The browser closed the stream; stop quietly. */
    private static final class ClientGone extends RuntimeException {
        ClientGone() {
            super(null, null, false, false);
        }
    }
}
