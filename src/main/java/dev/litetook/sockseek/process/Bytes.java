package dev.litetook.sockseek.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Is anything still arriving on disk?
 *
 * <p>The second half of the silence watchdog, and the half that keeps it honest. Finished
 * tracks are the obvious sign of life, but near the end of a playlist there may be only one
 * job left, legitimately pulling a 100 MB flac from a slow uploader. Nothing finishes for half
 * an hour and yet everything is working perfectly. Judged on completions alone the watchdog
 * would kill exactly the download the person was waiting for.
 *
 * <p>So the folder is watched too. sockseek writes a download to a {@code .incomplete} file
 * and grows it as the bytes come, so a changing total size is proof that the network is
 * moving - and it needs no console, no pipe and no cooperation from sockseek at all.
 *
 * <p>The walk is cheap but not free, so it is sampled rather than polled: at most once every
 * {@link #SAMPLE_MILLIS}, and the caller only starts asking once the events have gone quiet.
 */
public final class Bytes {

    /** Five seconds: a download that is moving changes a file size far more often than this. */
    public static final long SAMPLE_MILLIS = 5000;

    private final Path dir;
    private final long sampleMillis;
    private long fingerprint = Long.MIN_VALUE;
    private long lastChangeAt = System.nanoTime();
    private long lastLookAt;

    public Bytes(Path dir) {
        this(dir, SAMPLE_MILLIS);
    }

    /** A shorter sample than the default, for a watchdog whose window is shorter than it. */
    public Bytes(Path dir, long sampleMillis) {
        this.dir = dir;
        this.sampleMillis = Math.max(50, sampleMillis);
    }

    /** How long since the folder last changed, in seconds. */
    public double silentSeconds() {
        return (System.nanoTime() - lastChangeAt) / 1_000_000_000.0;
    }

    /** Look again, if it has been long enough. Never throws: a folder is not worth a run. */
    public void poll() {
        long now = System.nanoTime();
        if (lastLookAt != 0 && (now - lastLookAt) / 1_000_000 < sampleMillis) {
            return;
        }
        lastLookAt = now;
        long seen = measure();
        if (seen != fingerprint) {
            fingerprint = seen;
            lastChangeAt = now;
        }
    }

    /**
     * One number for "what is in the folder right now": how many files, and how many bytes.
     *
     * <p>Both, because either alone can stand still while the other moves - a file that is
     * renamed from {@code .incomplete} to its final name keeps its size, and a download that
     * is growing keeps the count.
     */
    private long measure() {
        try (Stream<Path> walk = Files.walk(dir)) {
            long[] totals = new long[2];
            walk.filter(Files::isRegularFile).forEach(file -> {
                totals[0]++;
                try {
                    totals[1] += Files.size(file);
                } catch (IOException | RuntimeException vanished) {
                    // a .incomplete file renamed between the walk and the read: next sample
                }
            });
            return totals[0] * 31 + totals[1];
        } catch (IOException | RuntimeException cannotWalk) {
            return fingerprint;   // unreadable folder: say nothing changed rather than guess
        }
    }
}
