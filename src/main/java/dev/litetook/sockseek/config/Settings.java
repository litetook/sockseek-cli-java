package dev.litetook.sockseek.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Settings of the tool itself: config.yaml, the folders, the tunable numbers, and the three
 * sockseek config layers.
 *
 * <p>Everything sockseek owns lives in sockseek.conf instead. Neither file carries comments:
 * the explanations are in the README, the files are a list of names and values.
 */
public final class Settings {

    public static final String CONFIG_NAME = "config.yaml";
    /**
     * The one file that is never committed: the login, and anything else this machine wants
     * to override. It is not created by the tool - a file full of placeholders is a file that
     * looks filled in. Replaces sockseek.local.conf, by the owner's decision of 2026-10-18.
     */
    public static final String LOCAL_CONFIG_NAME = "config.local.yaml";
    public static final String SOCKSEEK_EFFECTIVE_CONF = "sockseek.effective.conf";

    /** The section of config.yaml that is sockseek's own, passed on without being read. */
    public static final String SOCKSEEK_SECTION = "sockseek";

    public static final List<String> CREDENTIAL_KEYS = List.of("username", "password");
    /**
     * Formats to ask for in order, most wanted first; empty means one pass and no ordering.
     *
     * <p>A list, so it does not belong among the numbers in {@link #ALL}; it travels with
     * the other text values instead.
     */
    public static final String FORMAT_ORDER = "format_order";

    public static final List<String> PATH_KEYS =
            List.of("sockseek_bin", "playlists", "music", "work", "logs");
    /** What those folders were called before 2026-10-18; still read, with a warning. */
    private static final Map<String, String> PATH_OLD_KEYS =
            Map.of("sockseek", "sockseek_bin", "output", "music");
    private static final Map<String, String> PATH_DEFAULTS = Map.of(
            "sockseek_bin", "sockseek.exe", "playlists", "Playlists", "music", "output",
            "work", "work", "logs", "logs");

