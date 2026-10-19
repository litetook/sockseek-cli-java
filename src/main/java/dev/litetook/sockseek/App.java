package dev.litetook.sockseek;

import dev.litetook.sockseek.cli.Cli;
import dev.litetook.sockseek.config.Config;
import dev.litetook.sockseek.config.Settings;
import dev.litetook.sockseek.deps.Downloader;
import dev.litetook.sockseek.deps.Tools;
import dev.litetook.sockseek.importing.ImportResult;
import dev.litetook.sockseek.importing.Imported;
import dev.litetook.sockseek.importing.Imports;
import dev.litetook.sockseek.index.Failures;
import dev.litetook.sockseek.index.SockseekIndex;
import dev.litetook.sockseek.index.Status;
import dev.litetook.sockseek.links.LinkReader;
import dev.litetook.sockseek.links.Linked;
import dev.litetook.sockseek.links.Links;
import dev.litetook.sockseek.playlist.Entry;
import dev.litetook.sockseek.playlist.Playlist;
import dev.litetook.sockseek.playlist.Playlists;
import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The one command of this tool.
 *
 * <p>Asks where the list comes from - a .txt file you already have, a Spotify link or a
 * SoundCloud link - then shows every playlist with how much of it is already on disk, asks
 * which one to work on, shows exactly which tracks are missing, and downloads only those after
 * an explicit yes.
 *
 * <p>Every line it prints is pinned by a test, and the whole of it by a recording that the
 * end-to-end check compares against - so the wording below is not decoration, it is the
 * interface, and changing a line is a change somebody has to agree to.
 */
public final class App {

    /** How this tool is started, for the "now run" hints. */
    static final String LAUNCHER =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                    ? "run.cmd" : "./run.sh";

    /**
     * What {@code --help} prints. It names {@link #LAUNCHER} rather than a fixed command,
       * because the same jar is started by {@code run.cmd} on Windows and {@code ./run.sh}
       * everywhere else, and a help text that names the wrong one is worse than none.
     */
    static final String HELP = """
            The one command of this tool.

                %s

            Asks where the list comes from - a .txt file you already have, a Spotify link or a
            SoundCloud link - then shows every playlist with how much of it is already on disk, asks which
            one to work on, shows exactly which tracks are missing, and downloads only those after an
            explicit yes. A link is saved as a .txt first, so it joins the same flow. Re-running
            the same playlist later is the normal workflow: what arrives depends on who is online right
            now (proven 2026-10-07: a track absent on the 6th arrived on the 7th as flac 893 kbps).

            Options of this front end:
                --only <name>     skip the menu and take that playlist (file stem, no .txt)
                --yes             skip the confirmation (for a scheduled run)
                --all             take every playlist without asking which; with --yes this
                                  is the unattended run - no question at all
                --import <what>   turn an exported list (m3u/m3u8/csv/tsv/txt) or a playlist link
                                  (Spotify, SoundCloud) into a playlist file and exit
                --as <name>       name for the imported playlist (default: its name at the source)
                --overwrite       let --import replace a playlist that already exists
                --cookies-from-browser <browser>
                                  read a private SoundCloud set with the browser's own login
                                  (chrome, firefox, edge, brave, ...) - you log in there, not here
                --version         print the version and exit
            Anything else is passed straight through to the engine, e.g. --length-tol 60, --verbose,
            --explain, --dry-run.

            Everything you see on screen is also written to logs/session_<date>.log, one file per
            start of this command.

            A tool it needs but cannot find (sockseek, and later yt-dlp) is offered for download at the
            version pinned in dependencies.yaml.""";

    /**
     * The playlist option 4 adds to, and the folder its music lands in.
     *
     * <p>A playlist like any other: it is searched, cached and reported the same way, and it
     * grows every time a single track is asked for. The owner asked for it on 2026-10-18 -
     * "просто качає у папку unsorted нову".
     */
    public static final String UNSORTED = "unsorted";

    /** Where the list comes from. The first question of an interactive run. */
    public enum Source {
        TXT("1", "txt", "download a playlist from a .txt file"),
        SPOTIFY("2", "Spotify", "save a .txt from a Spotify link, then download it"),
        SOUNDCLOUD("3", "SoundCloud", "save a .txt from a SoundCloud link, then download it"),
        /** One track, from either service, straight into the unsorted playlist. */
        TRACK("4", "Spotify or SoundCloud",
                "one track from a link, into " + UNSORTED);

        private final String key;
        private final String label;
        private final String description;

        Source(String key, String label, String description) {
            this.key = key;
            this.label = label;
            this.description = description;
        }

        public String key() {
            return key;
        }

        /** The service as the person reads it: "Spotify", not "spotify". */
        public String label() {
            return label;
        }

        public String description() {
            return description;
        }

        /** The menu answer this is, by number ("2") or by name ("spotify"). */
        static Optional<Source> of(String answer) {
            String wanted = strip(answer).toLowerCase(Locale.ROOT);
            return List.of(values()).stream()
                    .filter(source -> wanted.equals(source.key)
                            || wanted.equals(source.name().toLowerCase(Locale.ROOT)))
                    .findFirst();
        }
    }

    /** A question to the person. Empty means input ended - a closed pipe, Ctrl+Z, Ctrl+C. */
    @FunctionalInterface
    public interface Ask {
        Optional<String> ask(String prompt);

        /**
         * The same question, with the typing hidden - for the password and nothing else.
         *
         * <p>The default is to ask it in the open: a pipe and a test have no console to hide
         * anything from, and refusing to work there would mean no test could cover the one
         * flow that writes the login file.
         */
        default Optional<String> secret(String prompt) {
            return ask(prompt);
        }
    }

    /** The engine, which is {@link Cli}. Injected so a test can watch what it was handed. */
    @FunctionalInterface
    public interface Engine {
        int run(List<String> argv);
    }

    /**
     * Everything the front end talks to that a test wants to replace.
     *
     * <p>They travel together rather than as four arguments so the signature of {@link #run}
     * stays short, and so a test replaces exactly the pieces it means to.
     */
    public record Wiring(Path appDir, Engine engine, LinkReader reader, Downloader downloader) {

        public static Wiring standard(Path appDir) {
            return new Wiring(appDir, argv -> Cli.main(argv, appDir, System.err),
                    new LinkReader(), Downloader.system());
        }
    }

