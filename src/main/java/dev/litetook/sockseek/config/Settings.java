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
    public static final List<String> PATH_KEYS = List.of("sockseek", "playlists", "output");
    private static final Map<String, String> PATH_DEFAULTS = Map.of(
            "sockseek", "sockseek.exe", "playlists", "Playlists", "output", "output");

    public static final String CONFIG_TEMPLATE = """
            paths:
              sockseek: sockseek.exe
              playlists: Playlists
              output: output

            search:
              length_tol_s: 45
              retries: 1
              playlist_timeout_min: 10
              stall_timeout_s: 300
              skip_not_found_days: 1
              searches_per_window: 34
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
     * files above 1411 kbps held 26% of all the bytes. The Python tool keeps the old
     * {@code 1000000} and {@code flac,wav,mp3}, which is a divergence on purpose.
     */
    public static final String SOCKSEEK_CONF_TEMPLATE = """
            # username =
            # password =

            max-stale-time = 12000
            max-retries = 3
            fails-to-downrank = 1
            fails-to-ignore = 2

            min-bitrate = 256
            pref-min-bitrate = 320
            pref-max-bitrate = 1411
            pref-format = flac,mp3

            fast-search = true
            fast-search-delay = 2000
            fast-search-min-up-speed = 200
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
            new Setting("playlist_timeout_min", "--playlist-timeout", Setting.Kind.DECIMAL, 40.0,
                    0.0, "wall-clock limit per attempt for one playlist, in minutes, 0 = none "
                            + "(default 40)", List.of("timeout")),
            new Setting("retries", "--retries", Setting.Kind.INTEGER, 2, 1.0,
                    "attempts per playlist, at least 1 (default 2)", List.of()),
            new Setting("stall_timeout_s", "--stall-timeout", Setting.Kind.DECIMAL, 300.0, 0.0,
                    "stop a playlist when no track has finished for this many seconds, "
                            + "0 = never (default 300)", List.of()),
            new Setting("skip_not_found_days", "--skip-not-found-days", Setting.Kind.INTEGER, 1,
                    0.0, "do not search again for a track nobody had, if that was within this "
                            + "many days; 0 = always search (default 1)", List.of()),
            new Setting("searches_per_window", "--searches-per-window", Setting.Kind.INTEGER,
                    34, 0.0, "keep the searches of ALL playlists together under this many per "
                            + "220 s, by pausing between them; 0 = no pause (default 34)",
                    List.of()));

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
