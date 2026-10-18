package dev.litetook.sockseek.batch;

import dev.litetook.sockseek.config.Settings;
import dev.litetook.sockseek.explain.Candidate;
import dev.litetook.sockseek.explain.Explain;
import dev.litetook.sockseek.index.Failure;
import dev.litetook.sockseek.index.Failures;
import dev.litetook.sockseek.index.IndexRow;
import dev.litetook.sockseek.index.Located;
import dev.litetook.sockseek.index.SockseekIndex;
import dev.litetook.sockseek.index.Status;
import dev.litetook.sockseek.playlist.Entry;
import dev.litetook.sockseek.playlist.Playlist;
import dev.litetook.sockseek.playlist.Playlists;
import dev.litetook.sockseek.process.Events;
import dev.litetook.sockseek.process.ProcessRunner;
import dev.litetook.sockseek.process.Runner;
import dev.litetook.sockseek.process.Screen;
import dev.litetook.sockseek.process.Sockseek;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import dev.litetook.sockseek.process.Options;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The run itself: for every chosen playlist, search ONLY what is missing, and report.
 *
 * <p>A re-run of the same playlist is the normal workflow - what arrives depends on who is
 * online right now - so the tracks already on disk are not searched at all, not even to be
 * skipped.
 */
public final class Batch {

    /** What a sockseek list file has always ended its lines with. */
    private static final String NEWLINE = String.valueOf((char) 10);


    private final RunConfig cfg;
    private final Path logsDir;
    private final Path workDir;
    private final Consumer<String> log;
    private final ProcessRunner capture;
    private final Screen screen = new Screen();

    public Batch(RunConfig cfg, Path logsDir, Path workDir, Consumer<String> log,
                 ProcessRunner capture) {
        this.cfg = cfg;
        this.logsDir = logsDir;
        this.workDir = workDir;
        this.log = log;
        this.capture = capture == null ? ProcessRunner.system() : capture;
    }

    /** The playlist files to process, or an exit code when there is nothing to do. */
    Optional<Integer> playlists(List<Path> into) {
        try {
            if (!Files.isDirectory(cfg.playlistsDir())) {
                Files.createDirectories(cfg.playlistsDir());
                log.accept("created " + cfg.playlistsDir() + ", put playlist .txt files there");
                return Optional.of(2);
            }
            into.addAll(Playlists.find(cfg.playlistsDir()));
        } catch (IOException cannotRead) {
            log.accept("ERROR: cannot read playlists folder: " + cannotRead.getMessage());
            return Optional.of(2);
        }
        if (cfg.only() != null && !cfg.only().isBlank()) {
            Path named = cfg.playlistsDir().resolve(cfg.only() + ".txt");
            List<Path> chosen = Files.isRegularFile(named) ? List.of(named)
                    : into.stream().filter(file -> stem(file).equals(cfg.only())).toList();
            into.clear();
            into.addAll(chosen);
            if (into.isEmpty()) {
                log.accept("ERROR: no playlist named '" + cfg.only() + "' in "
                        + cfg.playlistsDir());
                return Optional.of(2);
            }
        }
        if (into.isEmpty()) {
            log.accept("ERROR: no playlist .txt files in " + cfg.playlistsDir());
            return Optional.of(2);
        }
        return Optional.empty();
    }

    /** 0 the run worked, 1 this tool failed, 2 a setup problem. */
    public int run() {
        List<Path> files = new ArrayList<>();
        Optional<Integer> problem = playlists(files);
        if (problem.isPresent()) {
            return problem.get();
        }
        List<Result> results = new ArrayList<>();
        List<String> broken = new ArrayList<>();
        for (Path file : files) {
            log.accept("=== " + stem(file));
            Result result;
            try {
                result = one(file);
            } catch (IOException | UncheckedIOException cannotRun) {
                // one broken playlist must not stop the others
                log.accept("ERROR: " + stem(file) + ": " + cannotRun.getMessage());
                result = new Result(0, 0, true);
            }
            results.add(result);
            if (result.broke()) {
                broken.add(stem(file));
            }
        }
        int total = results.stream().mapToInt(Result::total).sum();
        int missing = results.stream().mapToInt(Result::missing).sum();
        String tag = "run #" + Runs.label(cfg.runNo());
        if (cfg.dryRun() || cfg.options().preview()) {
            log.accept("all done, nothing downloaded (" + tag + ")");
        } else {
            log.accept((total - missing) + " of " + total + " track(s) in the folders, "
                    + missing + " still missing (" + tag + ")");
        }
        if (!broken.isEmpty()) {
            log.accept("ERROR this run broke on: " + String.join(", ", broken));
            return 1;
        }
        return 0;
    }