    /** One playlist as the menu shows it. */
    public record Info(String name, Path path, int total, Status status, List<String> warnings,
                       boolean unreadable) {

        public Info {
            warnings = List.copyOf(warnings);
        }

        /** The ordinary case: a playlist that was read. */
        public Info(String name, Path path, int total, Status status, List<String> warnings) {
            this(name, path, total, status, warnings, false);
        }

        public List<Entry> missing() {
            return status.missing();
        }

        public int have() {
            return status.have().size();
        }
    }

    /** The front end's own flags; {@code extra} is everything the engine gets untouched. */
    record Front(String only, boolean yes, boolean all, String importSource, String cookiesFrom,
                 String importName, boolean overwrite, boolean version, boolean help,
                 Path playlists, Path output, Path config, List<String> extra) {
    }

    /** The outcome of writing a playlist from a link: an exit code and the file, if any. */
    record Written(int code, Path file) {
    }

    /**
     * An answer to a question that offers a way back.
     *
     * <p>Three outcomes, not two: an answer, "b" to step back to the question before, or the
     * end of it (Enter, a closed pipe) which quits. A plain Optional cannot say which of the
     * last two happened, and the difference is the whole point of the back step.
     */
    record Link(String url, boolean back) {
    }

    /** The same three outcomes for the playlist table. An empty list with back=false quits. */
    record Choice(List<Info> playlists, boolean back) {
    }

    private App() {
    }

    public static void main(String[] args) {
        // Windows consoles are not UTF-8 by default, and playlists carry names like
        // "Aurora Beltran" with an accent and "Doner Kebab" with an umlaut: say it explicitly
        PrintStream console = new PrintStream(new FileOutputStream(FileDescriptor.out), true,
                StandardCharsets.UTF_8);
        Path appDir = Path.of(".").toAbsolutePath().normalize();
        // One file per start of the command, holding the whole conversation - the menu, the
        // questions and every line the engine printed. The run logs (run_NNNN_...) hold one
        // run each and say nothing about what was asked before it; the owner asked on
        // 2026-10-16 for the screen itself to be kept.
        //
        // What it does NOT hold is sockseek's own display: that child inherits the real console
        // handles (Runner.inheritIO), so it writes past this stream entirely. Its lines go to
        // logs/sockseek_<run>_<name>.log instead, with --verbose.
        PrintStream out = Session.onlyAsking(args) ? console
                : Session.tee(console, Settings.folder(appDir, "logs"));
        // the engine prints through a System.out of its own, so that one is pointed at the
        // same file rather than being taught about it. System.err is left alone: the parity
        // harness reads the two streams apart.
        System.setOut(out);
        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        Ask console_ask = new Ask() {
            @Override
            public Optional<String> ask(String prompt) {
                out.print(prompt);
                out.flush();
                return readLine(in);
            }

            @Override
            public Optional<String> secret(String prompt) {
                // System.console() is null when the output is piped - the gates do that -
                // and then there is nothing to hide the typing from anyway
                java.io.Console real = System.console();
                if (real == null) {
                    return ask(prompt);
                }
                out.print(prompt);
                out.flush();
                char[] typed = real.readPassword();
                if (typed == null) {
                    return Optional.empty();
                }
                String value = new String(typed);
                java.util.Arrays.fill(typed, '\0');
                out.println();
                return Optional.of(value);
            }
        };
        System.exit(run(args, console_ask, out::println));
    }

    /**
     * Pick, show, confirm, download. Returns the worst exit code of the runs it started:
     * 0 worked, 1 the tool failed, 2 a setup problem.
     *
     * <p>130 is the conventional code for Ctrl+C, and nothing here produces it: the JVM takes
     * the signal and dies before any of this runs. The branch below that stops the session on
     * 130 is kept because an engine that does return it must not be mistaken for "skip to the
     * next playlist" - but do not read it as a promise that this tool exits with 130 (an
     * independent review called the javadoc out on it, 2026-10-16).
     */
    public static int run(String[] args, Ask ask, Consumer<String> out) {
        return run(args, ask, out, Wiring.standard(Path.of(".").toAbsolutePath().normalize()));
    }

