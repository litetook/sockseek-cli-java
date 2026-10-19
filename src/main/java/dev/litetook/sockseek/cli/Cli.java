package dev.litetook.sockseek.cli;

import dev.litetook.sockseek.batch.RunBatch;
import dev.litetook.sockseek.batch.RunConfig;
import dev.litetook.sockseek.config.Config;
import dev.litetook.sockseek.config.Setting;
import dev.litetook.sockseek.config.Settings;
import dev.litetook.sockseek.process.Options;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParseResult;

/**
 * The engine's command line. The everyday entry point is the menu in {@link
 * dev.litetook.sockseek.App}, which calls this.
 *
 * <p>Every setting ends up with a value and a source - cli, config or default - and the run log
 * opens with both, so a number that looks wrong can be traced to the place it came from without
 * guessing.
 */
@Command(name = "sockseek-cli", sortOptions = false,
        description = "Batch-download playlists via sockseek: one process per playlist, "
                + "flat output.")
public final class Cli implements Callable<Integer> {

    /**
     * The release this binary is.
     *
     * <p>1.0.0 since 2026-10-17, and it says the thing people actually want to know: that
     * this is finished enough to use, not a preview of something. The release workflow
     * refuses to publish a tag whose name disagrees with this.
     */
    public static final String VERSION = "2.3.0";

