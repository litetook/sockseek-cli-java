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
 * <pre>{@code 2026-10-17T12:24:31<TAB>AllDownloadsFailed<TAB>4<TAB>techno-hard<TAB>Artist - Title}</pre>
 * Tabs, because a playlist line may hold anything else, commas included.
 */
public final class Failures {

    /** Where it lives: beside the config, not in work/, which is safe to delete at any time. */
    public static final String FILE_NAME = "outcomes.tsv";

    /**
     * The one class that may ever stop a track being searched for again.
     *
     * <p>Not sockseek's word but our own, and the difference is the point. sockseek says
     * {@code NoSearchResults} both when nobody on the network had the track and when
     * several people did and every copy was refused by the length or the bitrate filter -
     * measured 2026-10-18: a file sitting in the mock folder, rejected on length, reported
     * exactly what a track nobody shares reports. On a real run that same code covered 104
     * of 135 failures.
     *
     * <p>Which of the two it was is decided <b>while the run is going</b>, from what the run
     * itself was asking for. The last pass over a playlist asks for any format, so when no
     * hard condition is set, a {@code NoSearchResults} from that pass can only mean nobody
     * had it - no second search can add anything. Until 2026-10-18 a whole extra pass of
     * searches was spent re-asking at the end of every run, and the owner was right about
     * what that was worth: on the live run of 15:26 it confirmed the inference for 18
     * failures out of 18 and changed nothing.
     */
    public static final String CONFIRMED_ABSENT = "NotOnNetwork";

    /**
     * Nothing came back, but a hard condition was in force, so "nobody had it" is NOT what
     * this says. The conditions that were in force are written down beside it, and the cache
     * never skips this class: the copy it refused may be the only one there is.
     */
    public static final String REFUSED_OR_ABSENT = "NoMatchUnderConditions";

    /**
     * The conditions string written beside an outcome: what could have refused a file.
     *
     * <p>One place, because the run that writes it and the preview that reads it have to
     * spell it the same way or the preview promises a skip the run will not make.
     */
    public static String conditions(int lengthTol, java.util.List<String> hardKeys) {
        java.util.List<String> on = new ArrayList<>();
        if (lengthTol >= 0) {
            on.add("len=" + lengthTol);
        }
        on.addAll(hardKeys);
        return String.join(",", on);
    }

    /** How many runs in a row make a failure worth mentioning in the report. */
    public static final int NAG_AFTER = 3;

    /** How many of them the report names; the rest are counted and left in the file. */
    public static final int NAG_SHOWN = 10;

    private Failures() {
    }

    /**
     * How one track ended, when that was, how many runs in a row it has ended that way, and
     * the hard conditions that were in force when it did.
     *
     * @param conditions what could have refused a file that run, as
     *                   {@code len=45,min-bitrate=256}, empty when nothing could. A row is
     *                   only ever acted on by a run asking under the SAME conditions:
     *                   turning a filter off makes yesterday's "nothing matched" worth
     *                   nothing, and the track is searched for again
     */
    public record Outcome(LocalDateTime when, String reason, int runs, String conditions,
                          String playlist, String line) {

        String toLine() {
            return when + "\t" + reason + "\t" + runs + "\t" + conditions + "\t" + playlist
                    + "\t" + line;
        }

        /** Confirmed absent by a search with every condition off - see CONFIRMED_ABSENT. */
        public boolean notOnNetwork() {
            return reason.equals(CONFIRMED_ABSENT);
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
            String[] fields = raw.split("\t", 6);
            // five fields is the shape this file had before 2026-10-18, with no conditions
            // column. Those rows are read with no conditions, which is the safe way round:
            // they are all in the old classes, and no old class is ever skipped anyway
            boolean old = fields.length == 5;
            if (fields.length < 5 || fields[fields.length - 1].isBlank()) {
                continue;   // a half-written or hand-edited line is skipped, never fatal
            }
            try {
                found.add(new Outcome(when(fields[0].strip()), fields[1].strip(),
                        Integer.parseInt(fields[2].strip()), old ? "" : fields[3].strip(),
                        fields[old ? 3 : 4].strip(), fields[old ? 4 : 5]));
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
                                        LocalDateTime now, String conditions) {
        Set<String> lines = new LinkedHashSet<>();
        if (hours <= 0) {
            return lines;
        }
        long minutes = Math.round(hours * 60);
        for (Outcome seen : all) {
            if (!seen.playlist().equals(playlist) || !seen.notOnNetwork()) {
                continue;
            }
            if (!seen.conditions().equals(conditions)) {
                // asked under different conditions, so it answers a different question.
                // This is what makes turning a filter off take effect at once instead of
                // after the window runs out
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
        return after(existing, playlist, byLine, now, Set.of(), "");
    }

    /**
     * The same, told which lines the run asked for with nothing in the way, and under which
     * conditions it asked.
     *
     * <p>A {@code NoSearchResults} for a line in {@code askedWideOpen} while
     * {@code conditions} is empty is written down as {@link #CONFIRMED_ABSENT} - the only
     * class the cache will ever hold back. With a condition in force it becomes
     * {@link #REFUSED_OR_ABSENT} instead, which is counted and never skipped, because the
     * file it refused might be the only copy there is. A line the run never reached that way
     * - a pass cut short, a track held back - keeps sockseek's own word and decides nothing.
     */
    public static List<Outcome> after(List<Outcome> existing, String playlist,
                                      Map<String, Events.Event> byLine, LocalDateTime now,
                                      Set<String> askedWideOpen, String conditions) {
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
            String reason = reasonOf(event, askedWideOpen.contains(said.getKey()),
                    conditions);
            Outcome was = before.get(said.getKey());
            int runs = was != null && was.reason().equals(reason) ? was.runs() + 1 : 1;
            kept.put(playlist + "\t" + said.getKey(),
                    new Outcome(now, reason, runs, conditions, playlist, said.getKey()));
        }
        return List.copyOf(kept.values());
    }

    /**
     * What this run may honestly claim about a failed track.
     *
     * <p>Only {@code NoSearchResults} is ever reinterpreted, because it is the only word
     * sockseek uses for two different things. Everything else - the upload that died above
     * all - is its own answer already.
     */
    public static String reasonOf(Events.Event event, boolean askedWideOpen,
                                  String conditions) {
        if (!event.reason().equals(Events.Event.NOT_FOUND)) {
            return event.reason();
        }
        if (!conditions.isEmpty()) {
            return REFUSED_OR_ABSENT;
        }
        // nothing could have refused a file, and the last pass asked for any format at all:
        // there is nothing left for a second search to find out
        return askedWideOpen ? CONFIRMED_ABSENT : event.reason();
    }

    /** What the line says about itself in a report: the class, and what it was asked under. */
    public static String explainClass(String reason, String conditions) {
        if (reason.equals(CONFIRMED_ABSENT)) {
            return "nobody on the network has it (searched with nothing in the way)";
        }
        if (reason.equals(REFUSED_OR_ABSENT)) {
            return "nothing matched under " + conditions + " - either nobody has it, or what "
                    + "they have was refused by those conditions. --why "
                    + "\"<track>\" searches again with them off";
        }
        if (reason.equals(Events.Event.NOT_FOUND)) {
            return "nothing came back, and this run was cut short before it could ask with "
                    + "nothing in the way";
        }
        return "found, but the download did not come through";
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
