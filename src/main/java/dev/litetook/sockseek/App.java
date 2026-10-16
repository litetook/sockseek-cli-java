package dev.litetook.sockseek;

import dev.litetook.sockseek.cli.Cli;
import dev.litetook.sockseek.config.Config;
import dev.litetook.sockseek.config.Settings;
import dev.litetook.sockseek.deps.Downloader;
import dev.litetook.sockseek.deps.Tools;
import dev.litetook.sockseek.importing.ImportResult;
import dev.litetook.sockseek.importing.Imported;
import dev.litetook.sockseek.importing.Imports;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
 * <p>The Python tool's {@code run.py} is the specification: every line this prints is the line
 * that one prints, except where a line names a Python command this tool does not have - see
 * {@link #LAUNCHER} and {@link #HELP}.
 */
public final class App {

    /** How this tool is started, for the "now run" hints. The Python tool says "py run.py". */
    static final String LAUNCHER =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                    ? "run.cmd" : "./run.sh";

    /**
     * What {@code --help} prints: the Python front end's docstring, with the three places that
     * name a Python file changed - the invocation, the {@code --ffprobe-check} example (the
     * quality module is not ported) and the {@code setup.py} clause (there is no setup step).
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
                --import <what>   turn an exported list (m3u/m3u8/csv/tsv/txt) or a playlist link
                                  (Spotify, SoundCloud) into a playlist file and exit
                --as <name>       name for the imported playlist (default: its name at the source)
                --overwrite       let --import replace a playlist that already exists
                --cookies-from-browser <browser>
                                  read a private SoundCloud set with the browser's own login
                                  (chrome, firefox, edge, brave, ...) - you log in there, not here
                --version         print the version and exit
            Anything else is passed straight through to the engine, e.g. --length-tol 60, --verbose,
            --no-explain, --dry-run.

            A tool it needs but cannot find (sockseek, and later yt-dlp) is offered for download at the
            version pinned in dependencies.yaml.""";

    /** Where the list comes from. The first question of an interactive run. */
    public enum Source {
        TXT("1", "txt", "download a playlist from a .txt file"),
        SPOTIFY("2", "Spotify", "save a .txt from a Spotify link, then download it"),
        SOUNDCLOUD("3", "SoundCloud", "save a .txt from a SoundCloud link, then download it");

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
    }

    /** The engine, which is {@link Cli}. Injected so a test can watch what it was handed. */
    @FunctionalInterface
    public interface Engine {
        int run(List<String> argv);
    }

    /**
     * Everything the front end talks to that a test wants to replace.
     *
     * <p>The Python tool passes {@code engine} and {@code reader} to {@code main()} and reads
     * {@code APP_DIR} from the module; here they travel together so the signature of
     * {@link #run} stays short.
     */
    public record Wiring(Path appDir, Engine engine, LinkReader reader, Downloader downloader) {

        public static Wiring standard(Path appDir) {
            return new Wiring(appDir, argv -> Cli.main(argv, appDir, System.err),
                    new LinkReader(), Downloader.system());
        }
    }

    /** One playlist as the menu shows it. */
    public record Info(String name, Path path, int total, Status status, List<String> warnings) {

        public Info {
            warnings = List.copyOf(warnings);
        }

        public List<Entry> missing() {
            return status.missing();
        }

        public int have() {
            return status.have().size();
        }
    }

    /** The front end's own flags; {@code extra} is everything the engine gets untouched. */
    record Front(String only, boolean yes, String importSource, String cookiesFrom,
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
        // "ADELA" with an accent and "Ben Bohmer" with an umlaut: say it explicitly
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true,
                StandardCharsets.UTF_8);
        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        System.exit(run(args, prompt -> {
            out.print(prompt);
            out.flush();
            return readLine(in);
        }, out::println));
    }

    /**
     * Pick, show, confirm, download. Returns the worst exit code of the runs it started:
     * 0 worked, 1 the tool failed, 2 a setup problem, 130 Ctrl+C.
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
        if (Settings.ensureFiles(appDir, out).contains(Settings.SOCKSEEK_LOCAL_CONF)) {
            out.accept("");
            out.accept("Put your Soulseek login in "
                    + appDir.resolve(Settings.SOCKSEEK_LOCAL_CONF) + ":");
            out.accept("    username = your-login");
            out.accept("    password = your-password");
            out.accept("Then run this again. Nothing can be downloaded until then.");
            return 2;
        }
        Config config = Settings.loadConfig(front.config() != null ? front.config()
                : appDir.resolve(Settings.CONFIG_NAME), out);
        Map<String, Path> folders = Settings.configPaths(config, appDir);
        Path playlistsDir = front.playlists() != null ? front.playlists()
                : folders.get("playlists");
        Path outputDir = front.output() != null ? front.output() : folders.get("output");

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
        while (true) {
            Source source = Source.TXT;
            if (ask != null && front.only() == null && !front.yes()) {
                Optional<Source> picked = sourceMenu(ask, out);
                if (picked.isEmpty()) {
                    return 0;
                }
                source = picked.get();
            }

            if (source != Source.TXT) {
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
                Choice choice = pick(infos, ask, out);
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

        int worst = 0;
        for (Info info : chosen) {
            out.accept("");
            formatMissing(info).forEach(out);
            for (String warning : info.warnings()) {
                out.accept("WARN " + warning);
            }
            if (info.missing().isEmpty()) {
                out.accept("Nothing to do.");
                continue;
            }
            out.accept("");
            if (!front.yes() && !confirm(ask, info.missing().size())) {
                out.accept("Skipped.");
                continue;
            }
            // the tool is asked for only once the answer is yes: nobody wants to be asked about
            // a 50 MB download before seeing what it would be for
            if (!haveSockseek(config, front.yes() ? null : ask, out, wiring)) {
                return 2;
            }
            out.accept("");
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
            worst = Math.max(worst, code);
            if (code == 130) {   // Ctrl+C means stop the session, not "skip to the next playlist"
                out.accept("Interrupted.");
                break;
            }
        }
        return worst;
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
                        List.of("cannot read: " + cannotRead.getMessage())));
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
                // "2".isdigit() is true for superscripts too, and Python's int() rejects those
                return Optional.empty();
            }
            int number;
            try {
                number = Integer.parseInt(part);
            } catch (NumberFormatException tooBig) {
                return Optional.empty();   // out of range, exactly as it is in Python
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
            out.accept("Type 1, 2 or 3, or Enter to quit.");
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
    private static Choice pick(List<Info> infos, Ask ask, Consumer<String> out) {
        if (ask == null) {
            // nobody to ask and no --only: there is nothing to choose with
            return new Choice(List.of(), false);
        }
        while (true) {
            Optional<String> answer =
                    ask.ask("What to download? (1, or 1,2, or all; [b] back, Enter quits): ");
            if (answer.isEmpty()) {
                return new Choice(List.of(), false);
            }
            if (isBack(strip(answer.get()))) {
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
        Path configured = Settings.configPaths(config, wiring.appDir()).get("sockseek");
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
        return new Front(only, yes, importSource, cookiesFrom, importName, overwrite, version,
                help, playlists, output, config, List.copyOf(extra));
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
     * Python's {@code str.strip()}, which also removes a NO-BREAK SPACE.
     *
     * <p>Java's {@link String#strip()} leaves U+00A0 in place, and the parity work has already
     * found one of those arriving from Spotify.
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
        return Character.isWhitespace(character) || Character.isSpaceChar(character);
    }

    private static Optional<String> readLine(BufferedReader in) {
        try {
            return Optional.ofNullable(in.readLine());
        } catch (IOException problem) {
            return Optional.empty();
        }
    }
}