    /**
     * The settings file, written when it is missing. One file since 2026-10-18: the tool's own
     * settings and sockseek's, in sections, by the owner's decision ("зведи це все в один ямл
     * файл"). Before that sockseek.conf stood beside it, and the reason it could stand apart -
     * "those keys are sockseek's, not ours" - is now a section instead of a file.
     *
     * <p>It carries a comment per key since 2026-10-17. Until then it was a bare list of
     * names and values and the manual did the explaining, by the owner's call of 2026-10-11 -
     * he reversed it on seeing the file: a setting you have to leave the file to understand
     * is a setting you will not touch. The comments say what the key IS; the manual still has
     * the why and the measurements behind each default.
     *
     * <p>The login is NOT here and no placeholder for it is written anywhere: it goes in
     * config.local.yaml, which the owner writes himself and git never sees.
     */
    public static final String CONFIG_TEMPLATE = """
            # Sections say what a value affects, and the full name of a setting is
            # section.key - playlist.attempts, track.max_sources. A key found under the
            # wrong section still works and is named in a warning; an unknown key is named
            # and ignored. Everything under "sockseek:" is sockseek's own and is passed to
            # it untouched.
            #
            # The Soulseek login does not live here. Put it in config.local.yaml, which is
            # git-ignored and overrides any key below:
            #
            #     sockseek:
            #       username: your-login
            #       password: your-password

            # ======================================================================
            # WHERE THINGS ARE
            # ======================================================================
            paths:
              # the sockseek program; .exe is added or dropped to match what is there
              sockseek_bin: sockseek.exe
              # input: one playlist .txt per playlist
              playlists: Playlists
              # output: the audio, one sub-folder per playlist
              music: output
              # scratch: generated list files and the merged sockseek config, rebuilt every
              # run and safe to delete - but it holds the login while a run is going
              work: work
              # run logs, session logs and the run counter
              logs: logs

            # ======================================================================
            # THE BRAKE - all playlists together, because the server counts per account
            # ======================================================================
            rate_limit:
              # more searches than this in one window and the server bans the account for
              # half an hour. sockseek counts inside ONE process and a run is many of them,
              # so this is the only count that matches what the server sees; 0 = no pause
              searches_per_window: 34
              # the length of that window, in seconds
              window_s: 220

            # ======================================================================
            # ONE PLAYLIST - when to give up on it
            # ======================================================================
            playlist:
              # how many goes at one playlist; 1 means one go and no repeat. Each is a
              # fresh sockseek
              attempts: 1
              # kill ONE go after this many minutes, 0 = never. Two attempts can take twice
              # this long
              attempt_timeout_min: 60
              # kill a go when nothing has finished AND nothing has arrived on disk for this
              # many seconds, 0 = never
              stall_timeout_s: 300

            # ======================================================================
            # ONE TRACK - which file is the right one, and how hard to try
            # ======================================================================
            track:
              # Formats you want, most wanted first. Each is a separate pass over whatever
              # is still missing with that format REQUIRED, and a last pass takes anything
              # else. This is the only way to order them: sockseek's own pref-format is
              # unordered by its own documentation, so "flac,mp3" there means "both are
              # fine", not "flac first".
              #
              # It costs searches: a track nobody has goes through every pass, so three
              # formats mean four searches for it instead of one - until the cache has
              # confirmed it absent and stops looking. Empty means one pass, no ordering.
              #   format_order: mp3,flac,wav
              format_order:
              # HARD condition and the only one we own: a shared file whose length differs
              # from the playlist by more than this many seconds is refused outright.
              # -1 turns it off. A refused track looks exactly like a missing one
              max_length_diff_s: 45
              # how many different uploaders to try for one track before giving up this run
              max_sources: 3

            # ======================================================================
            # MEMORY BETWEEN RUNS - outcomes.tsv
            # ======================================================================
            cache:
              # remember how each track ended; false ignores the file entirely - searches
              # everything and writes nothing down
              enabled: true
              # do not search again for a track confirmed absent - nobody on the network had
              # it - if that was within this many hours; 0 = always search. Fractions are
              # fine. Only a confirmed absence counts: a track refused by a hard condition
              # is written down WITH that condition and is never skipped, so turning the
              # condition off makes the tool ask about it again at once
              skip_absent_h: 2

            # ======================================================================
            # SOCKSEEK - handed to sockseek untouched. The key names are ITS, not ours,
            # which is why they carry dashes and no units. `sockseek --help` is the full
            # list. The sub-blocks are ours, for reading only: they are flattened away on
            # the way out, and a line pasted in sockseek's own "key = value" form is read
            # just as well as "key: value".
            # ======================================================================
            sockseek:

              # -- REFUSE: a file failing any of these is not downloaded AT ALL ------
              # all off on purpose: a refusal is indistinguishable from "nobody had it",
              # and min-bitrate = 256 refused every mp3 at 192 or 224 - a large part of
              # what Soulseek actually has, and 104 of 135 failures on a real run
              refuse:
                # min-bitrate: 256       # worse than this -> never, even if nothing else exists
                # max-bitrate: 1411      # better than this -> never (1411 kbps = CD lossless)
                # max-samplerate: 48000  # above 48 kHz -> never
                # max-bitdepth: 16       # 24-bit -> never
                # format: mp3,flac       # the only formats allowed at all - not an order

              # -- PREFER: the same ideas as an ORDER. Nothing here ever refuses -----
              prefer:
                # 320+ comes first; worse still downloads when that is all there is
                pref-min-bitrate: 320
                # above CD lossless comes last: 24-bit and hi-res cost a lot of bytes for
                # little gain, and still arrive when there is nothing cheaper
                pref-max-bitrate: 1411
                # flac and mp3 equally; wav is unlisted, so it comes last
                pref-format: flac,mp3
                # files whose path contains the track title come first
                pref-strict-title: true
                # and the same for the artist
                pref-strict-artist: true

              # -- PICK A SOURCE: how long to shop around before committing ----------
              pick_source:
                # take the first candidate that fits instead of waiting for the whole
                # search to end
                fast-search: true
                # milliseconds spent collecting candidates first, so "the first" is not
                # simply the only one
                fast-search-delay: 2000
                # the speed a candidate needs to qualify for that shortcut; a slower one
                # stays in the normal queue. The unit is undocumented and unproven -
                # sockseek's own default is 1
                fast-search-min-up-speed: 200

              # -- DROP A SOURCE: when to stop waiting on one that is not delivering --
              drop_source:
                # milliseconds connected with no bytes arriving before this candidate is
                # dropped and the next uploader is tried
                max-stale-time: 12000
                # one failure pushes an uploader down the ranking
                fails-to-downrank: 1
                # two failures and it is left out for the rest of the run
                fails-to-ignore: 2
                # how many DIFFERENT uploaders a track is offered to is track.max_sources
                # above: sockseek calls that max-retries, which counts something else
            """;

