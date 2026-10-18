package dev.litetook.sockseek.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * config.yaml, the sockseek config layers, and the folders.
 *
 * <p>What a settings file means, minus the parts that belong to the command
 * line and the run itself - those arrive with phase 4.
 */
class SettingsTest {

    private final List<String> warnings = new ArrayList<>();

    private Config load(Path path) {
        return Settings.loadConfig(path, warnings::add);
    }

    private static void write(Path path, String text) throws IOException {
        Files.writeString(path, text);
    }

    private static boolean said(List<String> warnings, String... parts) {
        return warnings.stream().anyMatch(warning -> {
            for (String part : parts) {
                if (!warning.contains(part)) {
                    return false;
                }
            }
            return true;
        });
    }

    // ------------------------------------------------------------------ the file itself

    @Test
    void aMissingFileIsCreatedFromTheTemplate(@TempDir Path dir) {
        Path path = dir.resolve(Settings.CONFIG_NAME);
        assertFalse(load(path).isEmpty());
        assertTrue(Files.isRegularFile(path));
        Config config = load(path);   // the generated template must be valid and warning-free
        assertEquals(List.of(), warnings);
        assertEquals(OptionalInt.of(45), config.integer("max_length_diff_s"));
        assertEquals(OptionalInt.of(1), config.integer("attempts"));
        assertEquals(60.0, config.decimal("attempt_timeout_min").orElseThrow());
        assertEquals(Map.of("sockseek_bin", "sockseek.exe", "playlists", "Playlists",
                "music", "output", "work", "work", "logs", "logs"), config.paths());
    }

    @Test
    void aWholeNumberStaysAWholeNumber(@TempDir Path dir) {
        // the run log prints these back: "track.max_length_diff_s = 45", never "45.0". Java
        // promotes Integer and Double to double inside one ternary, which is how that
        // happened once
        Config config = load(dir.resolve(Settings.CONFIG_NAME));
        assertInstanceOf(Integer.class, config.numbers().get("max_length_diff_s"));
        assertInstanceOf(Integer.class, config.numbers().get("attempts"));
        assertInstanceOf(Double.class, config.numbers().get("attempt_timeout_min"));
    }

    @Test
    void everySettingCarriesAComment() {
        // owner, 2026-10-17, reversing his own call of 2026-10-11 that the file should be a
        // bare list with the explaining left to the manual: "чому не на всіх пропертях є
        // короткий коментар який точно пояснює що це". A setting you have to leave the file
        // to understand is a setting you will not touch.
        List<String> lines = Settings.CONFIG_TEMPLATE.lines().toList();
        for (int n = 0; n < lines.size(); n++) {
            String line = lines.get(n).strip();
            if (line.isEmpty() || line.startsWith("#") || line.endsWith(":")) {
                continue;   // blank, a comment, or a section heading
            }
            String above = n == 0 ? "" : lines.get(n - 1).strip();
            assertTrue(above.startsWith("#"), line + " has no comment above it");
        }
    }

    @Test
    void sockseekOptionsLiveInTheirOwnBlockAndNowhereElse(@TempDir Path dir) {
        // one file since 2026-10-18, but still two namespaces: ours above, sockseek own
        // below, and a key of its never mirrored into a setting of ours
        Config config = load(dir.resolve(Settings.CONFIG_NAME));
        assertEquals("flac,mp3", config.sockseek().get("pref-format"));
        assertEquals("12000", config.sockseek().get("max-stale-time"));
        for (String ours : List.of("stale_time_s", "fast_search", "min_bitrate", "pref_format",
                "searches_per_time", "remove_brackets")) {
            assertFalse(Settings.CONFIG_TEMPLATE.contains(ours), ours);
        }
        // and what the tool owns is not duplicated down there
        assertFalse(config.sockseek().containsKey("max-retries"));
        assertEquals(List.of(), warnings);
    }

    @Test
    void theHardConditionsAreShippedOffAndSayWhy() {
        // measured 2026-10-18: min-bitrate = 256 refused every mp3 at 192 or 224, which was
        // 104 of 135 failures on a real run, and a refusal reads exactly like "nobody had it"
        assertTrue(Settings.CONFIG_TEMPLATE.contains("# min-bitrate: 256"));
        for (String ceiling : List.of("max-bitrate", "max-samplerate", "max-bitdepth")) {
            assertTrue(Settings.CONFIG_TEMPLATE.contains("# " + ceiling + ":"), ceiling);
        }
    }

