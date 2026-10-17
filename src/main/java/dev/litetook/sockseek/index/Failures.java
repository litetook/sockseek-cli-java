package dev.litetook.sockseek.index;

import dev.litetook.sockseek.process.Events;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What happened to a track last time, and how many times in a row.
 *
 * <p>Two different jobs, and they must not be confused:
 *
 * <ol>
 *   <li><b>Skipping.</b> A track nobody on the network had is not worth one of today's 34
 *       searches per 220 s. Only that one class is ever skipped, and only for
 *       {@code skip_not_found_hours}.
 *   <li><b>Counting.</b> Everything else is remembered but never skipped, purely so a track
 *       that has been failing to download for five runs running can say so. Without this the
 *       fact was unrecoverable: it lives in the text of a run log, and in {@code _index.csv},
 *       which sockseek rewrites in place on the next run - so the history was lost every time.
 * </ol>
 *
 * <p>Why failure must not generally be remembered as "do not try again": Soulseek has no
 * catalogue. A file exists while somebody who has it is online. A track absent on 6 October
 * arrived on the 7th as a 893 kbps flac, which is the whole reason this tool re-runs playlists
 * at all. "Found, but the upload never came through" is the most likely of all to arrive next
 * time, so it is counted and never skipped.
 *
 * <p>The classes are sockseek's own words, as it reports them when a track ends - measured in
 * mock mode on 2026-10-17:
 *
 * <table border="1">
 *   <caption>what the word means</caption>
 *   <tr><th>word</th><th>index row</th><th>meaning</th><th>skipped?</th></tr>
 *   <tr><td>{@code NoSearchResults}</td><td>state=2 reason=9</td><td>nobody had it</td>
 *       <td>yes, for skip_not_found_hours</td></tr>
 *   <tr><td>{@code AllDownloadsFailed}</td><td>state=2 reason=4</td>
 *       <td>found, the upload died</td><td>never</td></tr>
 * </table>
 *
 * <p>Why not read this off the index instead: {@code reason=9} cannot be split into "nobody
 * had it" and "found and rejected by the conditions" - run #0004 found two 320 kbps copies of
 * a track whose row said 9, rejected on length. The word can.
 *
 * <p>The file is tab-separated, in the tool's own folder, one line per track:
 * <pre>{@code 2026-10-17T12:24:31<TAB>AllDownloadsFailed<TAB>4<TAB>liked-techno<TAB>Artist - Title}</pre>
 * Tabs, because a playlist line may hold anything else, commas included.
 */
public final class Failures {

    /** Where it lives: beside the config, not in work/, which is safe to delete at any time. */
    public static final String FILE_NAME = "outcomes.tsv";

    /** How many runs in a row make a failure worth mentioning in the report. */
    public static final int NAG_AFTER = 3;

    /** How many of them the report names; the rest are counted and left in the file. */
    public static final int NAG_SHOWN = 10;

    private Failures() {
    }

    /** How one track ended, when that was, and how many runs in a row it has ended that way. */
    public record Outcome(LocalDateTime when, String reason, int runs, String playlist,
                          String line) {

        String toLine() {
            return when + "\t" + reason + "\t" + runs + "\t" + playlist + "\t" + line;
        }

        /** The one class that is worth not searching for again today. */
        public boolean notOnNetwork() {
            return reason.equals(Events.Event.NOT_FOUND);
        }
    }