    public static final List<Setting> ALL = List.of(
            // ---- one track ----
            new Setting("track", "max_length_diff_s", "--max-length-diff",
                    Setting.Kind.INTEGER, 45, -1.0,
                    "refuse a shared file whose length differs from the playlist by more than "
                            + "this many seconds; HARD condition, -1 disables (default 45)",
                    List.of("tol", "length_tol_s")),
            new Setting("track", "max_sources", "--max-sources", Setting.Kind.INTEGER, 3, 1.0,
                    "how many different uploaders to try for one track before giving up on "
                            + "it this run (default 3)", List.of()),
            // ---- one playlist ----
            // 60 and 1, the same numbers the shipped config.yaml carries. They used to be
            // 40 and 2, so a deleted config file quietly changed how a run behaved and the
            // help text named a default the file disagreed with - one number is enough.
            new Setting("playlist", "attempt_timeout_min", "--attempt-timeout",
                    Setting.Kind.DECIMAL, 60.0, 0.0,
                    "wall-clock limit for ONE attempt at one playlist, in minutes, 0 = none "
                            + "(default 60)", List.of("timeout", "playlist_timeout_min")),
            // "retries" counted attempts, so retries = 1 meant one go and no repeat at all.
            // Renamed 2026-10-18 for the same reason max-retries became max_sources: a name
            // that counts something else is a name that will be set to the wrong number.
            new Setting("playlist", "attempts", "--attempts", Setting.Kind.INTEGER, 1, 1.0,
                    "goes at one playlist, at least 1; 1 means one go and no repeat "
                            + "(default 1)", List.of("retries")),
            new Setting("playlist", "stall_timeout_s", "--stall-timeout", Setting.Kind.DECIMAL,
                    300.0, 0.0,
                    "stop an attempt when nothing has finished and nothing has arrived on "
                            + "disk for this many seconds, 0 = never (default 300)", List.of()),
            // ---- the cache ----
            // "not found" was the very word that meant two things, which is what the
            // confirmed-absent class exists to undo; the key no longer repeats it.
            new Setting("cache", "skip_absent_h", "--skip-absent", Setting.Kind.DECIMAL, 2.0,
                    0.0, "do not search again for a track confirmed absent from the network, "
                            + "if that was within this many hours; 0 = always search "
                            + "(default 2). Fractions are fine: 0.5 is half an hour",
                    List.of("skip_not_found_hours")),
            new Setting("cache", "enabled", "--no-cache", Setting.Kind.BOOLEAN, 1, 0.0,
                    "remember what happened to a track and act on it; false ignores "
                            + "outcomes.tsv completely - searches everything and writes "
                            + "nothing down (default true)", List.of("use_cache")),
            // ---- the whole run ----
            new Setting("rate_limit", "searches_per_window", "--searches-per-window",
                    Setting.Kind.INTEGER, 34, 0.0,
                    "keep the searches of ALL playlists together under this many per "
                            + "rate_limit.window_s, by pausing between playlists; 0 = no "
                            + "pause (default 34)", List.of()),
            new Setting("rate_limit", "window_s", "--searches-window", Setting.Kind.INTEGER,
                    220, 1.0, "the window those searches are counted in, in seconds "
                            + "(default 220)", List.of("searches_renew_s")));

    private Settings() {
    }

    public static Optional<Setting> byKey(String key) {
        return ALL.stream()
                .filter(setting -> setting.key().equals(key) || setting.oldKeys().contains(key))
                .findFirst();
    }

    // ------------------------------------------------------------------ config.yaml