    @Test
    void aBrokenFileDoesNotThrow(@TempDir Path dir) throws IOException {
        Path path = dir.resolve(Settings.CONFIG_NAME);
        write(path, "not a config file [[[");
        assertTrue(load(path).isEmpty());
    }

    // ------------------------------------------------------------------ values

    @Test
    void quotesAndFlatKeysAreAccepted(@TempDir Path dir) throws IOException {
        Path path = dir.resolve(Settings.CONFIG_NAME);
        write(path, "attempts: \"3\"\nmax_length_diff_s: 7\n");   // no block at all
        Config config = load(path);
        assertEquals(OptionalInt.of(3), config.integer("attempts"));
        assertEquals(OptionalInt.of(7), config.integer("max_length_diff_s"));
        assertEquals(List.of(), warnings);   // a block is a reading aid, never a requirement
    }

    @Test
    void aTrailingCommentIsTolerated(@TempDir Path dir) throws IOException {
        Path path = dir.resolve(Settings.CONFIG_NAME);
        write(path, "playlist:\n  attempt_timeout_min: 90  # tolerated\n"
                + "track:\n  max_length_diff_s: 2\n");
        Config config = load(path);
        assertEquals(90.0, config.decimal("attempt_timeout_min").orElseThrow());
        assertEquals(OptionalInt.of(2), config.integer("max_length_diff_s"));
    }

    @Test
    void minusOneIsALegalLengthTolerance(@TempDir Path dir) throws IOException {
        Path path = dir.resolve(Settings.CONFIG_NAME);
        write(path, "track:\n  max_length_diff_s: -1\n");
        assertEquals(OptionalInt.of(-1), load(path).integer("max_length_diff_s"));
        assertEquals(List.of(), warnings);
    }

    @Test
    void anInvalidValueWarnsAndIsDropped(@TempDir Path dir) throws IOException {
        Path path = dir.resolve(Settings.CONFIG_NAME);
        write(path, "track:\n  max_length_diff_s: abc\nplaylist:\n  attempts: 4\n");
        Config config = load(path);
        assertFalse(config.has("max_length_diff_s"));
        assertEquals(OptionalInt.of(4), config.integer("attempts"));
        assertTrue(said(warnings, "track.max_length_diff_s", "default"), warnings.toString());
    }

    @Test
    void aValueBelowTheMinimumWarnsAndIsDropped(@TempDir Path dir) throws IOException {
        Path path = dir.resolve(Settings.CONFIG_NAME);
        write(path, "playlist:\n  attempts: 0\n  attempt_timeout_min: -3\n"
                + "track:\n  max_length_diff_s: -2\n");
        Config config = load(path);
        for (String key : List.of("attempts", "attempt_timeout_min", "max_length_diff_s")) {
            assertFalse(config.has(key), key);
            assertTrue(said(warnings, key, "below the minimum"), key + " " + warnings);
        }
        // the numbers read like numbers, not like 0.000000
        assertTrue(said(warnings, "attempts = 0 is below the minimum 1"), warnings.toString());
        assertTrue(said(warnings, "attempt_timeout_min = -3 is below the minimum 0"),
                warnings.toString());
    }

    @Test
    void anUnknownKeyIsNamedAndIgnored(@TempDir Path dir) throws IOException {
        Path path = dir.resolve(Settings.CONFIG_NAME);
        write(path, "playlist:\n  attempts: 3\n  min_bitrate: 256\n");
        Config config = load(path);
        assertFalse(config.has("min_bitrate"));
        assertTrue(said(warnings, "playlist.min_bitrate", "unknown"), warnings.toString());
    }

    @Test
    void theOldKeysStillLoadAndSayTheyAreOld(@TempDir Path dir) throws IOException {
        Path path = dir.resolve(Settings.CONFIG_NAME);
        // the file as it stood before 2026-10-18, flat "search:" block and all
        write(path, "search:\n  tol: 2\n  timeout: 180\n  retries: 3\n"
                + "  skip_not_found_hours: 5\n  use_cache: false\n  searches_renew_s: 99\n"
                + "paths:\n  output: D:\\music\n");
        Config config = load(path);
        assertEquals(OptionalInt.of(2), config.integer("max_length_diff_s"));
        assertEquals(180.0, config.decimal("attempt_timeout_min").orElseThrow());
        assertEquals(OptionalInt.of(3), config.integer("attempts"));
        assertEquals(5.0, config.decimal("skip_absent_h").orElseThrow());
        assertEquals(OptionalInt.of(0), config.integer("enabled"));
        assertEquals(OptionalInt.of(99), config.integer("window_s"));
        assertEquals("D:\\music", config.paths().get("music"));
        assertTrue(said(warnings, "'tol' is deprecated", "'track.max_length_diff_s'"),
                warnings.toString());
        assertTrue(said(warnings, "'retries' is deprecated", "'playlist.attempts'"),
                warnings.toString());
        assertTrue(said(warnings, "'output' is deprecated", "'music'"), warnings.toString());
    }

