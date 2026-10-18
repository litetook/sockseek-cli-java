package dev.litetook.sockseek.process;

/**
 * Writing next to somebody else's live display without ruining it.
 *
 * <p>While sockseek runs it owns the console - this tool hands it over on purpose, so the
 * download display and the {@code t}/{@code c}/{@code i} keys keep working. sockseek paints a
 * block at the bottom and repaints it in place. Anything printed into the same stream lands
 * wherever its cursor happens to be, which is how a progress line ended up welded to the
 * middle of a track name:
 *
 * <pre>{@code R - Warm Up    [11:16:35] >> 7 of 81 done, 0 downloaded}</pre>
 *
 * <p>Two things fix that, and neither takes the console back.
 *
 * <ul>
 *   <li><b>Start on a fresh line.</b> A leading newline costs nothing and means the worst
 *       case is a line of ours above sockseek's block rather than inside its text.
 *   <li><b>Say it less often.</b> One line per finished track is a line every second or two
 *       on a big playlist, which buries the display it sits next to. A couple of seconds
 *       between them is still live, and the last one is always printed so the final count is
 *       never the second-to-last.
 * </ul>
 *
 * <p>And the window title, where the terminal supports it, carries the running total without
 * touching the screen at all - which is the only place that is genuinely always visible while
 * somebody else is drawing.
 */
public final class Screen {

    /** At most one progress line this often, however many tracks finish. */
    public static final long QUIET_MILLIS = 2000;

    private long lastSaidAt;

    /** True when enough time has passed, or when this is the one that must not be missed. */
    public boolean worthSaying(boolean always) {
        long now = System.currentTimeMillis();
        if (!always && lastSaidAt != 0 && now - lastSaidAt < QUIET_MILLIS) {
            return false;
        }
        lastSaidAt = now;
        return true;
    }

    /**
     * Whether the terminal will understand an escape sequence rather than print it.
     *
     * <p>Windows Terminal says so with {@code WT_SESSION}, and anything with a {@code TERM}
     * that is not {@code dumb} has understood these since the seventies. Old Windows consoles
     * say nothing and would show the raw characters, so they get nothing - a title is a nice
     * thing to have, not a thing worth printing rubbish for.
     */
    public static boolean canSetTitle() {
        if (System.getenv("WT_SESSION") != null) {
            return true;
        }
        String term = System.getenv("TERM");
        if (term != null && !term.isBlank() && !term.equalsIgnoreCase("dumb")) {
            return true;
        }
        return false;
    }

    /**
     * Put the run's state in the window title.
     *
     * <p>OSC 0: it sets the title and prints nothing, so it cannot collide with whatever is
     * being drawn. Written straight to the real stream rather than through the tool's logger,
     * because it is not something anybody wants in {@code session_*.log}.
     */
    public static void title(String text) {
        if (!canSetTitle()) {
            return;
        }
        System.out.print("\u001b]0;" + text.replace('\u0007', ' ') + "\u0007");
        System.out.flush();
    }

    /** Give the title back to whatever it should say when nothing is running. */
    public static void clearTitle() {
        title("sockseek-cli");
    }

    /**
     * Move to the start of a clean line, if we are not already on one.
     *
     * <p>Written straight to the stream rather than as a log line: a log line would carry a
     * clock and an empty message, and what is wanted here is one newline character.
     */
    public static void freshLine() {
        System.out.print(System.lineSeparator());
        System.out.flush();
    }
}
