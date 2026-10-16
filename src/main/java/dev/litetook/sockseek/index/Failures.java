package dev.litetook.sockseek.index;

import dev.litetook.sockseek.process.Events;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tracks nobody on the network had, and when that was.
 *
 * <p>What this is for: searching costs the one thing a big run is short of. sockseek is allowed
 * 34 searches per 220 seconds, and a playlist of 107 tracks spends most of its night waiting
 * for that window rather than downloading. Asking again today for a track that nobody shared
 * yesterday is the cheapest thing to stop doing.
 *
 * <p>What it is NOT for: remembering failure in general. Soulseek has no catalogue - a file
 * exists while somebody who has it is online. A track absent on 6 October arrived on the 7th as
 * a 893 kbps flac, which is the whole reason this tool re-runs playlists at all. So only one
 * class of failure is ever written here, {@code NoSearchResults} - nobody had it - and only for
 * as many days as {@code skip_not_found_days} says, one by default. "Found, but the upload
 * never came through" is never written: those are the ones that most often arrive next time.
 *
 * <p>Why not sockseek's own {@code --skip-not-found}: it remembers for ever rather than for a
 * day, and the row it writes cannot say which kind of failure it was - the index puts
 * {@code reason=9} both on a track nobody had and on a track that was found and rejected by
 * the conditions (measured 2026-10-17). The class of failure here comes from the word sockseek
 * itself reports as a track ends, through {@code --on-complete}.
 *
 * <p>The file is a tab-separated table, one line per track, in the tool's own folder:
 * <pre>{@code 2026-10-17<TAB>NoSearchResults<TAB>liked-techno-hard<TAB>Artist - Title | 4:09}</pre>
 * Tabs are the separator because a playlist line may hold anything else, commas included.
 */
public final class Failures {

    /** Where it lives: beside the config, not in work/, which is safe to delete at any time. */
    public static final String FILE_NAME = "not-found.tsv";

    private Failures() {
    }

    /** One track nobody had, on one day. */
    public record Miss(LocalDate when, String reason, String playlist, String line) {

        String toLine() {
            return when + "\t" + reason + "\t" + playlist + "\t" + line;
        }
    }

    /** Everything remembered so far; an unreadable or absent file simply remembers nothing. */
    public static List<Miss> read(Path file) {
        List<Miss> found = new ArrayList<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException nothingYet) {
            return List.of();
        }
        for (String raw : lines) {
            String[] fields = raw.split("\t", 4);
            if (fields.length != 4 || fields[3].isBlank()) {
                continue;   // a half-written or hand-edited line is skipped, never fatal
            }
            try {
                found.add(new Miss(LocalDate.parse(fields[0].strip()), fields[1].strip(),
                        fields[2].strip(), fields[3]));
            } catch (DateTimeParseException notADate) {
                continue;
            }
        }
        return List.copyOf(found);
    }

    /** Write the table back. A cache that cannot be written is not worth failing a run for. */
    public static boolean write(Path file, List<Miss> all) {
        try {
            Files.writeString(file, all.stream().map(Miss::toLine)
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
     * <p>{@code days} of 0 means the cache is off and nothing is skipped. One day means "not
     * again today": a miss from yesterday is already old enough to be worth another look,
     * because who is online changes by the hour.
     */
    public static Set<String> skippable(List<Miss> all, String playlist, int days,
                                        LocalDate today) {
        Set<String> lines = new LinkedHashSet<>();
        if (days <= 0) {
            return lines;
        }
        for (Miss miss : all) {
            if (!miss.playlist().equals(playlist)
                    || !miss.reason().equals(Events.Event.NOT_FOUND)) {
                continue;
            }
            if (!miss.when().plusDays(days).isAfter(today)) {
                continue;   // old enough to be worth asking again
            }
            lines.add(miss.line());
        }
        return lines;
    }

    /**
     * What to remember after a run of one playlist.
     *
     * <p>Every line this run has an opinion about replaces whatever was remembered for it: a
     * track that arrived is forgotten, a track found-but-not-delivered is forgotten, and only
     * a track nobody had is written with today's date. Other playlists are left alone.
     */
    public static List<Miss> after(List<Miss> existing, String playlist,
                                   Map<String, Events.Event> byLine, LocalDate today) {
        Map<String, Miss> kept = new LinkedHashMap<>();
        for (Miss miss : existing) {
            if (miss.playlist().equals(playlist) && byLine.containsKey(miss.line())) {
                continue;   // this run has something newer to say about it
            }
            kept.put(miss.playlist() + "\t" + miss.line(), miss);
        }
        for (Map.Entry<String, Events.Event> said : byLine.entrySet()) {
            if (said.getValue().notOnNetwork()) {
                kept.put(playlist + "\t" + said.getKey(),
                        new Miss(today, Events.Event.NOT_FOUND, playlist, said.getKey()));
            }
        }
        return List.copyOf(kept.values());
    }
}
