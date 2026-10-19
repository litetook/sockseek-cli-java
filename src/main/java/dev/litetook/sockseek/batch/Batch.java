package dev.litetook.sockseek.batch;

import dev.litetook.sockseek.Pace;
import dev.litetook.sockseek.config.Settings;
import dev.litetook.sockseek.explain.Candidate;
import dev.litetook.sockseek.explain.Explain;
import dev.litetook.sockseek.index.Better;
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
import dev.litetook.sockseek.process.Trace;
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
        Status onDisk = SockseekIndex.playlistStatus(entries, outDir);
        // replace_if_better: every track is asked for again, and what arrives lands in a
        // folder of its own - nothing in the library is touched until a winner is picked
        Path downloadDir = cfg.replaceIfBetter()
                ? workDir.resolve(playlist.name() + ".upgrade") : outDir;
        Status before = cfg.replaceIfBetter()
                ? new Status(List.of(), entries, onDisk.files()) : onDisk;
        String tol = cfg.options().lengthTol() < 0 ? "off"
                : "+-" + cfg.options().lengthTol() + " s";
        log.accept(entries.size() + " tracks, " + onDisk.have().size() + " already here, "
                + onDisk.missing().size() + " missing, length tolerance " + tol);
        if (cfg.replaceIfBetter()) {
            log.accept("replace_if_better: asking for all " + entries.size() + " again; a new "
                    + "copy is kept only if it beats yours, and nothing is deleted otherwise");
        }
        if (cfg.why() != null) {
            why(playlist, entries, before.missing(), outDir);
            return new Result(entries.size(), before.missing().size(), false);
        }
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
        // Uploaders that let us down recently. sockseek forgets them when a run ends -
        // fails-to-ignore lasts one run - so this is the part that outlives it. Ranked
        // last, never banned: one that stalled last night may be the only one holding a
        // track tonight
        List<String> rankLast = cfg.cache()
                ? Failures.staleUploaders(Failures.readUploaders(cacheFile),
                        cfg.badUploaderHours(), LocalDateTime.now())
                : List.of();
        if (!rankLast.isEmpty()) {
            log.accept(rankLast.size() + " uploader(s) ranked last after letting a transfer "
                    + "die within the last " + plainHours(cfg.badUploaderHours()) + " hour(s)");
        }
        Map<String, Integer> wentBad = new LinkedHashMap<>();
        // what this run could refuse a file for. A row remembered under other conditions
        // answers a different question and is not acted on - so turning a filter off takes
        // effect at once, instead of after the skip window runs out
        String conditions = conditions();
        Set<String> skip = cfg.cache()
                ? Failures.skippable(remembered, playlist.name(), cfg.skipNotFoundHours(),
                        LocalDateTime.now(), conditions)
                : Set.of();
        List<Entry> wanted = before.missing().stream()
                .filter(entry -> !skip.contains(entry.original())).toList();
        int skipped = before.missing().size() - wanted.size();
        if (skipped > 0) {
            log.accept("skipping " + skipped + " track(s) nobody had within the last "
                    + plainHours(cfg.skipNotFoundHours()) + " hour(s)"
                    + (conditions.isEmpty() ? "" : " under " + conditions)
                    + "; set cache.skip_absent_h: 0 to search them anyway");
        }
        if (wanted.isEmpty()) {
            log.accept("nothing left to search: every missing track was searched for recently "
                    + "and nobody had it");
            return new Result(entries.size(), before.missing().size(), false);
        }

        Files.createDirectories(workDir);
        Sockseek client = cfg.client();
        // written every run now, not only with --verbose: the auth line and the candidate
        // counts are read back out of it while the run is going. One file per pass, because
        // a pass that appended to the previous one's log would be counted twice
        Trace.Login signedIn = Trace.Login.UNKNOWN;

        // One pass per wanted format, most wanted first, then one with no format at all.
        // sockseek's pref-format is unordered by its own documentation, so this is the only
        // way to say "mp3 first, then flac, then whatever else": ask for them one at a
        // time, with the HARD filter, over whatever the pass before left behind.
        //
        // Each pass may carry its own floor - "mp3@192" - because without one the ladder
        // runs downhill: a 96 kbps mp3 taken in pass 1 means the flac that was sitting there
        // is never asked for. The last pass carries neither format nor floor, so nothing is
        // lost; the ladder only decides what is taken FIRST.
        List<Pass> passes = passesOf(cfg.formatOrder());
        // which pass each track came down to: the ladder is an experiment, and this is its
        // result. A track that only arrives on the last pass is a track nobody had at the
        // quality asked for first, which is worth saying out loud
        Map<String, Integer> arrivedOn = new LinkedHashMap<>();

        Path listFile = workDir.resolve(playlist.name() + ".list.txt");
        Map<String, Events.Event> said = new LinkedHashMap<>();
        // the lines the last pass asked for with no format required. A NoSearchResults from
        // that pass, with no hard condition set, is already the whole answer
        Set<String> wideOpen = new LinkedHashSet<>();
        dev.litetook.sockseek.process.Status status =
                dev.litetook.sockseek.process.Status.OK;
        List<Entry> left = wanted;

        // one wall clock for the PLAYLIST, shared out between its passes: the setting is
        // called attempt_timeout_min, and until 2026-10-19 it was a timeout per pass, so a
        // playlist with four passes could legitimately run four times the number set
        long attemptNanos = (long) (cfg.timeoutMinutes() * 60 * 1_000_000_000L);
        long attemptStart = System.nanoTime();
        int spentSearches = 0;
        for (int pass = 0; pass < passes.size() && !left.isEmpty(); pass++) {
            String format = passes.get(pass).format();
            // the pass number is in the name, not just the format: a ladder may ask for one
            // format twice at different floors (mp3@256 ... mp3@128), and without it the
            // second pass would overwrite the first one's list and events
            String tag = passes.size() == 1 ? ""
                    : "." + (pass + 1) + "-" + (format == null ? "any" : format);
            Path passList = workDir.resolve(playlist.name() + tag + ".list.txt");
            Path eventsFile = workDir.resolve(playlist.name() + tag + ".events.txt");
            Path logFile = verboseLog(playlist.name() + tag);
            // a bare newline, not the platform separator: this is what sockseek has
            // always been handed, and the dry-run line is pinned by a test
            Files.writeString(passList, String.join(NEWLINE,
                    left.stream().map(Entry::listLine).toList()) + NEWLINE,
                    StandardCharsets.UTF_8);
            Files.deleteIfExists(eventsFile);
            listFile = passList;   // the explain step probes whatever the last pass asked
            Options options = cfg.options().withPass(format, passes.get(pass).floor());
            List<String> command = new ArrayList<>(client.command(passList, downloadDir,
                    logFile, options, Runner.INDEX_NAME, eventsFile));
            if (!rankLast.isEmpty()) {
                command.addAll(List.of("--pref-banned-users", String.join(",", rankLast)));
            }
            if (cfg.dryRun()) {
                log.accept("dry run: " + Runs.commandLine(command));
                continue;
            }
            if (passes.size() > 1) {
                log.accept("pass " + (pass + 1) + " of " + passes.size() + ": "
                        + (format == null ? "any format" : format + " only")
                        + (passes.get(pass).floor() > 0
                                ? " at " + passes.get(pass).floor() + "+ kbps" : "")
                        + ", " + left.size() + " track(s)");
            }
            Files.createDirectories(downloadDir);
            List<Entry> asked = left;
            Events.Tail watch = new Events.Tail(eventsFile,
                    event -> record(event, asked, said, playlist.name()));
            // sockseek's own log, for the one thing only it knows: whether the account
            // got in at all
            Trace.Login was = signedIn;
            Trace.Tail trace = new Trace.Tail(logFile, heard -> {
                if (heard.login() == Trace.Login.IN && was != Trace.Login.IN) {
                    log.accept("auth: logged in as " + heard.about());
                }
                if (heard.login() == Trace.Login.OUT) {
                    log.accept("auth: LOGIN FAILED - " + heard.about());
                }
            });
            double leftSeconds = attemptNanos <= 0 ? client.timeoutSeconds()
                    : (attemptNanos - (System.nanoTime() - attemptStart)) / 1e9;
            if (attemptNanos > 0 && leftSeconds <= 0) {
                log.accept("out of time for this playlist after " + (pass) + " pass(es) - "
                        + "attempt_timeout_min is the budget for the whole playlist");
                break;
            }
            long passStart = System.nanoTime();
            status = Runner.runWithRetry(command, cfg.appDir(), leftSeconds,
                    cfg.retries(), log, Runner.RETRY_PAUSE_SECONDS, watch, cfg.stallSeconds(),
                    downloadDir, trace);
            if (trace.login() != Trace.Login.UNKNOWN) {
                signedIn = trace.login();
            }
            trace.wentBad().forEach((user, times) -> wentBad.merge(user, times, Integer::sum));
            int passSearches = Pace.searchesOf(passList, eventsFile);
            spentSearches += passSearches;
            if (status == dev.litetook.sockseek.process.Status.ERROR || status.cutShort()) {
                break;   // a pass that was stopped says nothing about the next one either
            }
            if (format == null) {
                // it ran to the end, and it asked for any format at all
                asked.forEach(entry -> wideOpen.add(entry.original()));
            }
            pauseAfterPass(passSearches, System.nanoTime() - passStart, pass, passes.size());
            // what the folder holds now decides what the next pass asks for
            Status between = SockseekIndex.playlistStatus(entries, downloadDir);
            int thisPass = pass + 1;
            left.stream()
                    .filter(entry -> between.files().containsKey(entry.original()))
                    .forEach(entry -> arrivedOn.putIfAbsent(entry.original(), thisPass));
            left = left.stream()
                    .filter(entry -> !between.files().containsKey(entry.original())).toList();
        }
        if (cfg.dryRun()) {
            return new Result(entries.size(), before.missing().size(), false);
        }
        // what the front end charges the brake with. It used to reconstruct a file name,
        // which stopped matching the moment passes gave those files a tag - and a brake that
        // charges nothing is a brake that is off (measured 2026-10-19)
        try {
            Files.writeString(workDir.resolve(playlist.name() + ".searches.txt"),
                    spentSearches + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException cannotWrite) {
            log.accept("WARN cannot write the search count: " + cannotWrite.getMessage());
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

        List<Path> indexFiles = SockseekIndex.findIndexFiles(downloadDir);
        List<Located> located = SockseekIndex.readWithFolders(indexFiles);
        List<IndexRow> rows = located.stream().map(Located::row).toList();
        if (indexFiles.isEmpty()) {
            log.accept("WARN no " + Runner.INDEX_NAME + " found under " + downloadDir);
        }
        if (cfg.replaceIfBetter()) {
            keepTheBetter(entries, downloadDir, outDir);
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
        if (!reportable.isEmpty() && !broke) {
            report(reportable, entries, rows, said, wideOpen, conditions);
            tally(reportable, said, wideOpen, conditions);
        }
        ladder(arrived, arrivedOn, passes);
        if (cfg.cache()) {
            remember(cacheFile, remembered, playlist.name(), said, wideOpen, conditions);
            rememberUploaders(cacheFile, wentBad);
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
        // against THIS pass, not the whole run: said is run-wide and every pass re-asks the
        // same lines, so from pass 2 on it printed a frozen "18 of 18" - 55 times in the
        // owner's run of 2026-10-18 15:26
        int done = (int) wanted.stream().filter(one -> said.containsKey(one.original())).count();
        long got = wanted.stream().map(one -> said.get(one.original()))
                .filter(one -> one != null && one.succeeded()).count();
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
                  Map<String, Events.Event> said, Set<String> wideOpen, String conditions) {
        if (said.isEmpty()) {
            return;   // no events at all: say nothing rather than forget what was known
        }
        List<Failures.Outcome> after = Failures.after(existing, playlist, said,
                LocalDateTime.now(), wideOpen, conditions);
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

    /**
     * One FAILED line per track still missing, each with what this run can honestly say
     * about it - decided from what the run itself asked for, not from a second search.
     *
     * <p>Until 2026-10-18 this spent one extra search per failure to tell "nobody had it"
     * from "the conditions refused it". It no longer does: when nothing could refuse a file
     * and the last pass asked for any format, the answer is already known. Asking again is
     * now {@code --why}, on the tracks you ask about, when you ask.
     */
    void report(List<Entry> still, List<Entry> entries, List<IndexRow> rows,
                Map<String, Events.Event> said, Set<String> wideOpen, String conditions) {
        Map<String, IndexRow> byLine = SockseekIndex.rowsByEntry(entries, rows);
        for (Entry entry : still) {
            String line = entry.original();
            Failure failure = failureOf(line, byLine.get(line));
            if (SockseekIndex.OK_STATES.contains(failure.state().strip())) {
                log.accept("WARN the index says this one arrived, but no file is on disk: "
                        + line);
            }
            log.accept(SockseekIndex.format(failure));
            Events.Event event = said.get(line);
            if (event == null) {
                // no event for it at all: it never finished, so the run knows nothing
                log.accept("  -> never finished in this run, so nothing is known about it");
                continue;
            }
            String reason = Failures.reasonOf(event, wideOpen.contains(line), conditions);
            log.accept("  -> " + Failures.explainClass(reason, conditions));
        }
    }

    /**
     * Write down which uploaders let a transfer die, so the next run can rank them last.
     *
     * <p>Read back and written whole rather than appended to, because the tracks were just
     * written to the same file and the two must not overwrite one another.
     */
    void rememberUploaders(Path cacheFile, Map<String, Integer> wentBad) {
        if (wentBad.isEmpty()) {
            return;
        }
        Failures.Cache was = Failures.cache(cacheFile);
        List<Failures.Bad> after = Failures.afterUploaders(was.uploaders(), wentBad,
                LocalDateTime.now(), "a transfer from them did not make it");
        if (!Failures.write(cacheFile, new Failures.Cache(was.tracks(), after))) {
            log.accept("WARN cannot write " + cacheFile + ", these uploaders will be tried "
                    + "again as if nothing happened");
            return;
        }
        log.accept(wentBad.size() + " uploader(s) written down as having dropped a transfer; "
                + "they rank last for the next " + plainHours(cfg.badUploaderHours())
                + " hour(s)");
    }

    /**
     * The same failures counted by cause, after they have been listed one by one.
     *
     * <p>A list of 35 lines says what happened to each track and nothing about the run. The
     * owner asked on 2026-10-19 for the other half: whether this was a bad night on the
     * network, a quality bar nobody could meet, or uploaders that would not deliver - which
     * is three different things to do about it.
     */
    void tally(List<Entry> still, Map<String, Events.Event> said, Set<String> wideOpen,
               String conditions) {
        Map<String, Integer> byCause = new LinkedHashMap<>();
        for (Entry entry : still) {
            Events.Event event = said.get(entry.original());
            String cause = event == null ? "never finished in this run, so nothing is known"
                    : shortCause(Failures.reasonOf(event, wideOpen.contains(entry.original()),
                            conditions), conditions);
            byCause.merge(cause, 1, Integer::sum);
        }
        if (byCause.size() <= 1 && still.size() < 2) {
            return;   // one track with one cause is already said above, in full
        }
        log.accept("why " + still.size() + " did not arrive:");
        byCause.entrySet().stream()
                .sorted((one, two) -> Integer.compare(two.getValue(), one.getValue()))
                .forEach(cause -> log.accept(String.format("  %4d  %s", cause.getValue(),
                        cause.getKey())));
    }

    /** The cause in a handful of words, for counting; the full sentence is in Failures. */
    private static String shortCause(String reason, String conditions) {
        if (reason.equals(Failures.CONFIRMED_ABSENT)) {
            return "nobody on the network has it";
        }
        if (reason.equals(Failures.REFUSED_OR_ABSENT)) {
            return "nothing matched under " + conditions + " - absent, or refused by them";
        }
        if (reason.equals(Events.Event.NOT_FOUND)) {
            return "never asked with nothing in the way - the run was cut short";
        }
        return "found, but the upload never came through";
    }

    /**
     * Which rung of the ladder each arrival came down to.
     *
     * <p>Only printed when there is more than one pass, and only about passes that brought
     * something: on a one-pass run it would say what the line above already said.
     */
    void ladder(List<Entry> arrived, Map<String, Integer> arrivedOn, List<Pass> passes) {
        if (passes.size() < 2 || arrived.isEmpty()) {
            return;
        }
        Map<Integer, Integer> perPass = new java.util.TreeMap<>();
        arrived.forEach(entry -> perPass.merge(
                arrivedOn.getOrDefault(entry.original(), passes.size()), 1, Integer::sum));
        if (perPass.size() < 2) {
            return;   // everything came from the same pass; the pass line already said so
        }
        log.accept("where the " + arrived.size() + " that arrived came from:");
        perPass.forEach((pass, many) -> log.accept(String.format("  %4d  %s", many,
                passName(passes.get(Math.min(pass, passes.size()) - 1), pass, passes.size()))));
    }

    private static String passName(Pass pass, int number, int total) {
        String what = pass.format() == null ? "any format" : pass.format() + " only";
        String floor = pass.floor() > 0 ? " at " + pass.floor() + "+ kbps" : "";
        return "pass " + number + " of " + total + ": " + what + floor;
    }

    /**
     * Move in what is better and throw away what is not.
     *
     * <p>Only reached with {@code replace_if_better}. Everything that arrived is in a folder
     * of its own, so the library has not been touched yet: for each track, either there is
     * nothing of ours and the arrival simply moves in, or {@link Better} decides. A track
     * nobody had leaves yours exactly as it was - which is the whole promise of the setting.
     */
    void keepTheBetter(List<Entry> entries, Path downloadDir, Path outDir) {
        Status arrived = SockseekIndex.playlistStatus(entries, downloadDir);
        Status mine = SockseekIndex.playlistStatus(entries, outDir);
        int better = 0;
        int kept = 0;
        for (Entry entry : entries) {
            Path fresh = arrived.files().get(entry.original());
            if (fresh == null) {
                continue;   // nothing came for this one; whatever is on disk stays
            }
            Path existing = mine.files().get(entry.original());
            try {
                // normalised, both of them: the index writes "./Name.mp3", so the path it
                // resolves to is not equal to the plain one even when it is the same file -
                // and the first version of this deleted the copy it had just moved in
                // (measured in mock mode 2026-10-19, before any of it reached a library)
                Path target = outDir.resolve(fresh.getFileName()).normalize();
                if (existing == null) {
                    Files.createDirectories(outDir);
                    Files.move(fresh, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    continue;   // a plain top-up, not an upgrade: nothing to report
                }
                Path old = existing.normalize();
                java.util.OptionalInt seconds = entry.track().length();
                if (!Better.worthReplacing(fresh, existing, seconds)) {
                    log.accept("   = " + entry.original() + " - "
                            + Better.say(fresh, existing, seconds));
                    Files.deleteIfExists(fresh);
                    kept++;
                    continue;
                }
                log.accept("   ^ " + entry.original() + " - "
                        + Better.say(fresh, existing, seconds));
                Files.move(fresh, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                // only now, and only when the old one is a different file that is still there
                if (!old.equals(target) && Files.isRegularFile(old)) {
                    Files.deleteIfExists(old);
                }
                better++;
            } catch (IOException cannotMove) {
                log.accept("WARN " + entry.original() + ": could not put the new copy in "
                        + "place (" + cannotMove.getMessage() + "); yours is untouched");
            }
        }
        log.accept("replace_if_better: " + better + " upgraded, " + kept + " of yours kept");
    }

    /** One pass of the ladder: the format it demands, and the floor it demands, if any. */
    record Pass(String format, int floor) {
    }

    /**
     * The ladder as passes: {@code mp3@192, flac} becomes mp3 at 192+, then flac at any
     * bitrate, then one last pass with no format and no floor.
     *
     * <p>A floor is written with an {@code @}: it belongs to that pass and nothing else. An
     * entry that is only a name has no floor, which is the shape every config had before
     * 2026-10-19 and still means the same thing.
     */
    static List<Pass> passesOf(List<String> order) {
        List<Pass> passes = new ArrayList<>();
        for (String raw : order) {
            String item = raw == null ? "" : raw.strip();
            if (item.isEmpty()) {
                continue;
            }
            int at = item.indexOf('@');
            if (at < 0) {
                passes.add(new Pass(item, -1));
                continue;
            }
            String name = item.substring(0, at).strip();
            int floor;
            try {
                floor = Integer.parseInt(item.substring(at + 1).strip());
            } catch (NumberFormatException notANumber) {
                floor = -1;   // "mp3@fast" is a typo, not a floor; the format still stands
            }
            if (!name.isEmpty()) {
                passes.add(new Pass(name, floor));
            }
        }
        // No catch-all pass at the end any more. It used to take anything at all "so that
        // nothing is lost", which quietly undid the floors: a 128 kbps mp3 refused by pass
        // one arrived in pass four regardless. The owner settled it on 2026-10-20 - "міняю
        // свідомо кількість на якість" - so a format below its floor is simply not taken.
        // An empty list still means one pass that takes anything: that is "no preference",
        // not "refuse everything".
        if (passes.isEmpty()) {
            passes.add(new Pass(null, -1));
        }
        return List.copyOf(passes);
    }

    /**
     * Hold the next pass back the way the front end holds the next playlist back.
     *
     * <p>Every pass is a new sockseek with a fresh budget of its own, and the server counts
     * per account: four passes over eighteen tracks put 72 searches inside 196 s on a limit
     * of 34 per 220 s (measured from the owner's run of 2026-10-18 15:26). Without this the
     * ladder is a way to get the account banned.
     */
    void pauseAfterPass(int searches, long elapsedNanos, int pass, int passCount) {
        if (searches <= 0 || cfg.searchesPerWindow() <= 0 || pass >= passCount - 1) {
            return;   // nothing spent, no limit set, or that was the last pass
        }
        long earnedNanos = (long) ((double) searches / cfg.searchesPerWindow()
                * cfg.windowSeconds() * 1_000_000_000L);
        long waitNanos = earnedNanos - elapsedNanos;
        if (waitNanos <= 0) {
            return;
        }
        log.accept("pausing " + (waitNanos / 1_000_000_000L) + " s before the next pass so "
                + "that all of them together stay under " + cfg.searchesPerWindow()
                + " searches per " + cfg.windowSeconds() + " s");
        try {
            Thread.sleep(waitNanos / 1_000_000L);
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * What could have refused a file this run, as {@code len=45,min-bitrate=256}; empty when
     * nothing could, which is the state in which "nothing came back" means "nobody has it".
     *
     * <p>The format passes are not in here on purpose: the last pass asks for any format, so
     * by the end of a run no format has refused anything.
     */
    String conditions() {
        // the merged file is what sockseek was actually given; when the caller took the
        // config over (--no-config in the tests) there is nothing of ours in force
        return Failures.conditions(cfg.options().lengthTol(),
                cfg.conf() == null ? List.of() : Settings.hardConditions(cfg.conf()));
    }

    /** {@code --why}: search again with every condition off, for the tracks asked about. */
    void why(Playlist playlist, List<Entry> entries, List<Entry> missing, Path outDir) {
        String wanted = cfg.why().strip().toLowerCase(java.util.Locale.ROOT);
        List<Entry> asked = missing.stream()
                .filter(entry -> wanted.isEmpty()
                        || entry.original().toLowerCase(java.util.Locale.ROOT).contains(wanted))
                .toList();
        if (asked.isEmpty()) {
            log.accept(missing.isEmpty() ? "nothing is missing in this playlist"
                    : "no missing track of this playlist matches '" + cfg.why() + "'");
            return;
        }
        List<IndexRow> rows = SockseekIndex.readWithFolders(
                SockseekIndex.findIndexFiles(outDir)).stream().map(Located::row).toList();
        Map<String, IndexRow> byLine = SockseekIndex.rowsByEntry(entries, rows);
        List<Failure> failures = asked.stream()
                .map(entry -> failureOf(entry.original(), byLine.get(entry.original())))
                .toList();
        Path listFile = workDir.resolve(playlist.name() + ".list.txt");
        try {
            Files.createDirectories(workDir);
        } catch (IOException cannotMake) {
            log.accept("WARN cannot write to " + workDir + ": " + cannotMake.getMessage());
            return;
        }
        Map<String, List<Candidate>> found = Explain.explainFailures(failures, entries,
                listFile, cfg.client(), outDir, log, capture);
        OptionalInt minBitrate = cfg.conf() == null ? OptionalInt.empty()
                : Settings.readMinBitrate(cfg.conf());
        for (int i = 0; i < asked.size(); i++) {
            Failure failure = failures.get(i);
            log.accept(SockseekIndex.format(failure));
            String key = asked.get(i).track().title();
            Optional<List<Candidate>> candidates = found.containsKey(key)
                    ? Optional.of(found.get(key)) : Optional.empty();
            for (String line : Explain.explainFailure(failure, candidates,
                    cfg.options().lengthTol(), minBitrate)) {
                log.accept(line);
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