    /** One playlist, start to finish. */
    Result one(Path file) throws IOException {
        Playlist playlist = Playlist.fromFile(file);
        for (String warning : playlist.warnings()) {
            log.accept("WARN " + warning);
        }
        if (playlist.entries().isEmpty()) {
            log.accept("no tracks, skipped");
            return new Result(0, 0, false);
        }
        List<Entry> entries = playlist.entries();
        Path outDir = cfg.outputDir().resolve(playlist.name());
        Status before = SockseekIndex.playlistStatus(entries, outDir);
        String tol = cfg.options().lengthTol() < 0 ? "off"
                : "+-" + cfg.options().lengthTol() + " s";
        log.accept(entries.size() + " tracks, " + before.have().size() + " already here, "
                + before.missing().size() + " missing, length tolerance " + tol);
        if (before.missing().isEmpty()) {
            log.accept("nothing to search, all " + entries.size()
                    + " track(s) are already in " + outDir);
            return new Result(entries.size(), 0, false);
        }

        // A track nobody had yesterday is not worth one of today's 34 searches per 220 s. The
        // skip happens HERE, in the open, rather than through sockseek's --skip-not-found,
        // which remembers for ever and cannot tell "nobody had it" from "found and rejected".
        Path cacheFile = cfg.appDir().resolve(Failures.FILE_NAME);
        // --no-cache makes the file not exist for the length of this run: nothing is read
        // from it and nothing is written back, so a clean sweep leaves no trace either way
        List<Failures.Outcome> remembered = cfg.cache() ? Failures.read(cacheFile) : List.of();
        Set<String> skip = cfg.cache()
                ? Failures.skippable(remembered, playlist.name(), cfg.skipNotFoundHours(),
                        LocalDateTime.now())
                : Set.of();
        List<Entry> wanted = before.missing().stream()
                .filter(entry -> !skip.contains(entry.original())).toList();
        int skipped = before.missing().size() - wanted.size();
        if (skipped > 0) {
            log.accept("skipping " + skipped + " track(s) nobody had within the last "
                    + plainHours(cfg.skipNotFoundHours()) + " hour(s); set "
                    + "skip_not_found_hours: 0 to search them anyway");
        }
        if (wanted.isEmpty()) {
            log.accept("nothing left to search: every missing track was searched for recently "
                    + "and nobody had it");
            return new Result(entries.size(), before.missing().size(), false);
        }

        Files.createDirectories(workDir);
        Sockseek client = cfg.client();
        Path logFile = cfg.verbose() ? verboseLog(playlist.name()) : null;

        // One pass per wanted format, most wanted first, then one with no format at all.
        // sockseek's pref-format is unordered by its own documentation, so this is the only
        // way to say "mp3 first, then flac, then whatever else": ask for them one at a
        // time, with the HARD filter, over whatever the pass before left behind.
        List<String> passes = new ArrayList<>(cfg.formatOrder());
        passes.add(null);   // and finally, anything

        Path listFile = workDir.resolve(playlist.name() + ".list.txt");
        Map<String, Events.Event> said = new LinkedHashMap<>();
        dev.litetook.sockseek.process.Status status =
                dev.litetook.sockseek.process.Status.OK;
        List<Entry> left = wanted;

        for (int pass = 0; pass < passes.size() && !left.isEmpty(); pass++) {
            String format = passes.get(pass);
            String tag = passes.size() == 1 ? "" : "." + (format == null ? "any" : format);
            Path passList = workDir.resolve(playlist.name() + tag + ".list.txt");
            Path eventsFile = workDir.resolve(playlist.name() + tag + ".events.txt");
            // a bare newline, not the platform separator: this is what sockseek has
            // always been handed, and the dry-run line is pinned by a test
            Files.writeString(passList, String.join(NEWLINE,
                    left.stream().map(Entry::listLine).toList()) + NEWLINE,
                    StandardCharsets.UTF_8);
            Files.deleteIfExists(eventsFile);
            listFile = passList;   // the explain step probes whatever the last pass asked
            Options options = cfg.options().withFormat(format);
            List<String> command = client.command(passList, outDir, logFile, options,
                    Runner.INDEX_NAME, eventsFile);
            if (cfg.dryRun()) {
                log.accept("dry run: " + Runs.commandLine(command));
                continue;
            }
            if (passes.size() > 1) {
                log.accept("pass " + (pass + 1) + " of " + passes.size() + ": "
                        + (format == null ? "any format" : format + " only") + ", "
                        + left.size() + " track(s)");
            }
            Files.createDirectories(outDir);
            List<Entry> asked = left;
            Events.Tail watch = new Events.Tail(eventsFile,
                    event -> record(event, asked, said, playlist.name()));
            status = Runner.runWithRetry(command, cfg.appDir(), client.timeoutSeconds(),
                    cfg.retries(), log, Runner.RETRY_PAUSE_SECONDS, watch, cfg.stallSeconds(),
                    outDir);
            if (status == dev.litetook.sockseek.process.Status.ERROR || status.cutShort()) {
                break;   // a pass that was stopped says nothing about the next one either
            }
            // what the folder holds now decides what the next pass asks for
            Status between = SockseekIndex.playlistStatus(entries, outDir);
            left = left.stream()
                    .filter(entry -> !between.files().containsKey(entry.original())).toList();
        }
        if (cfg.dryRun()) {
            return new Result(entries.size(), before.missing().size(), false);
        }
        if (cfg.options().preview()) {
            log.accept(status == dev.litetook.sockseek.process.Status.OK ? "preview done"
                    : "FAILED");
            return new Result(entries.size(), before.missing().size(),
                    status != dev.litetook.sockseek.process.Status.OK);
        }
        if (status == dev.litetook.sockseek.process.Status.ERROR) {
            // the process never ran, so nothing is known about the network: calling a track
            // missing here would be the tool blaming Soulseek for its own failure
            log.accept("ERROR sockseek could not be started, nothing was searched");
            return new Result(entries.size(), before.missing().size(), true);
        }

        List<Path> indexFiles = SockseekIndex.findIndexFiles(outDir);
        List<Located> located = SockseekIndex.readWithFolders(indexFiles);
        List<IndexRow> rows = located.stream().map(Located::row).toList();
        if (indexFiles.isEmpty()) {
            log.accept("WARN no " + Runner.INDEX_NAME + " found under " + outDir);
        }
        Status after = SockseekIndex.playlistStatus(entries, outDir);
        List<Entry> arrived = before.missing().stream()
                .filter(entry -> after.files().containsKey(entry.original())).toList();
        List<Entry> still = before.missing().stream()
                .filter(entry -> !after.files().containsKey(entry.original())).toList();
        for (Entry entry : arrived) {
            log.accept("OK      " + entry.original());
        }
        // A non-zero exit is the EVERYDAY case: sockseek returns one whenever a single track
        // fails (run #0028 brought 6 of 10 and still exited 1). What marks a broken run is the
        // process being killed, or exiting non-zero with no index and nothing to show.
        boolean broke = status.cutShort() && arrived.isEmpty();
        if (broke) {
            log.accept("ERROR sockseek was stopped before anything arrived ("
                    + (status == dev.litetook.sockseek.process.Status.STALLED
                            ? "it had gone quiet" : "it ran out of time") + ")");
        } else if (status != dev.litetook.sockseek.process.Status.OK && arrived.isEmpty()
                && indexFiles.isEmpty()) {
            log.accept("ERROR sockseek exited with an error and wrote no index: nothing was "
                    + "searched");
            broke = true;
        }
        // the ones held back by the cache were never searched, so there is nothing to explain
        // about them and the index has nothing to say either; they are counted, not listed
        List<Entry> reportable = still.stream()
                .filter(entry -> !skip.contains(entry.original())).toList();
        Set<String> absent = !reportable.isEmpty() && !broke
                ? report(reportable, entries, rows, listFile, outDir, client)
                : Set.of();
        // after the report, not before it: what the report learns is the only thing that
        // may turn a failure into "do not search for this again"
        if (cfg.cache()) {
            remember(cacheFile, remembered, playlist.name(), said, absent);
        }
        // The question the owner kept asking of a cut-short run: how many never got a turn?
        // sockseek leaves state=0 on a row still in the queue, and eleven of them after the
        // ten-minute ceiling of run #0056 read as eleven failures. They are not failures and
        // they are not news about Soulseek - they were never searched, and saying the number
        // in one line is the difference between "it did not find them" and "it did not reach
        // them".
        Map<String, IndexRow> byEntry = SockseekIndex.rowsByEntry(entries, rows);
        // reportable, not still: a track the cache held back was never meant to be searched
        // this run, and the state=0 its row may still carry is a leftover of an earlier one
        long queued = reportable.stream()
                .map(entry -> byEntry.get(entry.original()))
                .filter(row -> row != null && row.state().strip().equals("0"))
                .count();
        if (queued > 0) {
            log.accept(queued + " track(s) never got their turn before the run ended - they "
                    + "were not searched, so nothing is known about them");
        }
        log.accept("run #" + Runs.label(cfg.runNo()) + ": " + arrived.size() + " arrived, "
                + still.size() + " still missing, " + after.have().size() + " of "
                + entries.size() + " in the folder");
        return new Result(entries.size(), still.size(), broke);
    }

