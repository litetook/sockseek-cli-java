package dev.litetook.sockseek;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The brake that sockseek cannot apply for us.
 *
 * <p>The Soulseek server bans an account for 30 minutes if searches arrive too fast, and
 * sockseek keeps itself under that with a budget of 34 searches per 220 seconds. But that
 * budget lives <b>inside one process</b>, and this tool runs one process per playlist. Every
 * new playlist therefore starts with a full budget, while the server has been counting the
 * whole time - so a row of small playlists can put seventy searches on the server in two
 * minutes with each process entirely within its own rules.
 *
 * <p>Nothing had noticed this while playlists were chosen one at a time by hand, with minutes
 * between them. {@code --all} made it a real possibility on the first night it existed (found
 * when the owner asked, on 2026-10-17, why every playlist logs in again).
 *
 * <p>So a playlist earns the time its searches cost: {@code searches / perWindow * 220} seconds.
 * If it finished sooner than that, the next one waits for the difference. A playlist that took
 * longer than it earned waits for nothing, which is the usual case for a big one.
 *
 * <p>The cost is honest and worth saying out loud: the owner's 672 missing tracks cannot be
 * searched in under {@code 672 / 34 * 220} seconds, about 72 minutes, however they are split.
 * Being quicker than that was never speed - it was borrowing against a ban.
 */
public final class Pace {

    /** sockseek's own window, and the one the server is assumed to use. */
    public static final int WINDOW_SECONDS = 220;

    /** sockseek's own default, and so ours: the number the server is known to tolerate. */
    public static final int DEFAULT_PER_WINDOW = 34;

    private final int perWindow;
    private final Consumer<String> say;
    private long allowedAt;
    private long startedAt;

    /** {@code perWindow} of 0 or less turns the brake off entirely. */
    public Pace(int perWindow, Consumer<String> say) {
        this.perWindow = perWindow;
        this.say = say;
    }

    public boolean on() {
        return perWindow > 0;
    }

    /**
     * Wait until this playlist may start, then remember when it did.
     *
     * <p>Interrupted means the person pressed Ctrl+C; the wait ends and the run goes on to
     * find that out for itself rather than swallowing it here.
     */
    public void waitTurn() {
        startedAt = System.nanoTime();
        if (!on() || allowedAt <= startedAt) {
            return;
        }
        long waitMillis = (allowedAt - startedAt) / 1_000_000;
        say.accept("pausing " + (waitMillis / 1000) + " s so that all playlists together stay "
                + "under " + perWindow + " searches per " + WINDOW_SECONDS + " s - the "
                + "server bans an account that goes faster");
        try {
            Thread.sleep(waitMillis);
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
        }
        startedAt = System.nanoTime();
    }

    /** What that playlist actually asked the server for. */
    public void spent(int searches) {
        if (!on() || searches <= 0) {
            return;
        }
        allowedAt = startedAt
                + (long) ((double) searches / perWindow * WINDOW_SECONDS * 1_000_000_000L);
    }

    /**
     * How many searches a playlist issued: the lines of the list file the engine wrote.
     *
     * <p>Not the number of missing tracks - the cache holds some of them back, and counting
     * those would make the tool wait for searches it never made. The file is what was really
     * handed to sockseek.
     */
    public static int searchesOf(Path listFile) {
        try {
            return (int) Files.readAllLines(listFile, StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.isBlank()).count();
        } catch (IOException | RuntimeException noFile) {
            return 0;   // nothing was written, so nothing was searched
        }
    }
}