    /** The same run with the folder, the engine, the link reader and the downloader injected. */
    public static int run(String[] args, Ask ask, Consumer<String> out, Wiring wiring) {
        Front front;
        try {
            front = parse(args);
        } catch (IllegalArgumentException badFlag) {
            out.accept("sockseek-cli: error: " + badFlag.getMessage());
            return 2;
        }
        if (front.help()) {
            HELP.formatted(LAUNCHER).lines().forEach(out);
            return 0;
        }
        if (front.version()) {
            out.accept("sockseek-cli " + Cli.VERSION);
            return 0;
        }

        Path appDir = wiring.appDir();
        Settings.ensureFiles(appDir, out);
        // The login, asked for here rather than left as homework in a file. Not for an
        // --import, which downloads nothing and needs no account.
        if (front.importSource() == null && Settings.missingLogin(appDir)) {
            if (ask == null) {
                // a scheduled run, a pipe: there is nobody to ask, so say where it goes
                out.accept("");
                out.accept("Put your Soulseek login in "
                        + appDir.resolve(Settings.LOCAL_CONFIG_NAME) + ":");
                out.accept("    sockseek:");
                out.accept("      username: your-login");
                out.accept("      password: your-password");
                out.accept("Then run this again. Nothing can be downloaded until then.");
                return 2;
            }
            if (!askForLogin(appDir, ask, out)) {
                return 2;
            }
        }
        Config config = Settings.loadConfig(front.config() != null ? front.config()
                : appDir.resolve(Settings.CONFIG_NAME), out);
        Map<String, Path> folders = Settings.configPaths(config, appDir);
        Path playlistsDir = front.playlists() != null ? front.playlists()
                : folders.get("playlists");
        Path outputDir = front.output() != null ? front.output() : folders.get("music");

        if (front.importSource() != null) {
            if (Links.isLink(front.importSource())) {
                return importLink(front.importSource(), playlistsDir, front, out, config, ask,
                        wiring);
            }
            return importExport(front.importSource(), playlistsDir, front.importName(),
                    front.overwrite(), out);
        }

        // the opening question comes before the scan: with an empty Playlists folder, pasting a
        // link is exactly what a new user needs, and "no playlist files" would be a dead end.
        // The loop is what makes "b" work: every question below can send the person back here
        // instead of forcing them to quit and start the tool again.
        List<Info> chosen;
        if (front.all() && front.only() != null) {
            out.accept("Both --all and --only given; --only wins, taking '" + front.only()
                    + "' alone.");
        }
        boolean canGoBack = ask != null && front.only() == null && !front.all()
                && !front.yes();   // there is a question to go back TO only then
        while (true) {
            Source source = Source.TXT;
            if (canGoBack) {
                Optional<Source> picked = sourceMenu(ask, out);
                if (picked.isEmpty()) {
                    return 0;
                }
                source = picked.get();
            }

            if (source == Source.TRACK) {
                Link link = askForLink(source, ask, out);
                if (link.back()) {
                    continue;
                }
                if (link.url() == null) {
                    return 0;
                }
                Written added = trackToUnsorted(link.url(), playlistsDir, out, config,
                        front.cookiesFrom(), ask, wiring);
                if (added.file() == null) {
                    return added.code();
                }
                chosen = named(scan(playlistsDir, outputDir), UNSORTED);
                if (chosen.isEmpty()) {
                    out.accept("Wrote " + added.file().getFileName()
                            + ", but reading it back found nothing. Look at the file.");
                    return 1;
                }
            } else if (source != Source.TXT) {
                Link link = askForLink(source, ask, out);
                if (link.back()) {
                    continue;
                }
                if (link.url() == null) {
                    return 0;
                }
                Written written = linkToPlaylist(link.url(), playlistsDir, front.importName(),
                        front.overwrite(), out, config, front.cookiesFrom(), ask, wiring, true);
                if (written.file() == null) {
                    return written.code();
                }
                chosen = named(scan(playlistsDir, outputDir), stem(written.file()));
                if (chosen.isEmpty()) {
                    out.accept("Wrote " + written.file().getFileName()
                            + ", but reading it back found nothing. Look at the file.");
                    return 1;
                }
            } else if (front.only() != null) {
                chosen = named(scan(playlistsDir, outputDir), front.only());
                if (chosen.isEmpty()) {
                    out.accept("No playlist named '" + front.only() + "' in " + playlistsDir);
                    return 2;
                }
            } else if (front.all()) {
                // the unattended run: every playlist, no question at all. --yes alone still
                // asks WHICH ones, which is one keystroke too many for a scheduled night
                chosen = scan(playlistsDir, outputDir);
                if (chosen.isEmpty()) {
                    out.accept("No playlist .txt files in " + playlistsDir);
                    return 2;
                }
                out.accept("Playlists:");
                out.accept("");
                formatTable(chosen).forEach(out);
            } else {
                List<Info> infos = scan(playlistsDir, outputDir);
                if (infos.isEmpty()) {
                    out.accept("No playlist .txt files in " + playlistsDir);
                    return 2;
                }
                out.accept("Playlists:");
                out.accept("");
                formatTable(infos).forEach(out);
                out.accept("");
                Choice choice = pick(infos, ask, out, canGoBack);
                if (choice.back()) {
                    continue;
                }
                if (choice.playlists().isEmpty()) {
                    return 0;   // Enter, or the end of input: quitting is not an error
                }
                chosen = choice.playlists();
            }
            break;
        }

        // Everything is shown and priced FIRST, then asked about once. Until 2026-10-17 the
        // question came per playlist, so picking "all" meant sitting at the keyboard for the
        // whole night answering it: "один раз дати відповідь на всі питання початкові".
        int worst = 0;
        List<Info> work = new ArrayList<>();
        for (Info info : chosen) {
            out.accept("");
            formatMissing(info).forEach(out);
            for (String warning : info.warnings()) {
                out.accept("WARN " + warning);
            }
            if (info.unreadable()) {
                // "0 tracks, 0 here, 0 missing" plus "Nothing to do." and exit 0 is what a
                // locked file or an unavailable network drive used to produce, so a scheduled
                // run recorded success having looked at nothing. The engine has always called
                // this a broken playlist and returned 1; now the two agree (found by an
                // independent review, 2026-10-16).
                out.accept("ERROR " + info.name() + ": this playlist could not be read, "
                        + "nothing was searched");
                worst = 1;
                continue;
            }
            if (info.missing().isEmpty()) {
                out.accept("Nothing to do.");
                continue;
            }
            work.add(info);
        }
        if (work.isEmpty()) {
            return worst;
        }

        // what the CACHE will hold back, so the price is the price of this run and not of
        // a run that ignores it. Reading it here as well as in the engine is the only way:
        // the engine answers with an exit code, and by then the yes has been given
        Map<String, Integer> held = heldBack(work, wiring.appDir(), config, front);
        out.accept("");
        int perWindow = searchesPerWindow(config, front);
        int windowSeconds = windowSeconds(config, front);
        formatPrice(work, held, perWindow, windowSeconds, passCount(config, front))
                .forEach(out);
        if (!front.yes() && !confirm(ask, wanted(work) - total(held))) {
            out.accept("Skipped.");
            return worst;
        }
        // the tool is asked for only once the answer is yes: nobody wants to be asked about
        // a 50 MB download before seeing what it would be for
        if (!haveSockseek(config, front.yes() ? null : ask, out, wiring)) {
            return 2;
        }

        int wanted = wanted(work);
        int arrivedSoFar = 0;
        // A dry run and a preview ask the server for nothing, so there is nothing to pace -
        // and pacing them would make the gates sit still for an hour.
        boolean real = front.extra().stream()
                .noneMatch(flag -> flag.equals("--dry-run") || flag.equals("--preview"));
        Pace pace = new Pace(real ? perWindow : 0, windowSeconds, out);
        for (int number = 0; number < work.size(); number++) {
            Info info = work.get(number);
            String step = "[" + (number + 1) + "/" + work.size() + "] ";
            out.accept("");
            pace.waitTurn();
            int searching = info.missing().size() - held.getOrDefault(info.name(), 0);
            out.accept(step + info.name() + ": searching " + searching + " missing track(s)"
                    + (searching < info.missing().size()
                            ? " (" + (info.missing().size() - searching) + " held back)" : ""));
            List<String> argv = new ArrayList<>(List.of("--only", info.name()));
            argv.addAll(front.extra());
            int code;
            try {
                code = wiring.engine().run(List.copyOf(argv));
            } catch (RuntimeException broke) {
                // One playlist must never take the rest of the session with it. A bug inside
                // the engine arrives here as a throw rather than an exit code - that is what
                // the malformed csv of 2026-10-16 did - and the playlists the person is still
                // waiting for are worth more than a stack trace.
                out.accept("ERROR " + info.name() + ": the engine broke on this one ("
                        + broke.getClass().getSimpleName() + ": " + broke.getMessage()
                        + "), going on to the next playlist");
                code = 1;
            }
            Path made = folders.get("work");
            // the engine writes down what it really spent. Reconstructing the file name here
            // stopped working the day format passes gave those files a tag (.mp3., .flac.),
            // and a brake that charges nothing is a brake that is off - measured 2026-10-19
            pace.spent(Pace.spentBy(made, info.name()));
            // counted here rather than asked of the engine, which answers with an exit code
            // only: the folder is the truth either way, and it is the same reading the menu did
            int arrived = info.missing().size() - stillMissing(info, outputDir);
            arrivedSoFar += Math.max(0, arrived);
            out.accept(step + info.name() + ": " + Math.max(0, arrived) + " of "
                    + info.missing().size() + " arrived - " + arrivedSoFar + " of " + wanted
                    + " in all");
            worst = Math.max(worst, code);
            if (code == 130) {   // Ctrl+C means stop the session, not "skip to the next playlist"
                out.accept("Interrupted.");
                break;
            }
        }
        return worst;
    }

