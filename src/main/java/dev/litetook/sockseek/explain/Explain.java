package dev.litetook.sockseek.explain;

import dev.litetook.sockseek.config.Settings;
import dev.litetook.sockseek.index.Failure;
import dev.litetook.sockseek.playlist.Entry;
import dev.litetook.sockseek.process.Options;
import dev.litetook.sockseek.process.ProcessRunner;
import dev.litetook.sockseek.process.Sockseek;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Why a track did not arrive.
 *
 * <p>After a run, the tracks still missing are searched once more with every condition off.
 * That costs one search per failure and it is the difference between "nobody shares this" and
 * "our own conditions rejected it" - a distinction the raw log hides: {@code reason=9} reads as
 * "no search results" while it really means "nothing passed the conditions".
 */
public final class Explain {

    private static final Pattern HEAD =
            Pattern.compile("^Results for (?<title>.+) \\((?<want>\\d+)s\\):\\s*$");
    private static final Pattern ROW = Pattern.compile(
            "^\\[(?<length>\\d+)s/(?<bitrate>[\\d?]+)kbps\\s*/(?<size>[\\d.?]+)MB\\]\\s*"
                    + "(?<path>.+)$");

    private Explain() {
    }

    /**
     * Group {@code --print results} output by the title it was searched for. A title with no
     * candidates maps to an empty list, which is the whole point.
     */
    public static Map<String, List<Candidate>> parsePreview(String text) {
        Map<String, List<Candidate>> found = new LinkedHashMap<>();
        String current = null;
        for (String raw : text.lines().toList()) {
            String line = raw.strip();
            Matcher head = HEAD.matcher(line);
            if (head.matches()) {
                current = head.group("title").strip();
                found.computeIfAbsent(current, title -> new ArrayList<>());
                continue;
            }
            if (current == null) {
                continue;
            }
            Matcher row = ROW.matcher(line);
            if (row.matches()) {
                String bitrate = row.group("bitrate");
                found.get(current).add(new Candidate(
                        OptionalInt.of(Integer.parseInt(row.group("length"))),
                        bitrate.chars().allMatch(Character::isDigit)
                                ? OptionalInt.of(Integer.parseInt(bitrate)) : OptionalInt.empty(),
                        row.group("path").strip()));
            }
        }
        return found;
    }

    /**
     * Why this track did not arrive, and what to do about it, as indented log lines.
     *
     * @param candidates empty Optional when the search did not run for this line at all, which
     *                   is a different claim from "nothing was found"
     */
    public static List<String> explainFailure(Failure failure, Optional<List<Candidate>> candidates,
                                              int lengthTol, OptionalInt minBitrate) {
        if (candidates.isEmpty()) {
            return List.of("  -> not checked (the explain search did not run for this line)");
        }
        OptionalInt want = parseInt(failure.indexLength());
        List<Candidate> found = candidates.get();
        if (found.isEmpty()) {
            // reason 4 means the run itself found candidates and tried one, so "nothing on the
            // network" would contradict the index
            if (failure.failureReason().strip().equals("4")) {
                return List.of("  -> the extra search came back empty, but the run itself had "
                        + "found a candidate: this is a search that did not answer in time, not "
                        + "an absence");
            }
            return List.of("  -> nothing on the network: 0 candidates even with every condition "
                    + "off");
        }
        Candidate best = found.stream()
                .min(Comparator.<Candidate>comparingInt(one -> gap(one, want))
                        .thenComparing(one -> -one.bitrate().orElse(0)))
                .orElseThrow();
        String bitrate = best.bitrate().isPresent() ? best.bitrate().getAsInt() + "kbps"
                : "bitrate unknown";
        List<String> lines = new ArrayList<>();
        lines.add("  -> IS on the network: " + found.size() + " candidate(s), closest "
                + best.length().orElse(0) + "s/" + bitrate);
        if (want.isPresent() && best.length().isPresent() && lengthTol >= 0
                && gap(best, want) > lengthTol) {
            int difference = best.length().getAsInt() - want.getAsInt();
            lines.add("     rejected by length: playlist says " + want.getAsInt() + "s, diff "
                    + (difference >= 0 ? "+" : "") + difference + "s");
            lines.add("     to take it: run.cmd --only <playlist> --length-tol "
                    + (gap(best, want) + 1));
            return lines;
        }
        List<Candidate> within = found.stream()
                .filter(one -> lengthTol < 0 || want.isEmpty() || one.length().isEmpty()
                        || gap(one, want) <= lengthTol)
                .toList();
        if (minBitrate.isPresent() && !within.isEmpty() && within.stream().allMatch(
                one -> one.bitrate().isPresent()
                        && one.bitrate().getAsInt() < minBitrate.getAsInt())) {
            int worst = within.stream().mapToInt(one -> one.bitrate().orElse(0)).max().orElse(0);
            lines.add("     rejected by quality: best is " + worst + "kbps, below min-bitrate "
                    + minBitrate.getAsInt() + " in " + Settings.SOCKSEEK_CONF);
            return lines;
        }
        lines.add("     length and quality are fine, so the uploader did not deliver - try "
                + "again later, availability changes with who is online");
        return lines;
    }