    /** 2 rather than 2.0, so the line reads like a person wrote it. */
    private static String plainHours(double hours) {
        return hours == Math.rint(hours) ? Long.toString((long) hours) : Double.toString(hours);
    }

    /**
     * One finished track, as sockseek reported it the moment it ended.
     *
     * <p>{@code row} is the line number in the list file this run wrote, so the track it
     * belongs to is known exactly rather than matched by name. The line printed here is the
     * only live count there is: sockseek's own display knows this playlist, not the run.
     */
    void record(Events.Event event, List<Entry> wanted, Map<String, Events.Event> said,
                String playlistName) {
        if (event.row() > wanted.size()) {
            return;   // a row we never wrote: nothing to attach it to, and nothing to claim
        }
        String line = wanted.get(event.row() - 1).original();
        said.put(line, event);
        int done = said.size();
        long got = said.values().stream().filter(Events.Event::succeeded).count();
        // the window title is the only place that is reliably visible while sockseek draws
        Screen.title(playlistName + "  " + done + "/" + wanted.size() + "  " + got
                + " downloaded");
        // every finished track is a line a second on a big playlist, which buries the
        // display it sits beside; the last one is always said so the final count is final
        if (!screen.worthSaying(done == wanted.size())) {
            return;
        }
        Screen.freshLine();
        log.accept(">> " + done + " of " + wanted.size() + " done, " + got + " downloaded"
                + (event.succeeded() ? "" : "  (" + event.reason() + ": " + line + ")"));
    }

