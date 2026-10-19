package dev.litetook.sockseek.batch;

import dev.litetook.sockseek.config.Config;
import dev.litetook.sockseek.config.Knob;
import dev.litetook.sockseek.config.Settings;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What this run is about to do, in one block, before it starts.
 *
 * <p>Asked for on 2026-10-19: "коротко параметри скачування". The {@code key = value} list the
 * engine still prints above it is correct and useless for answering "so what will it actually
 * do"; this says the same settings as consequences.
 *
 * <h2>Two rules it lives by</h2>
 *
 * <p><b>Every number comes from the settings, never from here.</b> Values are read from
 * {@link Config} and from the merged sockseek conf that is handed to sockseek - the same file
 * the run uses. A first draft of this block was typed by hand and was already wrong about one
 * line of ten within the hour; hence the rule, and the test that changes a value and expects
 * the line to change with it.
 *
 * <p><b>Nothing is invented.</b> A key that is not set produces no line, or says plainly that
 * sockseek decides it - never the default out of sockseek's help text, which would be this
 * tool guessing about another program's next version. A key nobody taught us about is printed
 * raw at the end rather than quietly dropped. The words for the keys we do know live in
 * {@link Knob}, which the settings page reads too, so the two cannot drift apart.
 */
public final class Brief {

    /** The width the labels line up in. */
    private static final int LABEL = 15;

    /** How wide a line may get before it is wrapped. */
    private static final int WIDTH = 92;

    /** How many playlists are named before the rest are counted instead. */
    private static final int SHOWN = 8;

    private Brief() {
    }

    /**
     * The block, ready to print.
     *
     * @param picked playlist name to how many of its tracks are missing, in run order
     * @param sockseek the merged conf as sockseek will read it
     */
    public static List<String> lines(Map<String, Integer> picked, Config config,
                                     Map<String, String> sockseek, List<String> formatOrder,
                                     Path musicDir, Path logsDir) {
        Map<String, String> theirs = new LinkedHashMap<>(sockseek);
        Settings.CREDENTIAL_KEYS.forEach(theirs::remove);
        Set<String> used = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        out.add("what this run will do");
        out.add("");

        int missing = picked.values().stream().mapToInt(Integer::intValue).sum();
        List<String> input = new ArrayList<>();
        input.add(count(picked.size(), "playlist") + ", " + count(missing, "track")
                + " missing on disk");
        if (!picked.isEmpty()) {
            List<String> named = picked.entrySet().stream()
                    .map(one -> one.getKey() + " " + one.getValue()).toList();
            input.add(named.size() <= SHOWN ? String.join(", ", named)
                    : String.join(", ", named.subList(0, SHOWN)) + " and "
                            + (named.size() - SHOWN) + " more");
        }
        section(out, "input", "what is going to be searched for", input);

        section(out, "auth", "Soulseek sign-in, as sockseek itself reports it",
                List.of("read from sockseek's own log as soon as the first search goes out"));

        List<Batch.Pass> passes = Batch.passesOf(formatOrder);
        List<String> ladder = new ArrayList<>();
        for (int at = 0; at < passes.size(); at++) {
            Batch.Pass pass = passes.get(at);
            ladder.add("  " + (at + 1) + "  "
                    + (pass.format() == null ? "any format" : pass.format())
                    + (pass.floor() > 0 ? " at " + pass.floor() + "+ kbps" : ""));
        }
        section(out, "format ladder", passes.size() > 1
                ? "passes over the missing tracks, best first; one pass = one search round each"
                : "one pass over the missing tracks", ladder);

        List<String> hard = block(theirs, used, "refuse");
        int lengthTol = config.integer("max_length_diff_s").orElse(-1);
        hard.add(lengthTol < 0 ? "length: not checked against the playlist"
                : "length: at most " + lengthTol + " s away from the playlist");
        if (!theirs.containsKey("strict-conditions") && hard.size() > 1) {
            hard.add("not strict: a candidate that declares nothing is still accepted");
        }
        section(out, "hard filters",
                "conditions a candidate must satisfy, or it is not taken at all", hard);

        section(out, "ranking", "preferences; they order candidates, they never refuse one",
                block(theirs, used, "prefer"));

        section(out, "source pick", "how the uploader for one download is chosen",
                block(theirs, used, "pick_source"));

        int tries = config.integer("attempts_per_pass").orElse(0);
        section(out, "retries",
                "download tries for one track inside one pass; each try is the next candidate "
                        + "that fits this pass",
                tries <= 0 ? List.of() : List.of(tries + " per track per pass"
                        + (passes.size() > 1 ? " -> at most " + tries * passes.size()
                                + " over the " + passes.size() + " passes" : "")));

        section(out, "source bans", "when an uploader is set aside",
                block(theirs, used, "drop_source"));

        List<String> timeouts = new ArrayList<>(block(theirs, used, "stall"));
        double stall = config.decimal("stall_timeout_s").orElse(0);
        timeouts.add(stall > 0
                ? "a playlist with nothing finished and nothing on disk for " + plain(stall)
                        + " s -> stopped"
                : "nothing is stopped for going quiet (stall_timeout_s is off)");
        double attempt = config.decimal("attempt_timeout_min").orElse(0);
        timeouts.add(attempt > 0
                ? "a playlist running longer than " + plain(attempt) + " min -> stopped"
                : "no limit on how long one playlist may run");
        section(out, "timeouts", "what is killed, and when", timeouts);

        List<String> pace = new ArrayList<>();
        int perWindow = config.integer("searches_per_window").orElse(0);
        int window = config.integer("window_s").orElse(0);
        if (perWindow > 0 && window > 0) {
            pace.add(perWindow + " searches every " + window + " s, shared by all playlists");
            long searches = (long) missing * Math.max(1, passes.size());
            pace.add("this run asks for about " + searches + " of them -> roughly "
                    + Math.round(searches / (double) perWindow * window / 60.0)
                    + " min of waiting on the limiter alone");
        } else {
            pace.add("no pause between playlists - the limiter is off");
        }
        List<String> ownLimit = block(theirs, used, "limit");
        if (ownLimit.isEmpty()) {
            pace.add("sockseek's own searches-per-time: not set, so sockseek decides it");
        } else {
            pace.addAll(ownLimit);
        }
        section(out, "rate limit", "search budget shared by all playlists at once", pace);

        boolean on = config.integer("enabled").orElse(1) != 0;
        double hours = config.decimal("skip_absent_h").orElse(0);
        section(out, "cache", "what is not searched for again", List.of(
                !on ? "off - every track is searched for again, nothing is written down"
                        : hours > 0 ? "a track confirmed missing within the last "
                                + plain(hours) + " h is skipped"
                        : "on, but nothing is skipped for being confirmed missing"));

        section(out, "paths", "where things land",
                List.of("music  " + musicDir, "logs   " + logsDir));

        section(out, "also passed",
                "handed to sockseek as it is; this tool has nothing to say about these",
                theirs.entrySet().stream().filter(one -> !used.contains(one.getKey()))
                        .map(one -> one.getKey() + " = " + one.getValue()).toList());
        out.add("");
        return List.copyOf(out);
    }