    /**
     * {@code section.key -> value}, one entry per setting in the file.
     *
     * <p>Deliberately not a YAML implementation: the config is a list of names and values in
     * named blocks. A line that opens a block ends in a colon and carries no value; a line
     * indented under it belongs to that block, and the name it comes back under is
     * {@code block.key}. Only the OUTERMOST block names a key, so the reading aids inside the
     * sockseek block - refuse, prefer, pick_source, drop_source - flatten away, and
     * {@code sockseek.min-bitrate} is what comes out of all of them.
     *
     * <p>Inside the sockseek block a line may also be written sockseek's own way,
     * {@code key = value}, so a line pasted out of its help works without translation.
     *
     * <p>A line that looks like a setting but yields no pair is reported with its number -
     * dropping one silently is how a setting goes missing while the log still claims the
     * default was chosen on purpose.
     */
    public static Map<String, String> parseYaml(String text, Consumer<String> warn) {
        Map<String, String> values = new LinkedHashMap<>();
        String block = "";          // the outermost block, "" at the top level
        int blockIndent = -1;       // the indent that opened it; deeper blocks are read aids
        int number = 0;
        for (String raw : text.lines().toList()) {
            number++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int indent = raw.length() - raw.stripLeading().length();
            if (indent <= blockIndent) {
                block = "";
                blockIndent = -1;
            }
            // split on the first ": ", not the first ":", or a value like a Windows path would
            // lose everything after its own colon
            int separator = line.indexOf(": ");
            // "key = value" as sockseek writes it, so a line pasted out of its help works
            int equals = block.equals(SOCKSEEK_SECTION) ? line.indexOf('=') : -1;
            boolean pair = separator >= 0 || equals > 0;
            String head = separator >= 0 ? line.substring(0, separator)
                    : equals > 0 ? line.substring(0, equals) : trimTrailingColons(line);
            String given = separator >= 0 ? line.substring(separator + 2).strip()
                    : equals > 0 ? line.substring(equals + 1).strip() : "";
            String value = pair ? scalar(given) : "";
            String key = head.strip();
            if (!pair && line.endsWith(":") && !key.isEmpty()) {
                if (blockIndent < 0) {               // an outermost block: it names the keys
                    block = key;
                    blockIndent = indent;
                }
                continue;                            // a deeper one is a reading aid only
            }
            if (!value.isEmpty() && !value.equals(given) && given.indexOf('#') >= 0
                    && warn != null) {
                // a folder really can be called Rock#1, and cutting it down to Rock without a
                // word ended in "No playlist .txt files in ...\Rock" with no hint why (found
                // by an independent review, 2026-10-16)
                warn.accept("line " + number + ": '" + given + "' was read as '" + value
                        + "' - everything after # is taken as a comment; put the value in "
                        + "quotes if the # belongs to it");
            }
            if (!key.isEmpty() && !value.isEmpty()) {
                values.put(block.isEmpty() ? key : block + "." + key, value);
            } else if (warn != null) {
                warn.accept("line " + number + " is not a 'key: value' pair, ignored: '"
                        + line + "'");
            }
        }
        return values;
    }

    /** The block a full name belongs to, {@code ""} for a name written without one. */
    public static String sectionOf(String fullName) {
        int dot = fullName.indexOf('.');
        return dot < 0 ? "" : fullName.substring(0, dot);
    }

    /** The key itself, whether or not the name carries its block. */
    public static String leafOf(String fullName) {
        return fullName.substring(fullName.indexOf('.') + 1);
    }

    private static String trimTrailingColons(String line) {
        int end = line.length();
        while (end > 0 && line.charAt(end - 1) == ':') {
            end--;
        }
        return line.substring(0, end);
    }

    /** One scalar: quotes stripped, and a trailing {@code # comment} removed only OUTSIDE them. */
    private static String scalar(String text) {
        String value = text.strip();
        for (char quote : new char[] {'"', '\''}) {
            if (!value.isEmpty() && value.charAt(0) == quote) {
                int closing = value.indexOf(quote, 1);
                if (closing > 0) {
                    return value.substring(1, closing);
                }
            }
        }
        int hash = value.indexOf('#');
        return (hash >= 0 ? value.substring(0, hash) : value).strip();
    }

    /** A message when the value is outside the legal range, else empty. */
    public static Optional<String> checkValue(Setting setting, Number value) {
        if (setting.minimum() != null && value.doubleValue() < setting.minimum()) {
            return Optional.of(setting.key() + " = " + plain(value) + " is below the minimum "
                    + plain(setting.minimum()));
        }
        return Optional.empty();
    }