    private static int gap(Candidate candidate, OptionalInt want) {
        return candidate.length().isPresent() && want.isPresent()
                ? Math.abs(candidate.length().getAsInt() - want.getAsInt()) : 0;
    }

    private static OptionalInt parseInt(String text) {
        try {
            return OptionalInt.of(Integer.parseInt(text.strip()));
        } catch (NumberFormatException notANumber) {
            return OptionalInt.empty();
        }
    }

    /**
     * One extra sockseek run over the failed tracks with no conditions at all. Downloads
     * nothing. Gives back the parsed candidates, empty when the step could not run.
     */
    public static Map<String, List<Candidate>> explainFailures(
            List<Failure> failures, List<Entry> entries, Path listFile, Sockseek sockseek,
            Path outDir, Consumer<String> log, ProcessRunner runner) {
        Set<String> lines = new LinkedHashSet<>(failures.stream()
                .map(Failure::playlistLine)
                .filter(line -> !line.isEmpty())
                .toList());
        List<Entry> wanted = entries.stream()
                .filter(entry -> lines.contains(entry.original()))
                .toList();
        if (wanted.isEmpty()) {
            return Map.of();
        }
        log.accept("explaining " + wanted.size() + " failure(s): " + wanted.size()
                + " extra search(es), nothing is downloaded");
        Path probe = listFile.resolveSibling(listFile.getFileName().toString()
                .replace(".list.txt", ".explain.list.txt"));
        try {
            Files.writeString(probe, wanted.stream().map(Entry::listLine)
                    .reduce((a, b) -> a + "\n" + b).orElse("") + "\n", StandardCharsets.UTF_8);
        } catch (IOException cannotWrite) {
            log.accept("WARN explain step did not run: " + cannotWrite.getMessage());
            return Map.of();
        }
        // every condition off, and one source is enough: nothing is downloaded here
        Options options = new Options(-1, true, 1);
        List<String> command = sockseek.command(probe, outDir, null, options,
                dev.litetook.sockseek.process.Runner.INDEX_NAME);
        try {
            ProcessRunner.Output output = runner.run(command);
            // Every line that was ASKED about gets an entry, even when the answer is
            // nothing. An absent key and an empty list used to look the same to the caller,
            // and they are opposites: one means "we never looked", the other means "we
            // looked with every condition off and the network really does not have it" -
            // which is the only thing the failure cache is allowed to act on.
            Map<String, List<Candidate>> asked = new LinkedHashMap<>();
            for (Entry entry : wanted) {
                asked.put(entry.track().title(), List.of());
            }
            asked.putAll(parsePreview(output.both()));
            return Map.copyOf(asked);
        } catch (RuntimeException cannotRun) {
            log.accept("WARN explain step did not run: " + cannotRun.getMessage());
            return Map.of();
        }
    }
}