    // ------------------------------------------------------------------ the parser itself

    @Test
    void aLineWithoutASpaceAfterTheColonIsReported() {
        List<String> said = new ArrayList<>();
        assertEquals(Map.of("max_length_diff_s", "60"),
                Settings.parseYaml("attempts:3\nmax_length_diff_s: 60\n", said::add));
        assertTrue(said(said, "line 1", "attempts:3"), said.toString());
    }

    @Test
    void aKeyComesBackUnderTheBlockItWasWrittenIn() {
        assertEquals(Map.of("playlist.attempts", "2", "track.max_sources", "5"),
                Settings.parseYaml("playlist:\n  attempts: 2\ntrack:\n  max_sources: 5\n",
                        null));
    }

    @Test
    void onlyTheOutermostBlockNamesAKey() {
        // refuse / prefer / pick_source / drop_source are reading aids of ours; sockseek
        // never sees them, so they must not reach the key either
        assertEquals(Map.of("sockseek.min-bitrate", "256", "sockseek.fast-search", "true"),
                Settings.parseYaml("sockseek:\n  refuse:\n    min-bitrate: 256\n"
                        + "  pick_source:\n    fast-search: true\n", null));
    }

    @Test
    void sockseekOwnSyntaxIsAcceptedInsideItsBlock() {
        // so a line pasted out of sockseek help works without being translated first
        List<String> said = new ArrayList<>();
        assertEquals(Map.of("sockseek.pref-format", "flac,mp3", "sockseek.max-stale-time",
                        "12000"),
                Settings.parseYaml("sockseek:\n  pref-format = flac,mp3\n"
                        + "  max-stale-time=12000\n", said::add));
        assertEquals(List.of(), said);
    }

    @Test
    void thatSyntaxIsNotAcceptedOutsideIt() {
        List<String> said = new ArrayList<>();
        assertEquals(Map.of(), Settings.parseYaml("playlist:\n  attempts = 2\n", said::add));
        assertTrue(said(said, "line 2"), said.toString());
    }

    @Test
    void aSectionHeaderAndACommentAreNotProblems() {
        List<String> said = new ArrayList<>();
        Settings.parseYaml("paths:\n  music: x\n", said::add);
        Settings.parseYaml("# just a note\nattempts: 2\n", said::add);
        assertEquals(List.of(), said);
    }

    @Test
    void aKeyUnderTheWrongBlockIsHonouredAndNamed(@TempDir Path dir) throws IOException {
        // the blocks group the file; they do not decide anything. Saying so is how a key
        // that drifted into the wrong one gets noticed before it is blamed for something else
        Path path = dir.resolve(Settings.CONFIG_NAME);
        write(path, "cache:\n  attempts: 3\n");
        assertEquals(OptionalInt.of(3), load(path).integer("attempts"));
        assertTrue(said(warnings, "cache.attempts", "belongs under playlist"),
                warnings.toString());
    }

    @Test
    void aHashInsideQuotesStaysInTheValue() {
        assertEquals(Map.of("music", "D:/music#1"),
                Settings.parseYaml("music: \"D:/music#1\"\n", null));
        assertEquals(Map.of("music", "D:/music#1"),
                Settings.parseYaml("music: 'D:/music#1'\n", null));
    }

    @Test
    void aHashOutsideQuotesIsStillAComment() {
        assertEquals(Map.of("attempts", "2"),
                Settings.parseYaml("attempts: 2  # two goes\n", null));
    }

    @Test
    void aWindowsPathKeepsItsDriveLetter() {
        // splitting on the first ":" instead of ": " would lose everything after D
        assertEquals(Map.of("paths.music", "D:\\music"),
                Settings.parseYaml("paths:\n  music: D:\\music\n", null));
    }

    @Test
    void theRunLogNamesTheLineItCouldNotRead(@TempDir Path dir) throws IOException {
        Path path = dir.resolve(Settings.CONFIG_NAME);
        write(path, "playlist:\n  attempts:3\n");
        load(path);
        assertTrue(said(warnings, "line 2"), warnings.toString());
    }