    /** 45, -1, 40 - never 40.000000, which is what a plain %g would print. */
    private static String plain(Number value) {
        double number = value.doubleValue();
        if (number == Math.rint(number) && Math.abs(number) < 1e15) {
            return Long.toString((long) number);
        }
        return BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
    }

    /**
     * config.yaml -> the values that survived checking. A missing file is written from the
     * template and read back, so the very first run already uses exactly what is in the file.
     *
     * <p>Invalid, out-of-range and unknown keys are named through {@code warn} and dropped, so
     * a typo never silently changes a run.
     */
    public static Config loadConfig(Path path, Consumer<String> warn) {
        Consumer<String> say = warn == null ? message -> { } : warn;
        if (!Files.exists(path)) {
            return create(path, say);
        }
        Config base = read(path, say);
        Path local = path.resolveSibling(LOCAL_CONFIG_NAME);
        return Files.exists(local) ? over(base, read(local, say)) : base;
    }

    /** One file, checked. {@link #loadConfig} is this plus the local layer on top. */
    private static Config read(Path path, Consumer<String> say) {
        String text;
        try {
            text = readUtf8(path);
        } catch (IOException cannotRead) {
            say.accept("WARN: cannot read " + path.getFileName() + ": " + cannotRead.getMessage());
            return Config.empty();
        }
        String name = path.getFileName().toString();
        Map<String, String> raw = parseYaml(text,
                message -> say.accept("WARN: " + name + ": " + message));
        Map<String, String> paths = new LinkedHashMap<>();
        Map<String, Number> numbers = new LinkedHashMap<>();
        Map<String, String> words = new LinkedHashMap<>();
        Map<String, String> sockseek = new LinkedHashMap<>();
        for (Map.Entry<String, String> pair : raw.entrySet()) {
            String full = pair.getKey();
            String section = sectionOf(full);
            String key = leafOf(full);
            // sockseek's own block: not ours to understand, so not ours to check either
            if (section.equals(SOCKSEEK_SECTION)) {
                sockseek.put(key, pair.getValue());
                continue;
            }
            String folder = PATH_OLD_KEYS.get(key);
            if (folder != null) {
                say.accept("WARN: " + name + ": key '" + key + "' is deprecated, use '"
                        + folder + "' (same value)");
                key = folder;
            }
            if (PATH_KEYS.contains(key)) {
                paths.put(key, pair.getValue());
                continue;
            }
            if (key.equals(FORMAT_ORDER)) {
                words.put(FORMAT_ORDER, pair.getValue());
                continue;
            }
            Optional<Setting> found = byKey(key);
            if (found.isEmpty()) {
                say.accept("WARN: " + name + ": unknown key '" + full + "', ignored");
                continue;
            }
            Setting setting = found.get();
            if (!key.equals(setting.key())) {
                say.accept("WARN: " + name + ": key '" + key + "' is deprecated, use '"
                        + setting.fullName() + "' (same value, unit in the name)");
            } else if (!section.isEmpty() && !section.equals(setting.section())) {
                // honoured anyway: the blocks group the file, they do not decide anything.
                // Saying so is how a key that drifted into the wrong block gets noticed
                say.accept("WARN: " + name + ": '" + full + "' belongs under "
                        + setting.section() + ", read as '" + setting.fullName() + "'");
            }
            Optional<Number> value = setting.kind().parse(pair.getValue());
            if (value.isEmpty()) {
                say.accept("WARN: " + name + ": invalid value for '" + full
                        + "', using the default");
                continue;
            }
            Optional<String> problem = checkValue(setting, value.get());
            if (problem.isPresent()) {
                say.accept("WARN: " + name + ": " + problem.get() + ", ignored (using the "
                        + "default)");
                continue;
            }
            numbers.put(setting.key(), value.get());
        }
        return new Config(paths, numbers, words, sockseek);
    }