    /** Write back what this playlist now knows about tracks nobody had. */
    void remember(Path cacheFile, List<Failures.Outcome> existing, String playlist,
                  Map<String, Events.Event> said, Set<String> confirmedAbsent) {
        if (said.isEmpty()) {
            return;   // no events at all: say nothing rather than forget what was known
        }
        List<Failures.Outcome> after = Failures.after(existing, playlist, said,
                LocalDateTime.now(), confirmedAbsent);
        if (!Failures.write(cacheFile, after)) {
            log.accept("WARN cannot write " + cacheFile + ", what happened to these tracks "
                    + "will not be remembered");
            return;
        }
        // the one thing a run log cannot show you, because each log only knows its own run
        List<Failures.Outcome> stuck = Failures.stuck(after, playlist);
        if (stuck.isEmpty()) {
            return;
        }
        log.accept(stuck.size() + " track(s) have ended the same way " + Failures.NAG_AFTER
                + "+ runs in a row - all of them are in " + cacheFile.getFileName());
        // The worst few only. On a set this size a few hundred tracks end up here, and a
        // report that prints all of them every run buries the live progress it sits next to;
        // the file has the rest, sorted by nothing in particular but easy to open.
        stuck.stream()
                .sorted((a, b) -> Integer.compare(b.runs(), a.runs()))
                .limit(Failures.NAG_SHOWN)
                .forEach(seen -> log.accept("  x" + seen.runs() + "  " + seen.reason() + "  "
                        + seen.line()));
        if (stuck.size() > Failures.NAG_SHOWN) {
            log.accept("  ... and " + (stuck.size() - Failures.NAG_SHOWN) + " more");
        }
    }