    // ------------------------------------------------------------------ the folders

    @Test
    void foldersComeFromTheConfigRelativeToTheAppFolder(@TempDir Path dir) throws IOException {
        Path path = dir.resolve(Settings.CONFIG_NAME);
        write(path, "paths:\n  playlists: lists\n  music: D:\\music\n"
                + "  sockseek_bin: bin\\sockseek.exe\n");
        Map<String, Path> folders = Settings.configPaths(load(path), dir);
        assertEquals(dir.resolve("lists"), folders.get("playlists"));
        assertEquals(dir.resolve("bin").resolve("sockseek.exe"), folders.get("sockseek_bin"));
        assertEquals(Path.of("D:\\music"), folders.get("music"));   // absolute stays absolute
    }

    @Test
    void foldersFallBackToTheLayoutInsideTheProject(@TempDir Path dir) {
        assertEquals(Map.of("playlists", dir.resolve("Playlists"),
                        "music", dir.resolve("output"),
                        "work", dir.resolve("work"),
                        "logs", dir.resolve("logs"),
                        "sockseek_bin", dir.resolve("sockseek.exe")),
                Settings.configPaths(Config.empty(), dir));
    }

    @Test
    void whicheverOfTheTwoBinaryNamesIsReallyThereIsUsed(@TempDir Path dir) throws IOException {
        // one config file then works on Windows and on macOS, instead of one value per machine
        Files.write(dir.resolve("sockseek"), new byte[0]);
        assertEquals(dir.resolve("sockseek"), Settings.configPaths(Config.empty(), dir)
                .get("sockseek_bin"));
    }