    @Option(names = "--version", versionHelp = true, description = "print the version and exit")
    boolean version;

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "this help")
    boolean help;

    @Option(names = "--playlists", paramLabel = "DIR",
            description = "folder with playlist .txt files (config: paths.playlists)")
    Path playlists;

    @Option(names = {"--music", "--output"}, paramLabel = "DIR",
            description = "export folder (config: paths.music)")
    Path output;

    @Option(names = "--sockseek", paramLabel = "FILE",
            description = "path to sockseek.exe (config: paths.sockseek_bin)")
    Path sockseek;

    @Option(names = "--only", paramLabel = "NAME",
            description = "process only this playlist (file name without .txt)")
    String only;

    @Option(names = {"--max-length-diff", "--length-tol"}, paramLabel = "SEC",
            description = "refuse a file whose length differs from the playlist by more than "
                    + "this many seconds; HARD condition, -1 disables (default 45) "
                    + "(config: track.max_length_diff_s)")
    Integer lengthTol;

    @Option(names = {"--attempt-timeout", "--playlist-timeout"}, paramLabel = "MIN",
            description = "wall-clock limit for ONE attempt at one playlist, in minutes, "
                    + "0 = none (default 60) (config: playlist.attempt_timeout_min)")
    Double playlistTimeout;

    @Option(names = {"--attempts", "--retries"}, paramLabel = "N",
            description = "goes at one playlist, at least 1; 1 means one go and no repeat "
                    + "(default 1) (config: playlist.attempts)")
    Integer retries;

    @Option(names = "--stall-timeout", paramLabel = "SEC",
            description = "stop an attempt when no track has finished and nothing has arrived "
                    + "on disk for this many seconds, 0 = never (default 300) "
                    + "(config: playlist.stall_timeout_s)")
    Double stallTimeout;

    @Option(names = {"--skip-absent", "--skip-not-found-hours"}, paramLabel = "HOURS",
            description = "do not search again for a track confirmed absent from the network, "
                    + "if that was within this many hours; 0 = always search (default 2). "
                    + "Fractions are fine (config: cache.skip_absent_h)")
    Double skipNotFoundHours;

    @Option(names = "--searches-per-window", paramLabel = "N",
            description = "keep the searches of ALL playlists together under this many per "
                    + "window, by pausing between them; 0 = no pause (default 34) "
                    + "(config: rate_limit.searches_per_window)")
    Integer searchesPerWindow;

    @Option(names = "--bad-uploader-hours", paramLabel = "HOURS",
            description = "rank an uploader last if a transfer from it failed within this "
                    + "many hours; 0 = forget them at once (default 24)")
    Double badUploaderHours;

    @Option(names = "--work", paramLabel = "DIR",
            description = "generated list files and the merged sockseek config "
                    + "(config: paths.work)")
    Path work;

    @Option(names = "--logs", paramLabel = "DIR",
            description = "run logs, session logs and the run counter (config: paths.logs)")
    Path logs;

    @Option(names = {"--searches-window", "--searches-renew-s"}, paramLabel = "SEC",
            description = "the window those searches are counted in, in seconds (default 220) "
                    + "(config: rate_limit.window_s)")
    Integer searchesRenewS;

    @Option(names = {"--attempts-per-pass", "--max-sources"}, paramLabel = "N",
            description = "download tries for one track inside one pass; each try is the "
                    + "next candidate that fits that pass (default 3)")
    Integer attemptsPerPass;

    @Option(names = "--why", paramLabel = "TEXT",
            description = "do not download: for every missing track whose line contains TEXT, "
                    + "search once more with every condition off and say what is really out "
                    + "there. One search each; an empty TEXT asks about all of them")
    String why;

    @Option(names = "--format-order", paramLabel = "LIST",
            description = "formats to ask for in order, most wanted first, comma separated "
                    + "(e.g. mp3,flac,wav). Each is a separate pass over what is still "
                    + "missing, with that format required; a last pass takes anything. "
                    + "Empty means one pass and no ordering")
    String formatOrder;

    @Option(names = "--replace-if-better",
            description = "search every track again, on-disk ones included, and keep the new "
                    + "copy only when it is better - lossless over lossy, or the same format "
                    + "at a bigger size (config: track.replace_if_better)")
    boolean replaceIfBetter;

    @Option(names = "--no-cache",
            description = "ignore cache.json completely for this run: search everything and "
                    + "write nothing down, as if the file did not exist")
    boolean noCache;

    @Option(names = "--dry-run",
            description = "write the list file, print the sockseek command, run nothing")
    boolean dryRun;

    @Option(names = "--preview",
            description = "print search results instead of downloading")
    boolean preview;

    /**
     * The old name for {@code --why} over everything still missing. Kept because it is in
     * the owner's fingers and in every note written before 2026-10-18.
     */
    @Option(names = "--explain", hidden = true,
            description = "the old name for --why over every missing track")
    boolean explain;

    /**
     * Kept because it is in everyone's fingers and in the old notes. Explaining is off by
     * default since 2026-10-17, so this now only forces off what is already off - but a flag
     * that used to work must not start failing the command line.
     */
    @Option(names = "--no-explain", hidden = true,
            description = "no longer needed: explaining is off unless --explain is given")
    boolean noExplain;

    @Option(names = "--verbose",
            description = "sockseek --verbose plus --log-file logs/sockseek_<run>_<name>.log")
    boolean verbose;

    @Option(names = "--config", paramLabel = "FILE", description = "settings file")
    Path config;

    @Option(names = "--sockseek-conf", paramLabel = "FILE",
            description = "one more layer on top of the sockseek block of config.yaml, in "
                    + "sockseek own key = value syntax, as few keys as you like")
    Path sockseekConf;

    @Option(names = "--sockseek-args", paramLabel = "STRING",
            description = "extra sockseek arguments as one quoted string")
    String sockseekArgs = "";

    private Path appDir = Path.of(".").toAbsolutePath().normalize();
    private Config loaded = Config.empty();
    private final List<String> early = new ArrayList<>();
    private final Map<String, String> sources = new LinkedHashMap<>();
    private final Map<String, Object> values = new LinkedHashMap<>();
    private ParseResult parsed;

    public Cli appDir(Path dir) {
        this.appDir = dir;
        return this;
    }

    @Override
    public Integer call() {
        return RunBatch.run(runConfig(), describeConfig(), List.copyOf(early), System.out::println,
                null);
    }

    /** Resolve the config file first: everything else is read on top of it. */
    public void resolve(ParseResult result) {
        this.parsed = result;
        Path file = config != null ? config : appDir.resolve(Settings.CONFIG_NAME);
        loaded = Settings.loadConfig(file, early::add);
        sources.put("config", config != null ? "cli" : "default");
        values.put("config", file);

        Map<String, Path> folders = Settings.configPaths(loaded, appDir);
        record Folder(String flag, String key, Path given) {
        }
        for (Folder folder : List.of(new Folder("playlists", "playlists_dir", playlists),
                new Folder("music", "music_dir", output),
                new Folder("work", "work_dir", work),
                new Folder("logs", "logs_dir", logs),
                new Folder("sockseek_bin", "sockseek_bin", sockseek))) {
            if (folder.given() != null) {
                values.put(folder.flag(), folder.given());
                sources.put(folder.flag(), "cli");
            } else {
                values.put(folder.flag(), folders.get(folder.flag()));
                sources.put(folder.flag(), loaded.path(folder.flag()).isPresent()
                        ? "config" : "default");
            }
        }
        for (Setting setting : Settings.ALL) {
            Number given = given(setting);
            Number value;
            String source;
            if (given != null) {
                value = given;
                source = "cli";
            } else if (loaded.numbers().containsKey(setting.key())) {
                value = loaded.numbers().get(setting.key());
                source = "config";
            } else {
                value = setting.fallback();
                source = "default";
            }
            Settings.checkValue(setting, value).ifPresent(problem -> {
                throw new CommandLine.ParameterException(new CommandLine(this),
                        problem + " (source: " + sources.getOrDefault(setting.key(), "cli") + ")");
            });
            values.put(setting.key(), value);
            sources.put(setting.key(), source);
        }
    }

    private Number given(Setting setting) {
        return switch (setting.key()) {
            case "max_length_diff_s" -> lengthTol;
            case "attempt_timeout_min" -> playlistTimeout;
            case "attempts" -> retries;
            case "stall_timeout_s" -> stallTimeout;
            case "skip_absent_h" -> skipNotFoundHours;
            case "bad_uploader_h" -> badUploaderHours;
            case "searches_per_window" -> searchesPerWindow;
            case "window_s" -> searchesRenewS;
            case "attempts_per_pass" -> attemptsPerPass;
            // a flag that is only ever "on": absent means "whatever the file says"
            case "replace_if_better" -> replaceIfBetter ? 1 : null;
            // a flag that is only ever "off": absent means "whatever the file says"
            case "enabled" -> noCache ? 0 : null;
            default -> null;
        };
    }

    int integer(String key) {
        return ((Number) values.get(key)).intValue();
    }

    double decimal(String key) {
        return ((Number) values.get(key)).doubleValue();
    }

    Path path(String key) {
        return (Path) values.get(key);
    }

    /**
     * The wanted formats, in order; the command line wins over the file.
     *
     * <p>A list, so it cannot live in {@link Settings#ALL} with the numbers. Blank entries
     * are dropped rather than passed on as an empty {@code --format}, which sockseek would
     * read as "no format at all passes".
     */
    public List<String> formatOrder() {
        return Settings.ladder(loaded, formatOrder);
    }

    /**
     * {@code --verbose} as an ordinary argument to sockseek.
     *
     * <p>It used to ride along with the log file, which meant the log could only exist when
     * the person asked for noise. The log is now written every run - it is where the login
     * result and the candidate counts are read from - so the two had to come apart.
     */
    private List<String> withVerbose(List<String> given) {
        if (!verbose || given.contains("--verbose")) {
            return given;
        }
        List<String> all = new java.util.ArrayList<>(given);
        all.add("--verbose");
        return List.copyOf(all);
    }

    public List<String> splitSockseekArgs() {
        // split on spaces, honour quotes, and keep backslashes as they are: a Windows path
        // inside this string is a path, not an escape sequence
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char quote = 0;
        for (char character : sockseekArgs.toCharArray()) {
            if (inQuotes) {
                if (character == quote) {
                    inQuotes = false;
                } else {
                    current.append(character);
                }
            } else if (character == '"' || character == '\'') {
                inQuotes = true;
                quote = character;
            } else if (Character.isWhitespace(character)) {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(character);
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return List.copyOf(parts);
    }

    public RunConfig runConfig() {
        return RunConfig.builder(appDir)
                .playlistsDir(path("playlists"))
                .outputDir(path("music"))
                .workDir(path("work"))
                .logsDir(path("logs"))
                .sockseek(path("sockseek_bin"))
                .options(new Options(integer("max_length_diff_s"), preview,
                        integer("attempts_per_pass"), null, -1))
                .timeoutMinutes(decimal("attempt_timeout_min"))
                .retries(integer("attempts"))
                .dryRun(dryRun)
                .only(only)
                .verbose(verbose)
                .why(why != null ? why : explain && !noExplain ? "" : null)
                .sockseekArgs(withVerbose(splitSockseekArgs()))
                .sockseekConf(sockseekConf)
                .stallSeconds(decimal("stall_timeout_s"))
                .skipNotFoundHours(decimal("skip_absent_h"))
                .badUploaderHours(decimal("bad_uploader_h"))
                .cache(integer("enabled") != 0)
                .formatOrder(formatOrder())
                .rateLimit(integer("searches_per_window"), integer("window_s"))
                .replaceIfBetter(integer("replace_if_better") != 0)
                .build();
    }

    /** Effective configuration, one key per line: name = value [source]. */
    public List<String> describeConfig() {
        List<String> lines = new ArrayList<>();
        lines.add("effective configuration of sockseek-cli " + VERSION
                + " (key = value [source: cli | config | default]):");
        lines.add("  paths.playlists = " + path("playlists")
                + " [" + sources.get("playlists") + "]");
        lines.add("  paths.music = " + path("music") + " [" + sources.get("music") + "]");
        lines.add("  paths.work = " + path("work") + " [" + sources.get("work") + "]");
        lines.add("  paths.logs = " + path("logs") + " [" + sources.get("logs") + "]");
        if (!formatOrder().isEmpty()) {
            lines.add("  track.formats = " + String.join(",", formatOrder())
                    + " [" + (formatOrder != null ? "cli" : "config") + "]");
        }
        lines.add("  paths.sockseek_bin = " + path("sockseek_bin")
                + " [" + sources.get("sockseek_bin") + "]");
        lines.add("  config_file = " + path("config") + " [" + sources.get("config") + "]");
        for (Setting setting : Settings.ALL) {
            Object value = values.get(setting.key());
            String shown = setting.kind() == Setting.Kind.BOOLEAN
                    ? trueFalse(((Number) value).intValue() != 0) : plain(value);
            lines.add("  " + setting.fullName() + " = " + shown
                    + " [" + sources.get(setting.key()) + "]");
        }
        record Extra(String key, Object value) {
        }
        for (Extra extra : List.of(new Extra("only", only),
                new Extra("why", why != null ? why : explain && !noExplain ? "" : null),
                new Extra("dry_run", dryRun ? Boolean.TRUE : null),
                new Extra("preview", preview ? Boolean.TRUE : null),
                new Extra("verbose", verbose ? Boolean.TRUE : null),

                new Extra("sockseek_conf", sockseekConf),
                new Extra("sockseek_args", sockseekArgs.isEmpty() ? null : sockseekArgs))) {
            if (extra.value() != null) {
                lines.add("  " + extra.key() + " = " + trueFalse(extra.value()) + " [cli]");
            }
        }
        return List.copyOf(lines);
    }

    /** True and False with a capital, as every other line of the block has always had. */
    private static String trueFalse(Object value) {
        if (value instanceof Boolean flag) {
            return flag ? "True" : "False";
        }
        return String.valueOf(value);
    }

    /** 45, 40, -1 - never 40.0, which is what Java prints for a double by default. */
    private static String plain(Object value) {
        if (value instanceof Double number) {
            return number == Math.rint(number)
                    ? Long.toString(number.longValue())
                    : BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }

    /**
     * The engine on its own, without the menu in front of it.
     *
     * <p>The everyday entry point is {@link dev.litetook.sockseek.App}; this one exists so the
     * engine's own options can be read and a run can be started without the menu:
     * {@code java -cp sockseek-cli.jar dev.litetook.sockseek.cli.Cli --help}.
     */
    public static void main(String[] args) {
        System.exit(main(List.of(args), Path.of(".").toAbsolutePath().normalize(), System.err));
    }

    /** Parse, resolve and run. Returns the exit code; bad values exit with 2, as argparse does. */
    public static int main(List<String> argv, Path appDir, PrintStream err) {
        Cli cli = new Cli().appDir(appDir);
        CommandLine line = new CommandLine(cli);
        line.getCommandSpec().version("sockseek-cli " + VERSION);
        try {
            ParseResult result = line.parseArgs(argv.toArray(String[]::new));
            if (CommandLine.printHelpIfRequested(result)) {
                return 0;
            }
            cli.resolve(result);
        } catch (CommandLine.ParameterException badValue) {
            err.println(badValue.getMessage());
            return 2;
        }
        return cli.call();
    }
}
