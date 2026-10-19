package dev.litetook.sockseek.playlist;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What is wrong with a playlist file, line by line, before it is saved.
 *
 * <p>The reader is deliberately forgiving - almost any text becomes a search - and that is
 * what makes an editor dangerous without this: a length written {@code | 5:xx} does not fail,
 * it quietly becomes part of the title and the track is then searched for under a name
 * nobody has. The owner asked for the check on 2026-10-19, starting with the blank lines.
 *
 * <p>Nothing here changes the file. It says what it found and the person decides, which is
 * the same rule the settings editor follows.
 */
public final class Lint {

    /** How bad a finding is. Only {@link #ERROR} stops a save, and only until it is confirmed. */
    public enum Level {
        /** The line will be searched for as something other than what it looks like. */
        ERROR,
        /** The line works, but probably not the way it reads. */
        WARN,
        /** Worth knowing, costs nothing. */
        NOTE
    }

    /**
     * One finding.
     *
     * @param line the line number as the editor counts them, from 1
     */
    public record Found(int line, Level level, String text, String why) {
    }

    private Lint() {
    }

    /** Everything worth saying about this text, in line order. */
    public static List<Found> check(String text) {
        List<Found> found = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        String[] lines = text == null ? new String[0] : text.split("\n", -1);
        for (int at = 0; at < lines.length; at++) {
            String raw = lines[at].strip();
            int number = at + 1;
            if (raw.isEmpty()) {
                // the last line of a file that ends with a newline is not a blank line
                if (at < lines.length - 1) {
                    found.add(new Found(number, Level.NOTE, "", "blank line"));
                }
                continue;
            }
            if (raw.startsWith("#")) {
                continue;
            }
            int bar = raw.lastIndexOf('|');
            if (bar >= 0) {
                String tail = raw.substring(bar + 1).strip();
                if (Playlists.parseDuration(tail).isEmpty()) {
                    found.add(new Found(number, Level.ERROR, raw,
                            "the length \"" + tail + "\" did not parse, so the whole line "
                                    + "including the bar is searched for as the title"));
                    continue;
                }
                if (raw.substring(0, bar).isBlank()) {
                    found.add(new Found(number, Level.ERROR, raw,
                            "a length with no track in front of it"));
                    continue;
                }
            }
            if (Playlists.parseLine(raw).isEmpty()) {
                found.add(new Found(number, Level.ERROR, raw,
                        "nothing here to search for"));
                continue;
            }
            if (!raw.contains(" - ")) {
                found.add(new Found(number, Level.WARN, raw,
                        "no \"Artist - Title\": the whole line is searched as one title, "
                                + "which finds less"));
            }
            if (bar < 0) {
                found.add(new Found(number, Level.NOTE, raw,
                        "no length: it is searched without a length filter"));
            }
            String key = keyOf(raw);
            Integer first = seen.putIfAbsent(key, number);
            if (first != null) {
                found.add(new Found(number, Level.NOTE, raw,
                        "the same track as line " + first + "; the run searches it once"));
            }
        }
        return List.copyOf(found);
    }

    /** How many of these are bad enough to stop a save. */
    public static long errors(List<Found> found) {
        return found.stream().filter(one -> one.level() == Level.ERROR).count();
    }

    /** Two lines that mean the same track: the title the reader would search for. */
    private static String keyOf(String raw) {
        return Playlists.parseLine(raw)
                .map(track -> track.title().toLowerCase(Locale.ROOT))
                .orElse(raw.toLowerCase(Locale.ROOT));
    }
}