    @Test
    void aConfiguredNameWithoutExeStillFindsTheExe(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve("bin"));
        Files.write(dir.resolve("bin").resolve("sockseek.exe"), new byte[0]);
        Config config = new Config(Map.of("sockseek_bin", "bin/sockseek"), Map.of(),
                Map.of(), Map.of());
        assertEquals(dir.resolve("bin").resolve("sockseek.exe"),
                Settings.configPaths(config, dir).get("sockseek_bin"));
    }

    @Test
    void nothingThereLeavesTheConfiguredValueAlone(@TempDir Path dir) {
        Config config = new Config(Map.of("sockseek_bin", "bin/sockseek.exe"), Map.of(),
                Map.of(), Map.of());
        assertEquals(dir.resolve("bin").resolve("sockseek.exe"),
                Settings.configPaths(config, dir).get("sockseek_bin"));
    }

    // ------------------------------------------------------------------ the sockseek layers

    @Test
    void anOverrideReplacesTheKeyInPlace() {
        assertEquals("min-bitrate = 320\nfast-search = true\n",
                Settings.mergeConf("min-bitrate = 256\nfast-search = true\n",
                        "min-bitrate = 320\n"));
    }

    @Test
    void aKeyThatIsNotInTheBaseIsAdded() {
        String merged = Settings.mergeConf("min-bitrate = 256\n", "username = u\npassword = p\n");
        assertEquals(Map.of("min-bitrate", "256", "username", "u", "password", "p"),
                Settings.parseConf(merged));
    }

    @Test
    void commentsAndBlankLinesOfTheBaseSurvive() {
        assertTrue(Settings.mergeConf("# why 256\nmin-bitrate = 256\n", "min-bitrate = 320\n")
                .contains("# why 256"));
    }

    @Test
    void aProfileSectionIsCopiedUntouched() {
        String merged = Settings.mergeConf("min-bitrate = 256\n\n[lossless]\nmin-bitrate = 900\n",
                "min-bitrate = 320\n");
        assertTrue(merged.contains("[lossless]"));
        assertTrue(merged.contains("min-bitrate = 900"));   // inside the profile, not rewritten
        assertEquals("320", Settings.parseConf(merged).get("min-bitrate"));
    }

    @Test
    void aNewKeyLandsAboveTheFirstProfile() {
        // below it, the key would belong to that profile, and the parser stops at "[" - that is
        // how a correct sockseek.local.conf could read as "no login"
        String merged = Settings.mergeConf("min-bitrate = 256\n\n[lossless]\nmin-bitrate = 900\n",
                "username = u\npassword = p\n");
        assertEquals(List.of(), Settings.missingCredentials(merged));
        assertEquals("u", Settings.parseConf(merged).get("username"));
        assertTrue(merged.indexOf("username") < merged.indexOf("[lossless]"));
        assertTrue(merged.contains("min-bitrate = 900"));
    }

    @Test
    void theLoginKeysThatAreNotSetAreNamed() {
        assertEquals(List.of(), Settings.missingCredentials("username = u\npassword = p\n"));
        assertEquals(List.of("password"), Settings.missingCredentials("username = u\n"));
        assertEquals(List.of("username", "password"), Settings.missingCredentials(""));
    }

    @Test
    void theShippedConfigCarriesNoLoginAndSaysWhereItGoes() {
        // no placeholder anywhere: a file full of your-login looks filled in, and the check
        // that caught that was a check that breaks the day somebody is really called your-login
        Map<String, String> shipped = Settings.parseYaml(Settings.CONFIG_TEMPLATE, null);
        for (String key : Settings.CREDENTIAL_KEYS) {
            assertFalse(shipped.containsKey("sockseek." + key), key);
        }
        // it does say where they go, in a comment, so nobody has to find that in the manual
        assertTrue(Settings.CONFIG_TEMPLATE.contains(Settings.LOCAL_CONFIG_NAME));
        Map<String, String> block = new java.util.LinkedHashMap<>();
        shipped.forEach((name, value) -> {
            if (Settings.sectionOf(name).equals(Settings.SOCKSEEK_SECTION)) {
                block.put(Settings.leafOf(name), value);
            }
        });
        assertEquals(List.of("username", "password"),
                Settings.missingCredentials(Settings.sockseekConf(block)));
    }

    // ------------------------------------------------------------------ the three layers

    private Path layers(Path dir, String extra) throws IOException {
        write(dir.resolve(Settings.CONFIG_NAME),
                "sockseek:\n  min-bitrate: 256\n  fast-search: true\n");
        write(dir.resolve(Settings.LOCAL_CONFIG_NAME),
                "sockseek:\n  username: u\n  password: p\n  min-bitrate: 320\n");
        Path third = null;
        if (extra != null) {
            third = dir.resolve("extra.conf");
            write(third, extra);
        }
        return Settings.effectiveConf(dir, dir.resolve("work"), warnings::add, third);
    }

    private Map<String, String> merged(Path dir, String extra) throws IOException {
        return Settings.parseConf(Files.readString(layers(dir, extra)));
    }

    @Test
    void withoutAThirdLayerTheLocalFileWins(@TempDir Path dir) throws IOException {
        assertEquals(Map.of("min-bitrate", "320", "fast-search", "true",
                "username", "u", "password", "p"), merged(dir, null));
    }

    @Test
    void theCommandLineLayerWinsOverBoth(@TempDir Path dir) throws IOException {
        Map<String, String> keys = merged(dir, "min-bitrate = 192\n");
        assertEquals("192", keys.get("min-bitrate"));
        assertEquals("true", keys.get("fast-search"));   // untouched by the upper layers
        assertEquals("u", keys.get("username"));         // the login still comes from local
    }

    @Test
    void aLayerWithOneKeyChangesOnlyThatKey(@TempDir Path dir) throws IOException {
        Map<String, String> keys = merged(dir, "pref-format = flac\n");
        assertEquals("flac", keys.get("pref-format"));
        assertEquals("320", keys.get("min-bitrate"));
    }

    @Test
    void aLayerThatDoesNotExistIsSimplyNotApplied(@TempDir Path dir) throws IOException {
        write(dir.resolve(Settings.CONFIG_NAME), "sockseek:\n  min-bitrate: 256\n");
        write(dir.resolve(Settings.LOCAL_CONFIG_NAME), "sockseek:\n  min-bitrate: 320\n");
        Path merged = Settings.effectiveConf(dir, dir.resolve("work"), warnings::add,
                dir.resolve("nope.conf"));
        assertEquals("320", Settings.parseConf(Files.readString(merged)).get("min-bitrate"));
    }

    @Test
    void theLoginReachesTheMergedFileAndNotTheCommittedOne(@TempDir Path dir) throws IOException {
        Path merged = layers(dir, null);
        assertEquals(dir.resolve("work").resolve(Settings.SOCKSEEK_EFFECTIVE_CONF), merged);
        assertEquals("p", Settings.parseConf(Files.readString(merged)).get("password"));
        assertFalse(Files.readString(dir.resolve(Settings.CONFIG_NAME)).contains("password"));
    }

    @Test
    void theOverrideIsWhatMinBitrateIsReadFrom(@TempDir Path dir) throws IOException {
        assertEquals(OptionalInt.of(320), Settings.readMinBitrate(layers(dir, null)));
    }

    @Test
    void theLocalFileOverridesOurOwnSettingsToo(@TempDir Path dir) throws IOException {
        // it is not a credentials file with a special case; it is the same file, on top
        write(dir.resolve(Settings.CONFIG_NAME), "playlist:\n  attempts: 1\n"
                + "track:\n  max_sources: 5\n");
        write(dir.resolve(Settings.LOCAL_CONFIG_NAME), "playlist:\n  attempts: 9\n");
        Config config = load(dir.resolve(Settings.CONFIG_NAME));
        assertEquals(OptionalInt.of(9), config.integer("attempts"));
        assertEquals(OptionalInt.of(5), config.integer("max_sources"));
    }

    @Test
    void theBlockIsWrittenOutInSockseekOwnSyntax() {
        assertEquals("pref-format = flac,mp3\nfast-search = true\n",
                Settings.sockseekConf(new java.util.LinkedHashMap<>(
                        new java.util.LinkedHashMap<>() {
                            {
                                put("pref-format", "flac,mp3");
                                put("fast-search", "true");
                            }
                        })));
    }

    // ------------------------------------------------------------------ format_order

    @Test
    void theFormatOrderOfTheFileIsRead(@TempDir Path dir) throws IOException {
        // it was dropped as an unknown key until 2026-10-18, so only --format-order worked
        // and the config line did nothing at all
        Path path = dir.resolve(Settings.CONFIG_NAME);
        write(path, "track:\n  format_order: mp3,flac,wav\n");
        assertEquals("mp3,flac,wav", load(path).text(Settings.FORMAT_ORDER, ""));
        assertEquals(List.of(), warnings);
    }

    @Test
    void anAbsentFormatOrderIsNoOrderAtAll(@TempDir Path dir) {
        assertEquals("", load(dir.resolve(Settings.CONFIG_NAME)).text(Settings.FORMAT_ORDER, ""));
    }

    // ------------------------------------------------------------------ a fresh clone

    @Test
    void onlyTheConfigIsCreatedAndNamed(@TempDir Path dir) {
        assertEquals(List.of(Settings.CONFIG_NAME), Settings.ensureFiles(dir, warnings::add));
        assertTrue(Files.isRegularFile(dir.resolve(Settings.CONFIG_NAME)));
    }

    @Test
    void theLoginFileIsNeverWrittenForYou(@TempDir Path dir) {
        // the owner asked for it absent and empty on 2026-10-18: he writes it himself, and
        // git never sees it
        Settings.ensureFiles(dir, warnings::add);
        assertFalse(Files.exists(dir.resolve(Settings.LOCAL_CONFIG_NAME)));
        assertFalse(said(warnings, "your-password"), warnings.toString());
    }

    @Test
    void aFileThatIsThereIsLeftAlone(@TempDir Path dir) throws IOException {
        write(dir.resolve(Settings.CONFIG_NAME), "playlist:\n  attempts: 7\n");
        assertFalse(Settings.ensureFiles(dir, warnings::add).contains(Settings.CONFIG_NAME));
        assertEquals(OptionalInt.of(7),
                load(dir.resolve(Settings.CONFIG_NAME)).integer("attempts"));
    }

    @Test
    void aDeletedConfigComesBackWithTheMeasuredValues(@TempDir Path dir) throws IOException {
        // losing it silently would drop the waiting rules from every run - and those rules
        // ARE the owner's "three tries, then somebody else"
        Settings.ensureFiles(dir, warnings::add);
        Files.delete(dir.resolve(Settings.CONFIG_NAME));
        assertEquals(List.of(Settings.CONFIG_NAME), Settings.ensureFiles(dir, warnings::add));
        Config config = load(dir.resolve(Settings.CONFIG_NAME));
        assertEquals("12000", config.sockseek().get("max-stale-time"));
        assertEquals("1", config.sockseek().get("fails-to-downrank"));
        assertEquals("2", config.sockseek().get("fails-to-ignore"));
        // max-retries left the conf on 2026-10-18: the tool owns it as track.max_sources,
        // because "retries" is not what it counts
        assertEquals(null, config.sockseek().get("max-retries"));
        // and the floor is off, so nothing is refused for being 192 kbps
        assertEquals(null, config.sockseek().get("min-bitrate"));
    }

    @Test
    void aRunThroughTheEngineAloneStillGetsTheConfig(@TempDir Path dir) {
        Settings.effectiveConf(dir, dir.resolve("work"), warnings::add, null);
        assertTrue(Files.isRegularFile(dir.resolve(Settings.CONFIG_NAME)));
    }
}