    /** Everything remembered so far; an unreadable or absent file simply remembers nothing. */
    public static List<Outcome> read(Path file) {
        List<Outcome> found = new ArrayList<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException nothingYet) {
            return List.of();
        }
        for (String raw : lines) {
            String[] fields = raw.split("\t", 5);
            if (fields.length != 5 || fields[4].isBlank()) {
                continue;   // a half-written or hand-edited line is skipped, never fatal
            }
            try {
                found.add(new Outcome(when(fields[0].strip()), fields[1].strip(),
                        Integer.parseInt(fields[2].strip()), fields[3].strip(), fields[4]));
            } catch (DateTimeParseException | NumberFormatException notThisLine) {
                continue;
            }
        }
        return List.copyOf(found);
    }

    /**
     * The stamp, tolerating the date-only form this file used until 2026-10-17.
     *
     * <p>A day's worth of real records already existed when the unit changed, and throwing
     * them away would have made the tool search two hundred hopeless lines again for nothing.
     * A date with no time counts as that day's midnight, which is the safe reading: it can
     * only make a row look older than it is, never younger.
     */
    private static LocalDateTime when(String text) {
        return text.length() <= 10 ? LocalDate.parse(text).atStartOfDay()
                : LocalDateTime.parse(text);
    }

    /** Write the table back. A record that cannot be written is not worth failing a run for. */
    public static boolean write(Path file, List<Outcome> all) {
        try {
            Files.writeString(file, all.stream().map(Outcome::toLine)
                    .reduce((a, b) -> a + "\n" + b).map(text -> text + "\n").orElse(""),
                    StandardCharsets.UTF_8);
            return true;
        } catch (IOException | RuntimeException cannotWrite) {
            return false;
        }
    }

    /**
     * The playlist lines worth not searching for again today.
     *
     * <p>{@code hours} of 0 means the skipping is off and nothing is held back - the counting
     * goes on either way.
     *
     * <p>Hours, not days, since 2026-10-17, and two of them by default. It was a day until the
     * owner noticed the obvious thing a day cannot express: "деякі треки просто з'явились у
     * пошуку коли я в інший час додаток запустив". Who is sharing changes through the day, so
     * a morning miss must not silence a track until midnight - and an hour of rest is still
     * enough to stop a re-run five minutes later from spending the search budget on the same
     * hopeless lines.
     */
    public static Set<String> skippable(List<Outcome> all, String playlist, double hours,
                                        LocalDateTime now) {
        Set<String> lines = new LinkedHashSet<>();
        if (hours <= 0) {
            return lines;
        }
        long minutes = Math.round(hours * 60);
        for (Outcome seen : all) {
            if (!seen.playlist().equals(playlist) || !seen.notOnNetwork()) {
                continue;
            }
            if (!seen.when().plusMinutes(minutes).isAfter(now)) {
                continue;   // old enough to be worth asking again
            }
            lines.add(seen.line());
        }
        return lines;
    }

    /**
     * What to remember after a run of one playlist.
     *
     * <p>A track that arrived is forgotten outright - it is on disk now, and the folder is the
     * only record worth having. A track that failed again in the same way keeps its count and
     * adds one; a track that failed in a NEW way starts counting from one, because a different
     * cause is a different story. Other playlists are left untouched.
     */
    public static List<Outcome> after(List<Outcome> existing, String playlist,
                                      Map<String, Events.Event> byLine, LocalDateTime now) {
        Map<String, Outcome> kept = new LinkedHashMap<>();
        Map<String, Outcome> before = new LinkedHashMap<>();
        for (Outcome seen : existing) {
            if (seen.playlist().equals(playlist) && byLine.containsKey(seen.line())) {
                before.put(seen.line(), seen);
                continue;   // this run has something newer to say about it
            }
            kept.put(seen.playlist() + "\t" + seen.line(), seen);
        }
        for (Map.Entry<String, Events.Event> said : byLine.entrySet()) {
            Events.Event event = said.getValue();
            if (!event.failed()) {
                continue;   // it is here now; the folder says so better than any file of ours
            }
            Outcome was = before.get(said.getKey());
            int runs = was != null && was.reason().equals(event.reason()) ? was.runs() + 1 : 1;
            kept.put(playlist + "\t" + said.getKey(),
                    new Outcome(now, event.reason(), runs, playlist, said.getKey()));
        }
        return List.copyOf(kept.values());
    }

    /**
     * Tracks of this playlist that have been failing the same way for {@link #NAG_AFTER} runs.
     *
     * <p>Not an error and not a reason to stop trying - just the thing a person cannot work
     * out for themselves, because every run log only shows its own run.
     */
    public static List<Outcome> stuck(List<Outcome> all, String playlist) {
        return all.stream()
                .filter(seen -> seen.playlist().equals(playlist) && seen.runs() >= NAG_AFTER)
                .toList();
    }
}
