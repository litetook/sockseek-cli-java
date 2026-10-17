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
     * <p>It mirrored the Python tool's 0.0.4 while that one was the oracle the port was
     * diffed against. That ended on 2026-10-17, and so did the borrowed version: this is a
     * tool in its own right now, and 1.0.0 says the thing people actually want to know -
     * that it is finished enough to use, not a preview of something.
     */
    public static final String VERSION = "1.0.0";

    @Option(names = "--version", versionHelp = true, description = "print the version and exit")
    boolean version;

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "this help")
    boolean help;

    @Option(names = "--playlists", paramLabel = "DIR",
            description = "folder with playlist .txt files (config: paths.playlists)")
    Path playlists;

    @Option(names = "--output", paramLabel = "DIR",
            description = "export folder (config: paths.output)")
    Path output;

    @Option(names = "--sockseek", paramLabel = "FILE",
            description = "path to sockseek.exe (config: paths.sockseek)")
    Path sockseek;

    @Option(names = "--only", paramLabel = "NAME",
            description = "process only this playlist (file name without .txt)")
    String only;

    @Option(names = "--length-tol", paramLabel = "SEC",
            description = "length tolerance in seconds around the playlist length; HARD "
                    + "condition, -1 disables (default 45)")
    Integer lengthTol;

    @Option(names = "--playlist-timeout", paramLabel = "MIN",
            description = "wall-clock limit per attempt for one playlist, in minutes, 0 = none "
                    + "(default 40)")
    Double playlistTimeout;

    @Option(names = "--retries", paramLabel = "N",
            description = "attempts per playlist, at least 1 (default 2)")
    Integer retries;

    @Option(names = "--stall-timeout", paramLabel = "SEC",
            description = "stop a playlist when no track has finished for this many seconds, "
                    + "0 = never (default 300)")
    Double stallTimeout;

    @Option(names = "--skip-not-found-hours", paramLabel = "HOURS",
            description = "do not search again for a track nobody had, if that was within this "
                    + "many hours; 0 = always search (default 2). Fractions are fine")
    Double skipNotFoundHours;

    @Option(names = "--searches-per-window", paramLabel = "N",
            description = "keep the searches of ALL playlists together under this many per "
                    + "220 s, by pausing between them; 0 = no pause (default 34)")
    Integer searchesPerWindow;

    @Option(names = "--work", paramLabel = "DIR",
            description = "generated list files and the merged sockseek config "
                    + "(config: paths.work)")
    Path work;

    @Option(names = "--logs", paramLabel = "DIR",
            description = "run logs, session logs and the run counter (config: paths.logs)")
    Path logs;

    @Option(names = "--searches-renew-s", paramLabel = "SEC",
            description = "the window searches are counted in, in seconds; must match "
                    + "sockseek's searches-renew-time (default 220)")
    Integer searchesRenewS;

    @Option(names = "--no-cache",
            description = "ignore outcomes.tsv completely for this run: search everything and "
                    + "write nothing down, as if the file did not exist")
    boolean noCache;

    @Option(names = "--dry-run",
            description = "write the list file, print the sockseek command, run nothing")
    boolean dryRun;

    @Option(names = "--preview",
            description = "print search results instead of downloading")
    boolean preview;

    @Option(names = "--explain",
            description = "after the run, search once more for every failed track and say why "
                    + "it was rejected; it costs one query per failure (off by default)")
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
            description = "one more sockseek.conf layer, same syntax, as few keys as you like")
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
                new Folder("output", "output_dir", output),
                new Folder("work", "work_dir", work),
                new Folder("logs", "logs_dir", logs),
                new Folder("sockseek", "sockseek_exe", sockseek))) {
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
            case "length_tol_s" -> lengthTol;
            case "playlist_timeout_min" -> playlistTimeout;
            case "retries" -> retries;
            case "stall_timeout_s" -> stallTimeout;
            case "skip_not_found_hours" -> skipNotFoundHours;
            case "searches_per_window" -> searchesPerWindow;
            case "searches_renew_s" -> searchesRenewS;
            // a flag that is only ever "off": absent means "whatever the file says"
            case "use_cache" -> noCache ? 0 : null;
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

    public List<String> splitSockseekArgs() {
        // the Python tool splits with shlex(posix=False), which keeps Windows backslashes and
        // leaves the quotes on a token; stripping them here gives the same list
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
                .outputDir(path("output"))
                .workDir(path("work"))
                .logsDir(path("logs"))
                .sockseek(path("sockseek"))
                .options(new Options(integer("length_tol_s"), preview))
                .timeoutMinutes(decimal("playlist_timeout_min"))
                .retries(integer("retries"))
                .dryRun(dryRun)
                .only(only)
                .verbose(verbose)
                .explain(explain && !noExplain)
                .sockseekArgs(splitSockseekArgs())
                .sockseekConf(sockseekConf)
                .stallSeconds(decimal("stall_timeout_s"))
                .skipNotFoundHours(decimal("skip_not_found_hours"))
                .cache(integer("use_cache") != 0)
                .build();
    }

    /** Effective configuration, one key per line: name = value [source]. */
    public List<String> describeConfig() {
        List<String> lines = new ArrayList<>();
        lines.add("effective configuration of sockseek-cli " + VERSION
                + " (key = value [source: cli | config | default]):");
        lines.add("  playlists_dir = " + path("playlists") + " [" + sources.get("playlists") + "]");
        lines.add("  output_dir = " + path("output") + " [" + sources.get("output") + "]");
        lines.add("  work_dir = " + path("work") + " [" + sources.get("work") + "]");
        lines.add("  logs_dir = " + path("logs") + " [" + sources.get("logs") + "]");
        lines.add("  sockseek_exe = " + path("sockseek") + " [" + sources.get("sockseek") + "]");
        lines.add("  config_file = " + path("config") + " [" + sources.get("config") + "]");
        for (Setting setting : Settings.ALL) {
            Object value = values.get(setting.key());
            String shown = setting.kind() == Setting.Kind.BOOLEAN
                    ? python(((Number) value).intValue() != 0) : plain(value);
            lines.add("  " + setting.key() + " = " + shown
                    + " [" + sources.get(setting.key()) + "]");
        }
        record Extra(String key, Object value) {
        }
        for (Extra extra : List.of(new Extra("only", only),
                new Extra("dry_run", dryRun ? Boolean.TRUE : null),
                new Extra("preview", preview ? Boolean.TRUE : null),
                new Extra("verbose", verbose ? Boolean.TRUE : null),
                new Extra("explain", explain && !noExplain ? Boolean.TRUE : null),
                new Extra("sockseek_conf", sockseekConf),
                new Extra("sockseek_args", sockseekArgs.isEmpty() ? null : sockseekArgs))) {
            if (extra.value() != null) {
                lines.add("  " + extra.key() + " = " + python(extra.value()) + " [cli]");
            }
        }
        return List.copyOf(lines);
    }

    /** True and False as Python prints them, so the two logs read the same. */
    private static String python(Object value) {
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
     * The engine on its own, which is what {@code py -m soulseek} is in the Python tool.
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