    /**
     * The global search brake, from the command line if it is there and the config if not.
     *
     * <p>Read here as well as in the engine because the pause happens BETWEEN engine runs,
     * which is the only place that can see more than one playlist.
     */
    static int searchesPerWindow(Config config, Front front) {
        List<String> given = front.extra();
        for (int n = 0; n < given.size(); n++) {
            String flag = given.get(n);
            String value = flag.startsWith("--searches-per-window=")
                    ? flag.substring("--searches-per-window=".length())
                    : flag.equals("--searches-per-window") && n + 1 < given.size()
                            ? given.get(n + 1) : null;
            if (value != null) {
                try {
                    return Integer.parseInt(value.strip());
                } catch (NumberFormatException notANumber) {
                    return Pace.DEFAULT_PER_WINDOW;   // the engine will complain about it
                }
            }
        }
        return config.integer("searches_per_window").orElse(Pace.DEFAULT_PER_WINDOW);
    }

    /** The window those searches are counted in; it has to be the one sockseek uses. */
    static int windowSeconds(Config config, Front front) {
        String given = flagValue(front, "--searches-window", "--searches-renew-s");
        if (given != null) {
            try {
                return Integer.parseInt(given.strip());
            } catch (NumberFormatException notANumber) {
                return Pace.DEFAULT_WINDOW_SECONDS;
            }
        }
        return config.integer("window_s").orElse(Pace.DEFAULT_WINDOW_SECONDS);
    }

    /** How many tracks the whole set is missing, before the cache has its say. */
    static int wanted(List<Info> work) {
        return work.stream().mapToInt(info -> info.missing().size()).sum();
    }