    /** The local layer on top of the committed one: a key it names wins, the rest stays. */
    private static Config over(Config base, Config local) {
        Map<String, String> paths = new LinkedHashMap<>(base.paths());
        paths.putAll(local.paths());
        Map<String, Number> numbers = new LinkedHashMap<>(base.numbers());
        numbers.putAll(local.numbers());
        Map<String, String> words = new LinkedHashMap<>(base.text());
        words.putAll(local.text());
        Map<String, String> sockseek = new LinkedHashMap<>(base.sockseek());
        sockseek.putAll(local.sockseek());
        return new Config(paths, numbers, words, sockseek);
    }

    private static Config create(Path path, Consumer<String> say) {
        try {
            Files.writeString(path, CONFIG_TEMPLATE, StandardCharsets.UTF_8);
        } catch (IOException cannotWrite) {
            return Config.empty();
        }
        return loadConfig(path, say);   // one code path produces the checked values
    }

    private static String readUtf8(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        return text.isEmpty() || text.charAt(0) != '﻿' ? text : text.substring(1);
    }

    /**
     * The three folders: the config value or the built-in default, relative to {@code appDir}.
     * An absolute value is taken as it is.
     */
    /**
     * One folder out of config.yaml, read cheaply and silently.
     *
     * <p>For the one caller that needs an answer before the configuration has been loaded
     * and reported: the session log is opened on the first line printed, which is earlier
     * than the first warning. Reading the file twice is better than a log that cannot
     * follow the setting that says where logs go.
     */
    public static Path folder(Path appDir, String key) {
        try {
            Map<String, String> values = parseYaml(readUtf8(appDir.resolve(CONFIG_NAME)), null);
            // "paths.logs" is how the file names it now; the bare "logs" of the flat file
            // before 2026-10-18 still answers, because an old config.yaml must keep working
            String given = values.getOrDefault("paths." + key, values.get(key));
            if (given != null && !given.isBlank()) {
                Path path = Path.of(given.strip());
                return path.isAbsolute() ? path : appDir.resolve(path);
            }
        } catch (IOException | RuntimeException noFile) {
            // the very first run: the file is not there yet
        }
        return appDir.resolve(PATH_DEFAULTS.getOrDefault(key, key));
    }

    public static Map<String, Path> configPaths(Config config, Path appDir) {
        Map<String, Path> found = new LinkedHashMap<>();
        for (String key : PATH_KEYS) {
            String value = config.path(key).filter(text -> !text.isBlank())
                    .orElse(PATH_DEFAULTS.get(key));
            Path path = Path.of(value);
            found.put(key, path.isAbsolute() ? path : appDir.resolve(path));
        }
        found.put("sockseek_bin", eitherSuffix(found.get("sockseek_bin")));
        return found;
    }

    /**
     * {@code sockseek.exe} on Windows, {@code sockseek} elsewhere: whichever of the two is
     * really there, so one config file works on both.
     */
    private static Path eitherSuffix(Path exe) {
        if (Files.isRegularFile(exe)) {
            return exe;
        }
        String name = exe.getFileName().toString();
        Path other = exe.resolveSibling(name.endsWith(".exe")
                ? name.substring(0, name.length() - 4) : name + ".exe");
        return Files.isRegularFile(other) ? other : exe;
    }

    // ------------------------------------------------------------------ sockseek.conf

