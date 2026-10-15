package dev.litetook.sockseek.batch;

import dev.litetook.sockseek.config.Settings;
import dev.litetook.sockseek.explain.Candidate;
import dev.litetook.sockseek.explain.Explain;
import dev.litetook.sockseek.index.Failure;
import dev.litetook.sockseek.index.IndexRow;
import dev.litetook.sockseek.index.Located;
import dev.litetook.sockseek.index.SockseekIndex;
import dev.litetook.sockseek.index.Status;
import dev.litetook.sockseek.playlist.Entry;
import dev.litetook.sockseek.playlist.Playlist;
import dev.litetook.sockseek.playlist.Playlists;
import dev.litetook.sockseek.process.ProcessRunner;
import dev.litetook.sockseek.process.Runner;
import dev.litetook.sockseek.process.Sockseek;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * The run itself: for every chosen playlist, search ONLY what is missing, and report.
 *
 * <p>A re-run of the same playlist is the normal workflow - what arrives depends on who is
 * online right now - so the tracks already on disk are not searched at all, not even to be
 * skipped.
 */
public final class Batch {

    private final RunConfig cfg;
    private final Path logsDir;
    private final Path workDir;
    private final Consumer<String> log;
    private final ProcessRunner capture;

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

        Path listFile = workDir.resolve(playlist.name() + ".list.txt");
        Files.createDirectories(workDir);
        Files.writeString(listFile, before.missing().stream().map(Entry::listLine)
                .reduce((a, b) -> a + "\n" + b).orElse("") + "\n", StandardCharsets.UTF_8);
        Sockseek client = cfg.client();
        Path logFile = cfg.verbose() ? verboseLog(playlist.name()) : null;
        List<String> command = client.command(listFile, outDir, logFile, null, Runner.INDEX_NAME);
        if (cfg.dryRun()) {
            log.accept("dry run: " + Runs.commandLine(command));
            return new Result(entries.size(), before.missing().size(), false);
        }
        Files.createDirectories(outDir);
        dev.litetook.sockseek.process.Status status = Runner.runWithRetry(command, cfg.appDir(),
                client.timeoutSeconds(), cfg.retries(), log, Runner.RETRY_PAUSE_SECONDS);
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
        boolean broke = status == dev.litetook.sockseek.process.Status.TIMEOUT
                && arrived.isEmpty();
        if (broke) {
            log.accept("ERROR sockseek was killed by the timeout before anything arrived");
        } else if (status != dev.litetook.sockseek.process.Status.OK && arrived.isEmpty()
                && indexFiles.isEmpty()) {
            log.accept("ERROR sockseek exited with an error and wrote no index: nothing was "
                    + "searched");
            broke = true;
        }
        if (!still.isEmpty() && !broke) {
            report(still, entries, rows, listFile, outDir, client);
        }
        log.accept("run #" + Runs.label(cfg.runNo()) + ": " + arrived.size() + " arrived, "
                + still.size() + " still missing, " + after.have().size() + " of "
                + entries.size() + " in the folder");
        return new Result(entries.size(), still.size(), broke);
    }

    /** One FAILED line per track still missing, with the reason in words. */
    void report(List<Entry> still, List<Entry> entries, List<IndexRow> rows, Path listFile,
                Path outDir, Sockseek client) {
        Map<String, IndexRow> byLine = SockseekIndex.rowsByEntry(entries, rows);
        List<Failure> failures = still.stream()
                .map(entry -> failureOf(entry.original(), byLine.get(entry.original())))
                .toList();
        Map<String, List<Candidate>> found = cfg.explain()
                ? Explain.explainFailures(failures, entries, listFile, client, outDir, log,
                        capture)
                : Map.of();
        OptionalInt minBitrate = Settings.readMinBitrate(cfg.conf() != null ? cfg.conf()
                : cfg.sockseek().getParent().resolve(Settings.SOCKSEEK_CONF));
        for (int i = 0; i < still.size(); i++) {
            Failure failure = failures.get(i);
            if (SockseekIndex.OK_STATES.contains(failure.state().strip())) {
                log.accept("WARN the index says this one arrived, but no file is on disk: "
                        + failure.playlistLine());
            }
            log.accept(SockseekIndex.format(failure));
            if (cfg.explain()) {
                // the search string is what sockseek echoes as "Results for ...", which is the
                // track title - not the playlist line with its " | 4:09" tail
                String key = still.get(i).track().title();
                Optional<List<Candidate>> candidates = found.containsKey(key)
                        ? Optional.of(found.get(key)) : Optional.empty();
                for (String line : Explain.explainFailure(failure, candidates,
                        cfg.options().lengthTol(), minBitrate)) {
                    log.accept(line);
                }
            }
        }
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