    /** Every key of one sockseek block that the file actually sets, said in words. */
    private static List<String> block(Map<String, String> theirs, Set<String> used,
                                      String name) {
        List<String> said = new ArrayList<>();
        for (Knob knob : Knob.in(name)) {
            String value = theirs.get(knob.key());
            if (value == null) {
                continue;
            }
            used.add(knob.key());
            knob.say(value).ifPresent(said::add);
        }
        return said;
    }

    /** One labelled block, or nothing at all when it has nothing to say. */
    private static void section(List<String> out, String label, String explanation,
                                List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        boolean first = true;
        for (String line : wrap(explanation)) {
            out.add("  " + pad(first ? label : "") + line);
            first = false;
        }
        for (String value : values) {
            boolean head = true;
            for (String line : wrap(value)) {
                out.add("  " + pad("") + (head ? line : "  " + line));
                head = false;
            }
        }
    }

    /** Plain word wrapping, so no line runs off the side of a terminal. */
    private static List<String> wrap(String text) {
        int room = WIDTH - LABEL - 2;
        int indent = 0;
        while (indent < text.length() && text.charAt(indent) == ' ') {
            indent++;
        }
        String lead = " ".repeat(indent);
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder(lead);
        for (String word : text.strip().split(" ")) {
            if (line.length() > indent && line.length() + 1 + word.length() > room) {
                lines.add(line.toString());
                line.setLength(0);
                line.append(lead);
            }
            line.append(line.length() == indent ? "" : " ").append(word);
        }
        if (line.length() > indent || lines.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static String pad(String label) {
        return label.length() >= LABEL ? label + " "
                : label + " ".repeat(LABEL - label.length());
    }

    /** 2 rather than 2.0, so a whole number reads like one. */
    private static String plain(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    private static String count(int many, String thing) {
        return many + " " + thing + (many == 1 ? "" : "s");
    }
}