    /** One FAILED line per track still missing, with the reason in words. */
    Set<String> report(List<Entry> still, List<Entry> entries, List<IndexRow> rows,
                       Path listFile, Path outDir, Sockseek client) {
        Map<String, IndexRow> byLine = SockseekIndex.rowsByEntry(entries, rows);
        List<Failure> failures = still.stream()
                .map(entry -> failureOf(entry.original(), byLine.get(entry.original())))
                .toList();
        // a budget, not a switch: one extra search per failed track, and a real run had 135
        // failures. Twenty is enough to see which kind a playlist is suffering from
        int budget = Math.min(cfg.explainTracks(), failures.size());
        List<Failure> asked = failures.subList(0, Math.max(0, budget));
        Map<String, List<Candidate>> found = asked.isEmpty() ? Map.of()
                : Explain.explainFailures(asked, entries, listFile, client, outDir, log,
                        capture);
        if (budget < failures.size()) {
            log.accept((failures.size() - budget) + " more failure(s) were not looked into - "
                    + "raise explain_tracks, or --explain for all of them");
        }
        // the merged file is what sockseek was actually given; when the caller took the
        // config over (--no-config in the tests) there is no floor to report
        OptionalInt minBitrate = cfg.conf() == null ? OptionalInt.empty()
                : Settings.readMinBitrate(cfg.conf());
        Set<String> absent = new LinkedHashSet<>();
        for (int i = 0; i < still.size(); i++) {
            Failure failure = failures.get(i);
            if (SockseekIndex.OK_STATES.contains(failure.state().strip())) {
                log.accept("WARN the index says this one arrived, but no file is on disk: "
                        + failure.playlistLine());
            }
            log.accept(SockseekIndex.format(failure));
            if (cfg.explainTracks() > 0) {
                // the search string is what sockseek echoes as "Results for ...", which is the
                // track title - not the playlist line with its " | 4:09" tail
                String key = still.get(i).track().title();
                Optional<List<Candidate>> candidates = found.containsKey(key)
                        ? Optional.of(found.get(key)) : Optional.empty();
                // searched again with every condition off and still nothing: this one is
                // really not out there, and it is the only kind the cache may act on
                if (candidates.isPresent() && candidates.get().isEmpty()) {
                    absent.add(failure.playlistLine());
                }
                for (String line : Explain.explainFailure(failure, candidates,
                        cfg.options().lengthTol(), minBitrate)) {
                    log.accept(line);
                }
            }
        }
        return absent;
    }

    private static Failure failureOf(String line, IndexRow row) {
        return row == null ? new Failure(line, "", "", "", "")
                : new Failure(line, row.title(), row.length(), row.state(), row.failureReason());
    }

    Path verboseLog(String name) {
        return logsDir.resolve("sockseek_" + Runs.label(cfg.runNo()) + "_" + name + ".log");
    }

    private static String stem(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }
}