    static int total(Map<String, Integer> held) {
        return held.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * Per playlist, how many of its missing tracks the failure cache will hold back.
     *
     * <p>The engine decides this for itself when it runs; the front end has to know it
     * BEFORE the question, or the one number the person approves is a number for a different
     * run. Reading the same file twice is cheaper than plumbing an answer back out of an
     * exit code.
     */
    static Map<String, Integer> heldBack(List<Info> work, Path appDir, Config config,
                                         Front front) {
        Map<String, Integer> held = new LinkedHashMap<>();
        if (!useCache(config, front)) {
            return held;
        }
        double hours = skipHours(config, front);
        List<Failures.Outcome> remembered = Failures.read(appDir.resolve(Failures.FILE_NAME));
        if (remembered.isEmpty() || hours <= 0) {
            return held;
        }
        LocalDateTime now = LocalDateTime.now();
        // the same string the run will write down, so the number the person approves is the
        // number the run holds back. A --sockseek-conf layer could still add a hard key the
        // preview cannot see; that is the one case where the two may disagree
        String conditions = Failures.conditions(lengthTol(config, front),
                Settings.hardConditions(config.sockseek()));
        for (Info info : work) {
            Set<String> skip = Failures.skippable(remembered, info.name(), hours, now,
                    conditions);
            long count = info.missing().stream()
                    .filter(entry -> skip.contains(entry.original())).count();
            if (count > 0) {
                held.put(info.name(), (int) count);
            }
        }
        return held;
    }

    /**
     * How many times each still-missing track will be searched for: one pass per format in
     * {@code track.format_order}, plus the last one that takes anything.
     */
    static int passCount(Config config, Front front) {
        String given = flagValue(front, "--format-order");
        String order = given != null ? given : config.text(Settings.FORMAT_ORDER, "");
        long formats = java.util.Arrays.stream(order.split(","))
                .map(String::strip)
                .filter(one -> !one.isEmpty())
                .count();
        return (int) formats + 1;
    }

    /** The hard length condition this run would use, -1 when it is off. */
    private static int lengthTol(Config config, Front front) {
        String given = flagValue(front, "--max-length-diff", "--length-tol");
        if (given != null) {
            try {
                return Integer.parseInt(given.strip());
            } catch (NumberFormatException notANumber) {
                return config.integer("max_length_diff_s").orElse(45);
            }
        }
        return config.integer("max_length_diff_s").orElse(45);
    }

    private static boolean useCache(Config config, Front front) {
        if (front.extra().contains("--no-cache")) {
            return false;
        }
        return config.integer("enabled").orElse(1) != 0;
    }

    private static double skipHours(Config config, Front front) {
        String given = flagValue(front, "--skip-absent", "--skip-not-found-hours");
        if (given != null) {
            try {
                return Double.parseDouble(given.strip());
            } catch (NumberFormatException notANumber) {
                return SKIP_HOURS_DEFAULT;
            }
        }
        return config.decimal("skip_absent_h").orElse(SKIP_HOURS_DEFAULT);
    }

    /** The default of skip_not_found_hours, mirrored here for the price. */
    static final double SKIP_HOURS_DEFAULT = 2;

    /** The value of a flag among the arguments passed through, in either spelling. */
    /** The value of whichever of these flags was given - a renamed flag keeps its old name. */
    private static String flagValue(Front front, String... flags) {
        List<String> given = front.extra();
        for (String flag : flags) {
            for (int n = 0; n < given.size(); n++) {
                String one = given.get(n);
                if (one.startsWith(flag + "=")) {
                    return one.substring(flag.length() + 1);
                }
                if (one.equals(flag) && n + 1 < given.size()) {
                    return given.get(n + 1);
                }
            }
        }
        return null;
    }

    /**
     * What this is going to cost, before the only yes of the session.
     *
     * <p>The minutes are search time alone and nothing else: sockseek asks the Soulseek server
     * at most 34 times per 220 seconds, and that limiter - not the downloads - is what makes a
     * big playlist take a night. The owner's own words on it, 2026-10-17: "я бачив що там треба
     * періодично search rate чекати, але це норм". Saying the number up front turns that wait
     * from a hang into a price.
     */
    public static List<String> formatPrice(List<Info> work, Map<String, Integer> held,
                                           int perWindow, int windowSeconds) {
        return formatPrice(work, held, perWindow, windowSeconds, 1);
    }

    /**
     * The same, told how many passes each track goes through.
     *
     * <p>A format ladder asks for every still-missing track once per format and once more
     * with no format at all, so the bill is that many times the number of tracks. Until
     * 2026-10-19 this number was the track count, and the owner was quoted 135 searches for
     * a set that was about to make 540 - the one number he says yes to, wrong by 4x.
     */
    public static List<String> formatPrice(List<Info> work, Map<String, Integer> held,
                                           int perWindow, int windowSeconds, int passes) {
        int rounds = Math.max(1, passes);
        int missing = wanted(work);
        int tracks = (missing - total(held)) * rounds;
        List<String> lines = new ArrayList<>();
        lines.add("To download: " + work.size() + " playlist(s), " + missing
                + " missing track(s)."
                + (total(held) > 0 ? " " + total(held) + " of them were searched for recently "
                        + "and nobody had them, so " + tracks + " will be searched." : ""));
        // summed per playlist, not over the total: the limiter lives inside ONE sockseek
        // process, and there is one process per playlist, so each playlist opens with a full
        // budget of its own. Counting the set as a single budget overstated the wait badly -
        // twenty playlists of seventeen tracks each wait for nothing at all.
        int minutes = work.stream()
                .mapToInt(info -> searchMinutes((info.missing().size()
                        - held.getOrDefault(info.name(), 0)) * rounds, perWindow,
                        windowSeconds))
                .sum();
        lines.add("That is about " + tracks + " search(es)"
                + (rounds > 1 ? " - " + rounds + " passes over every missing track, because "
                        + "track.format_order asks for one format at a time" : "")
                + "; sockseek is allowed " + perWindow + " per " + windowSeconds + " s and "
                + "each playlist gets its own budget, so waiting for the limiter adds about "
                + minutes + " min in all. Searching and downloading themselves come on top.");
        return List.copyOf(lines);
    }



    /**
     * Whole minutes of search for that many tracks, rounded up, never less than one.
     *
     * <p>The first window's worth of searches costs nothing but its own time; it is every
     * window after that which has to be waited out.
     */
    static int searchMinutes(int tracks, int perWindow, int windowSeconds) {
        if (perWindow <= 0) {
            return 0;
        }
        int windows = Math.max(0, (tracks - 1) / perWindow);
        return (int) Math.ceil(windows * windowSeconds / 60.0);
    }

    /** How many of this playlist's missing tracks are still missing, read from the folder. */
    private static int stillMissing(Info info, Path outputDir) {
        try {
            Status now = SockseekIndex.playlistStatus(Playlist.fromFile(info.path()).entries(),
                    outputDir.resolve(info.name()));
            return (int) info.missing().stream()
                    .filter(entry -> !now.files().containsKey(entry.original())).count();
        } catch (IOException | RuntimeException cannotRead) {
            // the progress line is not worth a failed run; say nothing moved rather than guess
            return info.missing().size();
        }
    }

    /** Every playlist in the folder, with what is already downloaded. */
    public static List<Info> scan(Path playlistsDir, Path outputDir) {
        List<Path> files;
        try {
            files = Playlists.find(playlistsDir);
        } catch (IOException | UncheckedIOException noFolder) {
            return List.of();
        }
        List<Info> found = new ArrayList<>();
        for (Path file : files) {
            try {
                Playlist playlist = Playlist.fromFile(file);
                // the index is read inside the same try on purpose: nothing about one
                // playlist's files may stop the table that lists all of them
                Status status = SockseekIndex.playlistStatus(playlist.entries(),
                        outputDir.resolve(stem(file)));
                found.add(new Info(stem(file), file, playlist.entries().size(), status,
                        playlist.warnings()));
            } catch (IOException | RuntimeException cannotRead) {
                found.add(new Info(stem(file), file, 0,
                        new Status(List.of(), List.of(), Map.of()),
                        List.of("cannot read: " + cannotRead.getMessage()), true));
            }
        }
        return List.copyOf(found);
    }

    /** The menu: number, name, how many tracks, how many are here, how many are missing. */
    public static List<String> formatTable(List<Info> infos) {
        int width = Math.max(8,
                infos.stream().mapToInt(info -> length(info.name())).max().orElse(0));
        List<String> lines = new ArrayList<>();
        lines.add("  #  " + pad("playlist", width) + "  tracks  here  missing");
        lines.add("  -  " + "-".repeat(width) + "  ------  ----  -------");
        for (int n = 0; n < infos.size(); n++) {
            Info info = infos.get(n);
            lines.add("%3d  %s  %6d  %4d  %7d".formatted(n + 1, pad(info.name(), width),
                    info.total(), info.have(), info.missing().size()));
        }
        return List.copyOf(lines);
    }

    /**
     * "1" -> [0]; "1,3" -> [0, 2]; "all" -> everything; "" -> []. Empty means "not understood".
     *
     * <p>Numbers are 1-based for the person and 0-based in the result. Duplicates collapse,
     * order is kept, and anything out of range makes the whole answer invalid - no silent
     * guessing.
     */
    public static Optional<List<Integer>> parseChoice(String text, int count) {
        String answer = strip(text).toLowerCase(Locale.ROOT);
        if (answer.isEmpty()) {
            return Optional.of(List.of());
        }
        if (answer.equals("all") || answer.equals("*")) {
            List<Integer> all = new ArrayList<>();
            for (int n = 0; n < count; n++) {
                all.add(n);
            }
            return Optional.of(List.copyOf(all));
        }
        List<Integer> picked = new ArrayList<>();
        for (String part : answer.replace(" ", ",").split(",", -1)) {
            if (part.isEmpty()) {
                continue;
            }
            if (!part.chars().allMatch(digit -> digit >= '0' && digit <= '9')) {
                // ASCII digits only: a superscript two is a digit to Character.isDigit and
                // is not a number anybody meant to type
                return Optional.empty();
            }
            int number;
            try {
                number = Integer.parseInt(part);
            } catch (NumberFormatException tooBig) {
                return Optional.empty();   // far out of range, which is not a choice
            }
            if (number < 1 || number > count) {
                return Optional.empty();
            }
            if (!picked.contains(number - 1)) {
                picked.add(number - 1);
            }
        }
        return Optional.of(List.copyOf(picked));
    }

    /** The git-status-like list: what this playlist is still missing, by playlist line. */
    public static List<String> formatMissing(Info info) {
        List<String> lines = new ArrayList<>();
        lines.add(info.name() + ": " + info.total() + " tracks, " + info.have() + " here, "
                + info.missing().size() + " missing");
        if (!info.missing().isEmpty()) {
            lines.add("");
            lines.add("Missing:");
            List<Entry> missing = info.missing();
            for (int n = 0; n < missing.size(); n++) {
                lines.add("%4d  %s".formatted(n + 1, missing.get(n).original()));
            }
        }
        return List.copyOf(lines);
    }

    /**
     * Ask for the Soulseek login and write it into config.local.yaml.
     *
     * <p>False when there is nothing to go on with: the person pressed Enter instead of
     * typing, or the file could not be written. The password is never printed, never logged
     * and never echoed - {@link Ask#secret} hides the typing where there is a console.
     *
     * <p>The file stays a plain file on purpose: it can still be edited by hand, which is
     * how a password with a character this prompt mangles gets fixed, and how a second
     * machine gets set up by copying one file.
     */
    static boolean askForLogin(Path appDir, Ask ask, Consumer<String> out) {
        out.accept("");
        out.accept("Your Soulseek login, once: it is written to "
                + Settings.LOCAL_CONFIG_NAME + " and never asked again.");
        out.accept("(the same account you use in the Soulseek client; "
                + "git never sees that file)");
        String username = ask.ask("Username: ").map(App::strip).orElse("");
        if (username.isEmpty()) {
            out.accept("Nothing typed, so nothing was saved. Run it again when you have it.");
            return false;
        }
        // not stripped: a password may legitimately start or end with a space. Only the
        // invisible byte-order mark goes, for the reason above
        String password = ask.secret("Password: ").orElse("").replace("\uFEFF", "");
        if (password.isEmpty()) {
            out.accept("No password typed, so nothing was saved. Run it again when you "
                    + "have it.");
            return false;
        }
        try {
            Settings.writeLogin(appDir, username, password);
        } catch (IOException cannotWrite) {
            out.accept("Could not write " + Settings.LOCAL_CONFIG_NAME + ": "
                    + cannotWrite.getMessage());
            return false;
        }
        out.accept("Saved to " + appDir.resolve(Settings.LOCAL_CONFIG_NAME)
                + " - edit that file if it ever changes.");
        return true;
    }

    /** The opening question. Empty means quit, and quitting is not an error. */
    static Optional<Source> sourceMenu(Ask ask, Consumer<String> out) {
        out.accept("Where from?");
        out.accept("");
        for (Source source : Source.values()) {
            out.accept("  " + source.key() + "  " + source.description());
        }
        out.accept("");
        while (true) {
            Optional<String> answer = ask.ask("Pick one (Enter quits): ");
            if (answer.isEmpty() || strip(answer.get()).isEmpty()) {
                return Optional.empty();
            }
            Optional<Source> picked = Source.of(answer.get());
            if (picked.isPresent()) {
                return picked;
            }
            out.accept("Type 1, 2, 3 or 4, or Enter to quit.");
        }
    }

    /** The link for option 2 or 3. "b" goes back to the source menu, Enter cancels. */
    static Link askForLink(Source service, Ask ask, Consumer<String> out) {
        out.accept("");
        if (ask == null) {
            return new Link(null, false);
        }
        String answer = ask.ask("Paste the " + service.label()
                + " link ([b] back, Enter cancels): ").map(App::strip).orElse("");
        if (isBack(answer)) {
            return new Link(null, true);
        }
        return new Link(answer.isEmpty() ? null : answer, false);
    }

    /** The playlists the person asked for; "b" goes back, Enter quits. */
    private static Choice pick(List<Info> infos, Ask ask, Consumer<String> out,
                               boolean canGoBack) {
        if (ask == null) {
            // nobody to ask and no --only: there is nothing to choose with
            return new Choice(List.of(), false);
        }
        while (true) {
            // "[b] back" is offered only when the source menu was actually shown: with
            // --yes there is no earlier question, and advertising a step back that merely
            // redisplays the same table is a lie (found by an independent review, 2026-10-16)
            Optional<String> answer = ask.ask("What to download? (1, or 1,2, or all; "
                    + (canGoBack ? "[b] back, " : "") + "Enter quits): ");
            if (answer.isEmpty()) {
                return new Choice(List.of(), false);
            }
            if (canGoBack && isBack(strip(answer.get()))) {
                return new Choice(List.of(), true);
            }
            Optional<List<Integer>> picked = parseChoice(answer.get(), infos.size());
            if (picked.isEmpty()) {
                out.accept("Type a number from 1 to " + infos.size()
                        + ", several separated by commas, or all.");
                continue;
            }
            return new Choice(picked.get().stream().map(infos::get).toList(), false);
        }
    }

    /** "b" or "back", in any case: the step back to the question before this one. */
    private static boolean isBack(String answer) {
        String said = answer.toLowerCase(Locale.ROOT);
        return said.equals("b") || said.equals("back");
    }

    /** [y] download / [n] skip. Anything else asks again; the end of input means no. */
    static boolean confirm(Ask ask, int count) {
        if (ask == null) {
            return false;
        }
        while (true) {
            Optional<String> answer = ask.ask("Download these " + count + "? [y] yes  [n] no: ");
            if (answer.isEmpty()) {
                return false;
            }
            String said = strip(answer.get()).toLowerCase(Locale.ROOT);
            if (said.equals("y") || said.equals("yes")) {
                return true;
            }
            if (said.equals("n") || said.equals("no") || said.isEmpty()) {
                return false;
            }
        }
    }

    /** Replace the existing playlist, or keep it and work on what is already in it. */
    static boolean replace(Ask ask, String name) {
        while (true) {
            Optional<String> answer =
                    ask.ask(name + " already exists. [r] replace  [k] keep it: ");
            if (answer.isEmpty()) {
                return false;
            }
            String said = strip(answer.get()).toLowerCase(Locale.ROOT);
            if (said.equals("r") || said.equals("replace")) {
                return true;
            }
            if (said.equals("k") || said.equals("keep") || said.isEmpty()) {
                return false;
            }
        }
    }

    /** A yes/no question for the tool downloads, with the same answers as the main one. */
    static boolean yes(Ask ask, String question) {
        String said = ask.ask(question + " [y] yes  [n] no: ")
                .map(App::strip).orElse("").toLowerCase(Locale.ROOT);
        return said.equals("y") || said.equals("yes");
    }

    /** {@code --import <link>}: write the playlist file and stop there. */
    private static int importLink(String url, Path playlistsDir, Front front,
                                  Consumer<String> out, Config config, Ask ask, Wiring wiring) {
        Written written = linkToPlaylist(url, playlistsDir, front.importName(),
                front.overwrite(), out, config, front.cookiesFrom(), ask, wiring, false);
        if (written.file() != null) {
            out.accept("Now run: " + LAUNCHER + " --only " + stem(written.file()));
        }
        return written.code();
    }

    /**
     * A Spotify or SoundCloud link -> a playlist file.
     *
     * <p>Only the track list is taken. The downloading stays with the engine, so a link ends up
     * in the same place a hand-written list does: a file you can read, edit and re-run.
     */
    /**
     * Option 4: one track from a link, added to the unsorted playlist and downloaded there.
     *
     * <p>A link that turns out to hold several tracks adds all of them and says so, rather
     * than taking the first and quietly dropping the rest - there is no reading of the
     * person's mind that can be right there, and the folder is the same either way.
     */
    private static Written trackToUnsorted(String url, Path playlistsDir, Consumer<String> out,
                                           Config config, String cookiesFrom, Ask ask,
                                           Wiring wiring) {
        Optional<Linked> read = readLink(url, out, cookiesFrom, ask, wiring);
        if (read.isEmpty()) {
            return new Written(2, null);
        }
        Linked linked = read.get();
        List<String> added;
        try {
            added = Imports.appendToPlaylist(linked.lines(), playlistsDir, UNSORTED);
        } catch (IOException cannotWrite) {
            out.accept("Nothing written: " + cannotWrite.getMessage());
            return new Written(2, null);
        }
        Path file = Imports.playlistFile(playlistsDir, UNSORTED);
        if (added.isEmpty()) {
            out.accept(linked.lines().size() + " track(s) already in "
                    + file.getFileName() + "; downloading what is still missing.");
            return new Written(0, file);
        }
        for (String line : added) {
            out.accept("  + " + line);
        }
        out.accept(added.size() + " track(s) added to " + file.getFileName()
                + "; they land in " + UNSORTED + "/");
        return new Written(0, file);
    }

    /** The link, read into tracks, with every reason it could not be read already said. */
    private static Optional<Linked> readLink(String url, Consumer<String> out,
                                             String cookiesFrom, Ask ask, Wiring wiring) {
        Optional<String> service = Links.detect(url);
        if (service.isEmpty()) {
            out.accept("Not a link this tool can read: " + url);
            out.accept("Spotify and SoundCloud links are supported.");
            return Optional.empty();
        }
        Path tool = null;
        if (service.get().equals("soundcloud")) {
            Predicate<String> agreed = ask == null ? null : question -> yes(ask, question);
            Optional<Path> found = Tools.ensure("yt-dlp", out, pinned(wiring), wiring.appDir(),
                    agreed, wiring.downloader());
            if (found.isEmpty()) {
                out.accept("Without yt-dlp a SoundCloud link cannot be read.");
                return Optional.empty();
            }
            tool = found.get();
        }
        out.accept("Reading " + url);
        Linked linked = wiring.reader().read(url, tool, cookiesFrom);
        for (String warning : linked.warnings()) {
            out.accept("WARN " + warning);
        }
        if (linked.lines().isEmpty()) {
            out.accept("Nothing imported.");
            return Optional.empty();
        }
        return Optional.of(linked);
    }

    private static Written linkToPlaylist(String url, Path playlistsDir, String name,
                                          boolean overwrite, Consumer<String> out, Config config,
                                          String cookiesFrom, Ask ask, Wiring wiring,
                                          boolean askOverwrite) {
        Optional<String> service = Links.detect(url);
        if (service.isEmpty()) {
            out.accept("Not a link this tool can read: " + url);
            out.accept("Spotify and SoundCloud playlists are supported.");
            return new Written(2, null);
        }
        Path tool = null;
        if (service.get().equals("soundcloud")) {
            // sockseek has no SoundCloud input type, so this one needs yt-dlp - fetched on
            // demand, and only after the person agreed to the download. Tools.load reads
            // dependencies.yaml: `config` here is config.yaml and knows nothing about pinned
            // tools
            Predicate<String> agreed = ask == null ? null : question -> yes(ask, question);
            Optional<Path> found = Tools.ensure("yt-dlp", out, pinned(wiring), wiring.appDir(),
                    agreed, wiring.downloader());
            if (found.isEmpty()) {
                out.accept("Without yt-dlp a SoundCloud link cannot be read.");
                return new Written(2, null);
            }
            tool = found.get();
        }
        out.accept("Reading " + url);
        Linked linked = wiring.reader().read(url, tool, cookiesFrom);
        for (String warning : linked.warnings()) {
            out.accept("WARN " + warning);
        }
        if (linked.lines().isEmpty()) {
            out.accept("Nothing imported.");
            return new Written(2, null);
        }
        String stem = name != null && !name.isBlank() ? name
                : orElse(Links.slug(linked.name()), "playlist");
        Path target = Imports.playlistFile(playlistsDir, stem);
        boolean replacing = overwrite;
        if (Files.isRegularFile(target) && !replacing && askOverwrite && ask != null) {
            // a playlist is hand-edited, so the choice is the person's, not a flag's
            replacing = replace(ask, target.getFileName().toString());
            if (!replacing) {
                out.accept("Keeping " + target.getFileName() + " as it is.");
                return new Written(0, target);
            }
        }
        Optional<Path> written;
        try {
            written = Imports.writePlaylist(linked.lines(), playlistsDir, stem, replacing);
        } catch (IOException cannotWrite) {
            out.accept("Nothing written: " + cannotWrite.getMessage());
            return new Written(2, null);
        }
        if (written.isEmpty()) {
            out.accept("Nothing written: " + target.getFileName()
                    + " already exists. Pass --overwrite to replace it.");
            return new Written(2, null);
        }
        out.accept(written.get().getFileName() + ": " + linked.lines().size()
                + " track(s) from " + linked.source()
                + (linked.name() == null || linked.name().isEmpty()
                        ? "" : " (" + linked.name() + ")")
                + ", " + withLength(linked.lines()) + " with a length.");
        return new Written(0, written.get());
    }

    /** {@code --import}: write a playlist from an exported list and report what happened. */
    private static int importExport(String what, Path playlistsDir, String name,
                                    boolean overwrite, Consumer<String> out) {
        Path source;
        try {
            source = Path.of(what);
        } catch (InvalidPathException notAPath) {
            out.accept("No such file: " + what);
            return 2;
        }
        if (!Files.isRegularFile(source)) {
            out.accept("No such file: " + source);
            return 2;
        }
        ImportResult result;
        try {
            result = Imports.importFile(source, playlistsDir, name, overwrite);
        } catch (IOException cannotRead) {
            out.accept("No such file: " + source);
            return 2;
        }
        Imported found = result.found();
        for (String warning : found.warnings()) {
            out.accept("WARN " + warning);
        }
        if (result.written().isEmpty()) {
            out.accept("Nothing imported from " + source.getFileName()
                    + " (recognised as: " + found.source() + ").");
            if (!found.lines().isEmpty()) {
                out.accept("Pass --overwrite to replace the existing playlist.");
            }
            return 2;
        }
        Path written = result.written().get();
        out.accept(written.getFileName() + ": " + found.lines().size() + " track(s) from "
                + source.getFileName() + " (format: " + found.source() + "), "
                + withLength(found.lines()) + " with a length.");
        out.accept("Now run: " + LAUNCHER + " --only " + stem(written));
        return 0;
    }

    /**
     * Make sure sockseek is there before the first download is promised.
     *
     * <p>A configured path that exists is left alone; only a missing binary triggers the offer
     * to fetch the pinned version (dependencies.yaml) into the tool's own folder.
     */
    private static boolean haveSockseek(Config config, Ask ask, Consumer<String> out,
                                        Wiring wiring) {
        Path configured = Settings.configPaths(config, wiring.appDir()).get("sockseek_bin");
        if (Files.isRegularFile(configured)) {
            return true;
        }
        // --yes: a scheduled run must not stop on a question, so it agrees by itself
        Predicate<String> confirm = ask == null ? question -> true
                : question -> yes(ask, question);
        out.accept("sockseek is not at " + configured);
        Optional<Path> found = Tools.ensure("sockseek", out, pinned(wiring), wiring.appDir(),
                confirm, wiring.downloader());
        if (found.isEmpty()) {
            out.accept("Without sockseek nothing can be downloaded. See the README.");
            return false;
        }
        return true;
    }

    private static Map<String, String> pinned(Wiring wiring) {
        return Tools.load(wiring.appDir().resolve(Tools.DEPENDENCIES_NAME));
    }

    /**
     * The front end's flags; everything else is left for the engine.
     *
     * <p>The three path options are read HERE as well, not merely passed on: the menu and the
     * "here / missing" counts have to look at the folders the engine will actually use.
     */
    static Front parse(String[] args) {
        String only = null;
        String importSource = null;
        String cookiesFrom = null;
        String importName = null;
        Path playlists = null;
        Path output = null;
        Path config = null;
        boolean yes = false;
        boolean all = false;
        boolean overwrite = false;
        boolean version = false;
        boolean help = false;
        List<String> extra = new ArrayList<>();
        List<String> rest = new ArrayList<>(List.of(args));
        while (!rest.isEmpty()) {
            String argument = rest.remove(0);
            String flag = argument;
            String inline = null;
            int equals = argument.indexOf('=');
            if (argument.startsWith("--") && equals > 2) {
                flag = argument.substring(0, equals);
                inline = argument.substring(equals + 1);
            }
            switch (flag) {
                case "--only" -> only = value(flag, inline, rest);
                case "--import" -> importSource = value(flag, inline, rest);
                case "--cookies-from-browser" -> cookiesFrom = value(flag, inline, rest);
                case "--as" -> importName = value(flag, inline, rest);
                case "--playlists" -> playlists = pathValue(flag, inline, rest);
                case "--output" -> output = pathValue(flag, inline, rest);
                case "--config" -> config = pathValue(flag, inline, rest);
                case "--yes" -> yes = true;
                case "--all" -> all = true;
                case "--overwrite" -> overwrite = true;
                case "--version" -> version = true;
                case "-h", "--help" -> help = true;
                default -> extra.add(argument);
            }
        }
        Map<String, Path> again = new LinkedHashMap<>();
        if (playlists != null) {
            again.put("--playlists", playlists);
        }
        if (output != null) {
            again.put("--output", output);
        }
        if (config != null) {
            again.put("--config", config);
        }
        for (Map.Entry<String, Path> folder : again.entrySet()) {
            extra.add(folder.getKey());
            extra.add(folder.getValue().toString());
        }
        return new Front(only, yes, all, importSource, cookiesFrom, importName, overwrite,
                version, help, playlists, output, config, List.copyOf(extra));
    }

    private static String value(String flag, String inline, List<String> rest) {
        if (inline != null) {
            return inline;
        }
        if (rest.isEmpty() || (rest.get(0).startsWith("-") && rest.get(0).length() > 1)) {
            throw new IllegalArgumentException("argument " + flag + ": expected one argument");
        }
        return rest.remove(0);
    }

    private static Path pathValue(String flag, String inline, List<String> rest) {
        String given = value(flag, inline, rest);
        try {
            return Path.of(given);
        } catch (InvalidPathException notAPath) {
            throw new IllegalArgumentException("argument " + flag + ": not a path: " + given);
        }
    }

    private static List<Info> named(List<Info> infos, String name) {
        return infos.stream().filter(info -> info.name().equals(name)).toList();
    }

    private static long withLength(List<String> lines) {
        return lines.stream().filter(line -> line.contains("|")).count();
    }

    private static String orElse(String text, String fallback) {
        return text == null || text.isEmpty() ? fallback : text;
    }

    static String stem(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    /** Code points, not chars: a name with an emoji must not throw the columns out. */
    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    private static String pad(String text, int width) {
        int length = length(text);
        return length >= width ? text : text + " ".repeat(width - length);
    }

    /**
     * Strip, including the NO-BREAK SPACE.
     *
     * <p>Java's {@link String#strip()} leaves U+00A0 in place, and Spotify really does send
     * them - a title that arrived with one went looking for a track whose name had an
     * invisible character on the end of it.
     */
    static String strip(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isSpace(text.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isSpace(char character) {
        // U+FEFF is neither, by Java's reckoning: it is a zero-width no-break space, and it
        // arrives at the front of the first typed line whenever something upstream wrote the
        // input as UTF-8 with a BOM. Measured 2026-10-19: it rode into a username and would
        // have been a login that is wrong for a reason nobody can see on screen
        return Character.isWhitespace(character) || Character.isSpaceChar(character)
                || character == '\uFEFF';
    }

    private static Optional<String> readLine(BufferedReader in) {
        try {
            return Optional.ofNullable(in.readLine());
        } catch (IOException problem) {
            return Optional.empty();
        }
    }
}