    /**
     * Top-level {@code key = value} pairs of a sockseek config. A {@code [profile]} section ends
     * the scan: keys inside one do not apply to a plain run.
     */
    public static Map<String, String> parseConf(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String raw : text.lines().toList()) {
            String line = raw.strip();
            if (line.startsWith("[")) {
                break;
            }
            int equals = line.indexOf('=');
            if (line.startsWith("#") || equals < 0) {
                continue;
            }
            values.put(line.substring(0, equals).strip(), line.substring(equals + 1).strip());
        }
        return values;
    }

    /**
     * sockseek reads exactly one config file and knows no includes, so the layers are merged
     * here: a key present in the override replaces the one in the base, in place, and the rest
     * is added. Everything from the first {@code [profile]} section on is copied untouched.
     */
    public static String mergeConf(String base, String override) {
        Map<String, String> extra = parseConf(override);
        Set<String> used = new HashSet<>();
        List<String> out = new ArrayList<>();
        List<String> tail = new ArrayList<>();   // from the first [profile] on
        for (String raw : base.lines().toList()) {
            String line = raw.strip();
            if (!tail.isEmpty() || line.startsWith("[")) {
                tail.add(raw);
                continue;
            }
            int equals = line.indexOf('=');
            if (!line.startsWith("#") && equals >= 0) {
                String key = line.substring(0, equals).strip();
                if (extra.containsKey(key)) {
                    out.add(key + " = " + extra.get(key));
                    used.add(key);
                    continue;
                }
            }
            out.add(raw);
        }
        List<String> rest = extra.entrySet().stream()
                .filter(pair -> !used.contains(pair.getKey()))
                .map(pair -> pair.getKey() + " = " + pair.getValue())
                .toList();
        if (!rest.isEmpty()) {
            // BEFORE the profiles, not after: a key added past the first [section] would belong
            // to that profile, and the parser stops at "[" - that is how a correct
            // sockseek.local.conf could read as "no login"
            out.add("");
            out.addAll(rest);
        }
        List<String> all = new ArrayList<>(out);
        if (!tail.isEmpty()) {
            all.add("");
            all.addAll(tail);
        }
        return String.join("\n", all).stripTrailing() + "\n";
    }

    /**
     * Which login keys are absent or still the template placeholder. Values are never returned
     * or logged - only the names of the keys.
     */
    public static List<String> missingCredentials(String text) {
        Map<String, String> values = parseConf(text);
        return CREDENTIAL_KEYS.stream()
                .filter(key -> {
                    String value = values.get(key);
                    return value == null || value.isBlank();
                })
                .toList();
    }

    /**
     * Whether this folder still has no usable Soulseek login.
     *
     * <p>True both when config.local.yaml is not there at all and when it is there without
     * the two keys - somebody half-edited it, and being asked beats a run that gets as far
     * as sockseek and dies on INVALIDPASS.
     */
    public static boolean missingLogin(Path appDir) {
        Config config = loadConfig(appDir.resolve(CONFIG_NAME), null);
        return !missingCredentials(sockseekConf(config.sockseek())).isEmpty();
    }

    /**
     * Write the login into config.local.yaml, keeping whatever else that file holds.
     *
     * <p>The values are never logged or returned anywhere; this is the only place they are
     * written, and the file is git-ignored.
     */
    public static void writeLogin(Path appDir, String username, String password)
            throws IOException {
        Path file = appDir.resolve(LOCAL_CONFIG_NAME);
        List<String> kept = new ArrayList<>();
        if (Files.exists(file)) {
            for (String raw : readUtf8(file).lines().toList()) {
                String line = raw.strip();
                // the two keys are replaced, everything else the person put there stays
                if (line.startsWith("username:") || line.startsWith("password:")
                        || line.equals(SOCKSEEK_SECTION + ":")) {
                    continue;
                }
                kept.add(raw);
            }
        }
        StringBuilder text = new StringBuilder();
        if (kept.isEmpty()) {
            text.append("# Your Soulseek login. Git never sees this file (.gitignore), and "
                    + "it overrides\n");
            text.append("# any key of ").append(CONFIG_NAME)
                    .append(" - so it is also the place for a machine-only tweak.\n\n");
        } else {
            for (String line : kept) {
                text.append(line).append('\n');
            }
            if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') {
                text.append('\n');
            }
        }
        text.append(SOCKSEEK_SECTION).append(":\n");
        text.append("  username: ").append(username).append('\n');
        text.append("  password: ").append(password).append('\n');
        Files.createDirectories(appDir);
        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Create the files a fresh clone needs, and name the ones that were created.
     *
     * <p>Only config.yaml: the login file is deliberately NOT written. A file full of
     * your-login placeholders is a file that looks filled in, and the one check that would
     * catch it - comparing against the placeholders - is a check that breaks the day somebody
     * really is called your-login. The owner asked for it empty and absent on 2026-10-18.
     */
    public static List<String> ensureFiles(Path appDir, Consumer<String> warn) {
        Consumer<String> say = warn == null ? message -> { } : warn;
        Path path = appDir.resolve(CONFIG_NAME);
        if (Files.exists(path)) {
            return List.of();
        }
        try {
            Files.createDirectories(appDir);
            Files.writeString(path, CONFIG_TEMPLATE, StandardCharsets.UTF_8);
        } catch (IOException cannotCreate) {
            say.accept("WARN cannot create " + CONFIG_NAME + ": " + cannotCreate.getMessage());
            return List.of();
        }
        say.accept("WARN created " + CONFIG_NAME + " from the built-in template");
        return List.of(CONFIG_NAME);
    }

    /**
     * The sockseek block as sockseek's own config text: one {@code key = value} per line, in
     * the order the file wrote them.
     */
    public static String sockseekConf(Map<String, String> block) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> pair : block.entrySet()) {
            out.append(pair.getKey()).append(" = ").append(pair.getValue()).append('\n');
        }
        return out.toString();
    }

    /**
     * Write what sockseek is to read into {@code work/} and return that file.
     *
     * <p>sockseek reads exactly one config file and knows no includes, so the layers are
     * merged here, lowest to highest: the sockseek block of config.yaml -> the same block of
     * config.local.yaml, which is where the login is -> the file given with
     * {@code --sockseek-conf}, which is still a sockseek.conf in its own syntax. Each layer
     * may hold as few keys as it likes.
     */
    public static Path effectiveConf(Path appDir, Path workDir, Consumer<String> warn,
                                     Path extra) {
        Consumer<String> say = warn == null ? message -> { } : warn;
        ensureFiles(appDir, say);   // a run started through the engine alone still gets it
        Config config = loadConfig(appDir.resolve(CONFIG_NAME), say);
        String merged = sockseekConf(config.sockseek());
        if (extra != null) {
            merged = mergeConf(merged, textOf(extra, say));
        }
        Path target = workDir.resolve(SOCKSEEK_EFFECTIVE_CONF);
        try {
            Files.createDirectories(workDir);
            Files.writeString(target, merged, StandardCharsets.UTF_8);
        } catch (IOException cannotWrite) {
            say.accept("WARN cannot write " + target.getFileName() + ": "
                    + cannotWrite.getMessage());
        }
        return target;
    }

    private static String textOf(Path path, Consumer<String> say) {
        if (path == null || !Files.isRegularFile(path)) {
            return "";
        }
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException | UncheckedIOException cannotRead) {
            say.accept("WARN cannot read " + path.getFileName() + ": " + cannotRead.getMessage());
            return "";
        }
    }

    /**
     * The keys of a sockseek config that can REFUSE a file outright, as
     * {@code min-bitrate=256}, in a fixed order so two runs under the same settings produce
     * the same string.
     *
     * <p>Everything with a {@code pref-} prefix is left out on purpose: those only rank
     * candidates, so they can never be the reason nothing came back.
     */
    public static final List<String> HARD_KEYS = List.of(
            "min-bitrate", "max-bitrate", "min-samplerate", "max-samplerate",
            "min-bitdepth", "max-bitdepth", "format", "min-length", "max-length");

    /** The same, from the sockseek block of config.yaml rather than the merged file. */
    public static List<String> hardConditions(Map<String, String> block) {
        List<String> on = new ArrayList<>();
        for (String key : HARD_KEYS) {
            String value = block.get(key);
            if (value != null && !value.isBlank()) {
                on.add(key + "=" + value.strip());
            }
        }
        return List.copyOf(on);
    }

    /** Which of {@link #HARD_KEYS} this config actually sets, as {@code key=value}. */
    public static List<String> hardConditions(Path conf) {
        Map<String, String> values;
        try {
            values = parseConf(new String(Files.readAllBytes(conf), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException noFile) {
            return List.of();
        }
        List<String> on = new ArrayList<>();
        for (String key : HARD_KEYS) {
            String value = values.get(key);
            if (value != null && !value.isBlank()) {
                on.add(key + "=" + value.strip());
            }
        }
        return List.copyOf(on);
    }

    /**
     * The top-level {@code min-bitrate} of a sockseek config.
     *
     * <p>That file also holds the Soulseek password, so this one number is the only thing ever
     * read out of it.
     */
    public static OptionalInt readMinBitrate(Path conf) {
        String text;
        try {
            text = new String(Files.readAllBytes(conf), StandardCharsets.UTF_8);
        } catch (IOException noFile) {
            return OptionalInt.empty();
        }
        String value = parseConf(text).get("min-bitrate");
        if (value == null) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(value.strip()));
        } catch (NumberFormatException notANumber) {
            return OptionalInt.empty();
        }
    }
}
