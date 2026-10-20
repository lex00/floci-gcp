package io.floci.gcp.services.pubsub;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

final class MutableClock extends Clock {

    private volatile Instant now;
    private final AtomicReference<Runnable> onNextRead = new AtomicReference<>();

    MutableClock(Instant start) {
        this.now = start;
    }

    void advance(Duration duration) {
        now = now.plus(duration);
    }

    // Runs on the thread that reads the clock next, so a test can pause it at that point.
    void onNextRead(Runnable hook) {
        onNextRead.set(hook);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Instant instant() {
        Runnable hook = onNextRead.getAndSet(null);
        if (hook != null) {
            hook.run();
        }
        return now;
    }
}
