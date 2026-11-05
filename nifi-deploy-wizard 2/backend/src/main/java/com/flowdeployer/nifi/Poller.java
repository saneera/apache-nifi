package com.flowdeployer.nifi;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Polls until a check returns non-null. Used for NiFi's asynchronous update requests.
 * The deployment supplies one that reports progress and honours cancellation.
 */
public interface Poller {

    <T> T until(String what, Supplier<T> check);

    /** Default implementation: fixed interval, overall timeout, optional progress callback and cancel check. */
    static Poller of(Duration interval, Duration timeout, java.util.function.Consumer<String> onProgress,
                     java.util.function.BooleanSupplier cancelled) {
        return new Poller() {
            @Override
            public <T> T until(String what, Supplier<T> check) {
                Instant deadline = Instant.now().plus(timeout);
                while (true) {
                    T result = check.get();
                    if (result != null) return result;
                    if (cancelled.getAsBoolean()) {
                        throw new CancelledException();
                    }
                    if (Instant.now().isAfter(deadline)) {
                        throw new IllegalStateException("Timed out waiting for " + what);
                    }
                    onProgress.accept("Waiting for " + what + "…");
                    try {
                        Thread.sleep(interval.toMillis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new CancelledException();
                    }
                }
            }
        };
    }

    class CancelledException extends RuntimeException {
        public CancelledException() {
            super("Cancelled", null, false, false);
        }
    }
}
