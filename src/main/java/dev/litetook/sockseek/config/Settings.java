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
    public static final String SOCKSEEK_CONF = "sockseek.conf";
    public static final String SOCKSEEK_LOCAL_CONF = "sockseek.local.conf";
    public static final String SOCKSEEK_EFFECTIVE_CONF = "sockseek.effective.conf";

    public static final List<String> CREDENTIAL_KEYS = List.of("username", "password");
    /**
     * Formats to ask for in order, most wanted first; empty means one pass and no ordering.
     *
     * <p>A list, so it does not belong among the numbers in {@link #ALL}; it travels with
     * the other text values instead.
     */
    public static final String FORMAT_ORDER = "format_order";

    public static final List<String> PATH_KEYS =
            List.of("sockseek", "playlists", "output", "work", "logs");
    private static final Map<String, String> PATH_DEFAULTS = Map.of(
            "sockseek", "sockseek.exe", "playlists", "Playlists", "output", "output",
            "work", "work", "logs", "logs");

    /**
     * The settings file, written when it is missing.
     *
     * <p>It carries a comment per key since 2026-10-17. Until then it was a bare list of
     * names and values and the manual did the explaining, by the owner's call of 2026-10-11 -
     * he reversed it on seeing the file: a setting you have to leave the file to understand
     * is a setting you will not touch. The comments say what the key IS; the manual still has
     * the why and the measurements behind each default.
     */
    public static final String CONFIG_TEMPLATE = """
            paths:
              # the sockseek binary; .exe is added or dropped to match what is really there
              sockseek: sockseek.exe
              # folder of playlist .txt files - this is the input
              playlists: Playlists
              # where the audio goes, one sub-folder per playlist
              output: output
              # generated list files and the merged sockseek config; rebuilt every run and
              # safe to delete - but it holds the login in plain text while one is going
              work: work
              # run logs, session logs and the run counter
              logs: logs

            search:
              # HARD filter: a shared file may differ from the playlist length by this many
              # seconds, -1 turns it off
              length_tol_s: 45
              # how many times to attempt one playlist; each attempt is a fresh sockseek
              retries: 1
              # wall clock for ONE attempt at one playlist, in minutes, 0 = no limit
              playlist_timeout_min: 60
              # stop a playlist when nothing has finished AND nothing has arrived on disk for
              # this many seconds, 0 = never stop it
              stall_timeout_s: 300
              # do not search again for a track nobody was sharing, if that was within this
              # many hours; 0 = always search. Fractions are fine
              skip_not_found_hours: 2
              # the brake across playlists: all of them together stay under this many
              # searches per searches_renew_s, or the Soulseek server bans the account for
              # half an hour. Both must match searches-per-time and searches-renew-time in
              # sockseek.conf: sockseek keeps to them inside one process, this keeps to them
              # across all of them
              searches_per_window: 34
              # and the length of that window, in seconds
              searches_renew_s: 220
              # how many different uploaders to try for one track before giving up this run
              max_sources: 3
              # after a run, search again with every condition off for this many failed tracks
              # per playlist. sockseek reports "nobody had it" and "found it and refused it"
              # with the same code, and this is the only way to tell them apart - which the
              # cache below needs, or it would stop searching for tracks that are there
              explain_tracks: 20
              # Formats in the order you want them, most wanted first. Each is a separate
              # pass over whatever is still missing, with that format REQUIRED, and a last
              # pass with no format at all picks up everything else. This is the only way
              # to order them: sockseek's own pref-format is unordered by its own
              # documentation, so "flac,mp3" there means "both equally fine", not "flac
              # first".
              #
              # It costs searches: a track nobody has goes through every pass, so three
              # formats mean four searches for it instead of one - until the cache has
              # confirmed it absent and stops looking. Empty means one pass, no ordering.
              #   format_order: mp3,flac,wav
              format_order:
              # remember in outcomes.tsv how each track ended; false ignores the file entirely
              use_cache: true
            """;

    /**
     * sockseek.conf is recreated when it is missing, like config.yaml: it carries measured
     * decisions - the 256 kbps floor, the 30 s wait, the ranking ceiling - and losing them
     * silently would quietly change every run.
     *
     * <p>The two login keys stay as comments because sockseek refuses a key with an empty value
     * (measured 2026-10-11: "Error parsing config ... at line 0").
     *
     * <p>The four waiting keys are the honest answer to the owner's rule of 2026-10-17, "якщо
     * швидкість до 60 кб/с це дуже мало... переключаємось на наступне. і так тричі". sockseek
     * has no "slower than N kB/s" lever at all - there is no such condition
     * ({@code uploadspeed}, {@code upspeed} and {@code speed} are all rejected as unknown,
     * measured) and no way to interrupt one transfer from outside without taking the console
     * away. What it does have is these: {@code max-stale-time} drops a source that has sent
     * nothing for 12 s rather than 30, {@code max-retries} caps the attempts at the three he
     * asked for (and sockseek does move to the next candidate by itself - measured with three
     * copies of one track and two forced failures), and the two {@code fails-to-} keys push a
     * bad uploader down the ranking after one failure and out of it after two. That is "three
     * tries, then somebody else", bounded by sockseek's own clock instead of ours.
     *
     * <p>{@code pref-max-bitrate = 1411} and {@code pref-format = flac,mp3} are the owner's
     * decision of 2026-10-16 about disk space, and both are PREFERENCES, so nothing is ever
     * refused for being too big: 1411 kbps is exactly CD lossless, so anything above it can
     * only be 24-bit or above 48 kHz, and a wav costs 10 MB a minute where a flac of the same
     * CD quality costs 7. Both still download when nothing cheaper exists. Measured on the
     * owner's own 57 files: the heaviest was a 2722 kbps flac, 102 MB for 5:15, and the six
     * files above 1411 kbps held 26% of all the bytes.
     */
    public static final String SOCKSEEK_CONF_TEMPLATE = """
            # sockseek's own options, without the leading --. No secrets here: this file is
            # safe to share. The login goes in sockseek.local.conf, which overrides any key
            # below as well. `sockseek.exe --help` is the full list.
            #
            # These two stay commented out: sockseek refuses a key with an empty value.
            # username =
            # password =

            # --- how long to wait on a source that is not delivering ---
            # milliseconds with no bytes arriving before this candidate is dropped
            max-stale-time = 12000
            # an uploader that fails once drops down the ranking
            fails-to-downrank = 1
            # an uploader that fails twice is left out for the rest of the run
            fails-to-ignore = 2

            # --- quality ---
            # A HARD ceiling is not set, on purpose: an expensive file should come last,
            # not be refused, and refusing it can mean the track does not arrive at all.
            # Uncomment one of these if the disk matters more than the coverage. All three
            # reject outright; the pref- keys below only reorder.
            #   max-bitrate = 1411      kbps. 1411 is exactly CD lossless, so this keeps
            #                           24-bit and hi-res out
            #   max-samplerate = 48000  Hz. Keeps 96 and 192 kHz out, where the size is
            #   max-bitdepth = 16       bits. 24-bit is 1.5x the bytes at the same rate
            #   format = mp3,flac       the formats allowed AT ALL (not an order - see
            #                           format_order in config.yaml for that)
            # the ONLY hard floor: a file below this bitrate is refused outright
            min-bitrate = 256
            # everything with pref- only RANKS candidates and never refuses one
            pref-min-bitrate = 320
            # 1411 kbps is CD lossless; anything above it is 24-bit or hi-res, so it ranks
            # last and arrives only when there is nothing cheaper
            pref-max-bitrate = 1411
            # flac and mp3 equally preferred; wav is not listed, so it ranks below both
            pref-format = flac,mp3

            # --- picking a source early ---
            # take the first candidate that satisfies the preferred conditions instead of
            # waiting for the whole search to finish
            fast-search = true
            # but spend this many milliseconds collecting candidates first
            fast-search-delay = 2000
            # a pass mark for that shortcut ONLY; a slower uploader stays in the normal set.
            # The unit is undocumented and still unproven - 1 is sockseek's own default
            fast-search-min-up-speed = 200
            # prefer files whose path contains the track title / the artist
            pref-strict-title = true
            pref-strict-artist = true
            """;

    public static final String LOCAL_CONF_TEMPLATE = """
            username = your-login
            password = your-password
            """;

    public static final List<Setting> ALL = List.of(
            new Setting("length_tol_s", "--length-tol", Setting.Kind.INTEGER, 45, -1.0,
                    "length tolerance in seconds around the playlist length; HARD condition, "
                            + "-1 disables (default 45)", List.of("tol")),
            // 60 and 1, the same numbers the shipped config.yaml carries. They used to be
            // 40 and 2, so a deleted config file quietly changed how a run behaved and the
            // help text named a default the file disagreed with - one number is enough.
            new Setting("playlist_timeout_min", "--playlist-timeout", Setting.Kind.DECIMAL, 60.0,
                    0.0, "wall-clock limit per attempt for one playlist, in minutes, 0 = none "
                            + "(default 60)", List.of("timeout")),
            new Setting("retries", "--retries", Setting.Kind.INTEGER, 1, 1.0,
                    "attempts per playlist, at least 1 (default 1)", List.of()),
            new Setting("stall_timeout_s", "--stall-timeout", Setting.Kind.DECIMAL, 300.0, 0.0,
                    "stop a playlist when no track has finished for this many seconds, "
                            + "0 = never (default 300)", List.of()),
            new Setting("skip_not_found_hours", "--skip-not-found-hours",
                    Setting.Kind.DECIMAL, 2.0, 0.0,
                    "do not search again for a track nobody had, if that was within this many "
                            + "hours; 0 = always search (default 2). Fractions are fine: 0.5 "
                            + "is half an hour", List.of()),
            new Setting("use_cache", "--no-cache", Setting.Kind.BOOLEAN, 1, 0.0,
                    "remember what happened to a track and act on it; false ignores "
                            + "outcomes.tsv completely - searches everything and writes "
                            + "nothing down (default true)", List.of()),
            new Setting("searches_per_window", "--searches-per-window", Setting.Kind.INTEGER,
                    34, 0.0, "keep the searches of ALL playlists together under this many per "
                            + "searches_renew_s, by pausing between playlists; 0 = no pause. "
                            + "Must match sockseek's searches-per-time (default 34)",
                    List.of()),
            new Setting("searches_renew_s", "--searches-renew-s", Setting.Kind.INTEGER, 220,
                    1.0, "the window those searches are counted in, in seconds. Must match "
                            + "sockseek's searches-renew-time (default 220)", List.of()),
            new Setting("max_sources", "--max-sources", Setting.Kind.INTEGER, 3, 1.0,
                    "how many different uploaders to try for one track before giving up on "
                            + "it this run (default 3)", List.of()),
            new Setting("explain_tracks", "--explain-tracks", Setting.Kind.INTEGER, 20, 0.0,
                    "after a run, search again with every condition off for this many failed "
                            + "tracks per playlist, to find out which of them nobody had and "
                            + "which were refused by the filters; costs one search each, "
                            + "0 = none (default 20)", List.of()));

    /** Taken from the template rather than written out again: renaming a placeholder there must
     *  not quietly turn "you have not filled this in yet" into "you have". */
    private static final Set<String> PLACEHOLDERS =
            Set.copyOf(parseConf(LOCAL_CONF_TEMPLATE).values());

    private Settings() {
    }

    public static Optional<Setting> byKey(String key) {
        return ALL.stream()
                .filter(setting -> setting.key().equals(key) || setting.oldKeys().contains(key))
                .findFirst();
    }

    // ------------------------------------------------------------------ config.yaml

    /**
     * Flat {@code key: value} pairs, section names ignored.
     *
     * <p>Deliberately not a YAML implementation: the config is a list of names and values.
     * A line that looks like a setting but yields no pair is reported with its number -
     * dropping one silently is how a setting goes missing while the log still claims the
     * default was chosen on purpose.
     */
    public static Map<String, String> parseYaml(String text, Consumer<String> warn) {
        Map<String, String> values = new LinkedHashMap<>();
        int number = 0;
        for (String raw : text.lines().toList()) {
            number++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            // split on the first ": ", not the first ":", or a value like a Windows path would
            // lose everything after its own colon
            int separator = line.indexOf(": ");
            String head = separator >= 0 ? line.substring(0, separator) : trimTrailingColons(line);
            String given = separator >= 0 ? line.substring(separator + 2).strip() : "";
            String value = separator >= 0 ? scalar(given) : "";
            String key = head.strip();
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
                values.put(key, value);
            } else if ((key.isEmpty() || !line.endsWith(":")) && warn != null) {
                warn.accept("line " + number + " is not a 'key: value' pair, ignored: '"
                        + line + "'");
            }
        }
        return values;
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
        for (Map.Entry<String, String> pair : raw.entrySet()) {
            String key = pair.getKey();
            if (PATH_KEYS.contains(key)) {
                paths.put(key, pair.getValue());
                continue;
            }
            Optional<Setting> found = byKey(key);
            if (found.isEmpty()) {
                say.accept("WARN: " + name + ": unknown key '" + key + "', ignored");
                continue;
            }
            Setting setting = found.get();
            if (!key.equals(setting.key())) {
                say.accept("WARN: " + name + ": key '" + key + "' is deprecated, use '"
                        + setting.key() + "' (same value, unit in the name)");
            }
            Optional<Number> value = setting.kind().parse(pair.getValue());
            if (value.isEmpty()) {
                say.accept("WARN: " + name + ": invalid value for '" + key
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
        return new Config(paths, numbers);
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
            String given = values.get(key);
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
        found.put("sockseek", eitherSuffix(found.get("sockseek")));
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
                    return value == null || value.isBlank() || PLACEHOLDERS.contains(value);
                })
                .toList();
    }

    /**
     * Create the files a fresh clone needs, and name the ones that were created.
     *
     * <p>All three are made here rather than deep inside a run: the first thing a new user
     * should see is "put your login in this file", not an error after a 50 MB download.
     */
    public static List<String> ensureFiles(Path appDir, Consumer<String> warn) {
        Consumer<String> say = warn == null ? message -> { } : warn;
        List<String> created = new ArrayList<>();
        Map<String, String> files = new LinkedHashMap<>();
        files.put(CONFIG_NAME, CONFIG_TEMPLATE);
        files.put(SOCKSEEK_CONF, SOCKSEEK_CONF_TEMPLATE);
        files.put(SOCKSEEK_LOCAL_CONF, LOCAL_CONF_TEMPLATE);
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path path = appDir.resolve(file.getKey());
            if (Files.exists(path)) {
                continue;
            }
            try {
                Files.createDirectories(appDir);
                Files.writeString(path, file.getValue(), StandardCharsets.UTF_8);
            } catch (IOException cannotCreate) {
                say.accept("WARN cannot create " + file.getKey() + ": "
                        + cannotCreate.getMessage());
                continue;
            }
            created.add(file.getKey());
            say.accept(file.getKey().equals(SOCKSEEK_LOCAL_CONF)
                    ? "WARN created " + file.getKey() + ": put your Soulseek login there "
                            + "(it is git-ignored and overrides " + SOCKSEEK_CONF + ")"
                    : "WARN created " + file.getKey() + " from the built-in template");
        }
        return List.copyOf(created);
    }

    /**
     * Merge the sockseek config layers into {@code work/} and return that file.
     *
     * <p>Lowest to highest: sockseek.conf (the defaults that come with the tool, no login) ->
     * sockseek.local.conf (this machine: the login and anything else) -> the file given with
     * {@code --sockseek-conf}. Each layer may hold as few keys as it likes.
     */
    public static Path effectiveConf(Path appDir, Path workDir, Consumer<String> warn,
                                     Path extra) {
        Consumer<String> say = warn == null ? message -> { } : warn;
        ensureFiles(appDir, say);   // a run started through the engine alone still gets them
        String merged = textOf(appDir.resolve(SOCKSEEK_CONF), say);
        for (Path layer : new Path[] {appDir.resolve(SOCKSEEK_LOCAL_CONF), extra}) {
            merged = mergeConf(merged, textOf(layer, say));
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
