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
        assertEquals(OptionalInt.of(45), config.integer("length_tol_s"));
        assertEquals(OptionalInt.of(1), config.integer("retries"));
        assertEquals(60.0, config.decimal("playlist_timeout_min").orElseThrow());
        assertEquals(Map.of("sockseek", "sockseek.exe", "playlists", "Playlists",
                "output", "output", "work", "work", "logs", "logs"), config.paths());
    }

    @Test
    void aWholeNumberStaysAWholeNumber(@TempDir Path dir) {
        // the run log prints these back: "length_tol_s = 45", never "45.0". Java promotes
        // Integer and Double to double inside one ternary, which is how that happened once
        Config config = load(dir.resolve(Settings.CONFIG_NAME));
        assertInstanceOf(Integer.class, config.numbers().get("length_tol_s"));
        assertInstanceOf(Integer.class, config.numbers().get("retries"));
        assertInstanceOf(Double.class, config.numbers().get("playlist_timeout_min"));
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
    void theConfigDoesNotMentionSockseekOnlyOptions() {
        for (String gone : List.of("stale_time_s", "fast_search", "strict", "desperate",
                "min_bitrate", "pref_format", "searches_per_time", "remove_brackets")) {
            assertFalse(Settings.CONFIG_TEMPLATE.contains(gone), gone);
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
        write(path, "retries: \"3\"\nlength_tol_s: 7\n");   // no section header at all
        Config config = load(path);
        assertEquals(OptionalInt.of(3), config.integer("retries"));
        assertEquals(OptionalInt.of(7), config.integer("length_tol_s"));
    }

    @Test
    void aTrailingCommentIsTolerated(@TempDir Path dir) throws IOException {
        Path path = dir.resolve(Settings.CONFIG_NAME);
        write(path, "search:\n  playlist_timeout_min: 90  # tolerated\n  length_tol_s: 2\n");
        Config config = load(path);
        assertEquals(90.0, config.decimal("playlist_timeout_min").orElseThrow());
        assertEquals(OptionalInt.of(2), config.integer("length_tol_s"));
    }

    @Test
    void minusOneIsALegalLengthTolerance(@TempDir Path dir) throws IOException {
        Path path = dir.resolve(Settings.CONFIG_NAME);
        write(path, "search:\n  length_tol_s: -1\n");
        assertEquals(OptionalInt.of(-1), load(path).integer("length_tol_s"));
        assertEquals(List.of(), warnings);
    }

    @Test
    void anInvalidValueWarnsAndIsDropped(@TempDir Path dir) throws IOException {
        Path path = dir.resolve(Settings.CONFIG_NAME);
        write(path, "search:\n  length_tol_s: abc\n  retries: 4\n");
        Config config = load(path);
        assertFalse(config.has("length_tol_s"));
        assertEquals(OptionalInt.of(4), config.integer("retries"));
        assertTrue(said(warnings, "length_tol_s", "default"), warnings.toString());
    }

    @Test
    void aValueBelowTheMinimumWarnsAndIsDropped(@TempDir Path dir) throws IOException {
        Path path = dir.resolve(Settings.CONFIG_NAME);
        write(path, "search:\n  retries: 0\n  playlist_timeout_min: -3\n  length_tol_s: -2\n");
        Config config = load(path);
        for (String key : List.of("retries", "playlist_timeout_min", "length_tol_s")) {
            assertFalse(config.has(key), key);
            assertTrue(said(warnings, key, "below the minimum"), key + " " + warnings);
        }
        // the numbers read like numbers, not like 0.000000
        assertTrue(said(warnings, "retries = 0 is below the minimum 1"), warnings.toString());
        assertTrue(said(warnings, "playlist_timeout_min = -3 is below the minimum 0"),
                warnings.toString());
    }

    @Test
    void anUnknownKeyIsNamedAndIgnored(@TempDir Path dir) throws IOException {
        Path path = dir.resolve(Settings.CONFIG_NAME);
        write(path, "search:\n  retries: 3\n  min_bitrate: 256\n");
        Config config = load(path);
        assertFalse(config.has("min_bitrate"));
        assertTrue(said(warnings, "min_bitrate", "unknown"), warnings.toString());
    }

    @Test
    void theOldKeysStillLoadAndSayTheyAreOld(@TempDir Path dir) throws IOException {
        Path path = dir.resolve(Settings.CONFIG_NAME);
        write(path, "search:\n  tol: 2\n  timeout: 180\n");
        Config config = load(path);
        assertEquals(OptionalInt.of(2), config.integer("length_tol_s"));
        assertEquals(180.0, config.decimal("playlist_timeout_min").orElseThrow());
        assertTrue(said(warnings, "'tol' is deprecated", "'length_tol_s'"), warnings.toString());
        assertTrue(said(warnings, "'timeout' is deprecated", "'playlist_timeout_min'"),
                warnings.toString());
    }

    // ------------------------------------------------------------------ the parser itself

    @Test
    void aLineWithoutASpaceAfterTheColonIsReported() {
        List<String> said = new ArrayList<>();
        assertEquals(Map.of("length_tol_s", "60"),
                Settings.parseYaml("retries:3\nlength_tol_s: 60\n", said::add));
        assertTrue(said(said, "line 1", "retries:3"), said.toString());
    }

    @Test
    void aSectionHeaderAndACommentAreNotProblems() {
        List<String> said = new ArrayList<>();
        Settings.parseYaml("paths:\n  output: x\n", said::add);
        Settings.parseYaml("# just a note\nretries: 2\n", said::add);
        assertEquals(List.of(), said);
    }

    @Test
    void aHashInsideQuotesStaysInTheValue() {
        assertEquals(Map.of("output", "D:/music#1"),
                Settings.parseYaml("output: \"D:/music#1\"\n", null));
        assertEquals(Map.of("output", "D:/music#1"),
                Settings.parseYaml("output: 'D:/music#1'\n", null));
    }

    @Test
    void aHashOutsideQuotesIsStillAComment() {
        assertEquals(Map.of("retries", "2"),
                Settings.parseYaml("retries: 2  # two attempts\n", null));
    }

    @Test
    void aWindowsPathKeepsItsDriveLetter() {
        // splitting on the first ":" instead of ": " would lose everything after D
        assertEquals(Map.of("output", "D:\\music"),
                Settings.parseYaml("output: D:\\music\n", null));
    }

    @Test
    void theRunLogNamesTheLineItCouldNotRead(@TempDir Path dir) throws IOException {
        Path path = dir.resolve(Settings.CONFIG_NAME);
        write(path, "search:\n  retries:3\n");
        load(path);
        assertTrue(said(warnings, "line 2"), warnings.toString());
    }

    // ------------------------------------------------------------------ the folders

    @Test
    void foldersComeFromTheConfigRelativeToTheAppFolder(@TempDir Path dir) throws IOException {
        Path path = dir.resolve(Settings.CONFIG_NAME);
        write(path, "paths:\n  playlists: lists\n  output: D:\\music\n"
                + "  sockseek: bin\\sockseek.exe\n");
        Map<String, Path> folders = Settings.configPaths(load(path), dir);
        assertEquals(dir.resolve("lists"), folders.get("playlists"));
        assertEquals(dir.resolve("bin").resolve("sockseek.exe"), folders.get("sockseek"));
        assertEquals(Path.of("D:\\music"), folders.get("output"));   // absolute stays absolute
    }

    @Test
    void foldersFallBackToTheLayoutInsideTheProject(@TempDir Path dir) {
        assertEquals(Map.of("playlists", dir.resolve("Playlists"),
                        "output", dir.resolve("output"),
                        "work", dir.resolve("work"),
                        "logs", dir.resolve("logs"),
                        "sockseek", dir.resolve("sockseek.exe")),
                Settings.configPaths(Config.empty(), dir));
    }

    @Test
    void whicheverOfTheTwoBinaryNamesIsReallyThereIsUsed(@TempDir Path dir) throws IOException {
        // one config file then works on Windows and on macOS, instead of one value per machine
        Files.write(dir.resolve("sockseek"), new byte[0]);
        assertEquals(dir.resolve("sockseek"), Settings.configPaths(Config.empty(), dir)
                .get("sockseek"));
    }

    @Test
    void aConfiguredNameWithoutExeStillFindsTheExe(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve("bin"));
        Files.write(dir.resolve("bin").resolve("sockseek.exe"), new byte[0]);
        Config config = new Config(Map.of("sockseek", "bin/sockseek"), Map.of());
        assertEquals(dir.resolve("bin").resolve("sockseek.exe"),
                Settings.configPaths(config, dir).get("sockseek"));
    }

    @Test
    void nothingThereLeavesTheConfiguredValueAlone(@TempDir Path dir) {
        Config config = new Config(Map.of("sockseek", "bin/sockseek.exe"), Map.of());
        assertEquals(dir.resolve("bin").resolve("sockseek.exe"),
                Settings.configPaths(config, dir).get("sockseek"));
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
        assertEquals(List.of("username", "password"),
                Settings.missingCredentials(Settings.LOCAL_CONF_TEMPLATE));
        assertEquals(List.of("username", "password"), Settings.missingCredentials(""));
    }

    @Test
    void theShippedConfDeclaresTheLoginKeysCommentedOut() {
        // sockseek refuses a key with an EMPTY value (measured 2026-10-11: "Error parsing
        // config ... at line 0"), so the two keys stay visible as comments instead
        for (String key : Settings.CREDENTIAL_KEYS) {
            assertTrue(Settings.SOCKSEEK_CONF_TEMPLATE.contains("# " + key + " ="), key);
        }
        assertEquals(List.of("username", "password"),
                Settings.missingCredentials(Settings.SOCKSEEK_CONF_TEMPLATE));
    }

    // ------------------------------------------------------------------ the three layers

    private Path layers(Path dir, String extra) throws IOException {
        write(dir.resolve(Settings.SOCKSEEK_CONF), "min-bitrate = 256\nfast-search = true\n");
        write(dir.resolve(Settings.SOCKSEEK_LOCAL_CONF),
                "username = u\npassword = p\nmin-bitrate = 320\n");
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
        write(dir.resolve(Settings.SOCKSEEK_CONF), "min-bitrate = 256\n");
        write(dir.resolve(Settings.SOCKSEEK_LOCAL_CONF), "min-bitrate = 320\n");
        Path merged = Settings.effectiveConf(dir, dir.resolve("work"), warnings::add,
                dir.resolve("nope.conf"));
        assertEquals("320", Settings.parseConf(Files.readString(merged)).get("min-bitrate"));
    }

    @Test
    void theLoginReachesTheMergedFileAndNotThePublishedOne(@TempDir Path dir) throws IOException {
        Path merged = layers(dir, null);
        assertEquals(dir.resolve("work").resolve(Settings.SOCKSEEK_EFFECTIVE_CONF), merged);
        assertEquals("p", Settings.parseConf(Files.readString(merged)).get("password"));
        assertFalse(Files.readString(dir.resolve(Settings.SOCKSEEK_CONF)).contains("password"));
    }

    @Test
    void theOverrideIsWhatMinBitrateIsReadFrom(@TempDir Path dir) throws IOException {
        assertEquals(OptionalInt.of(320), Settings.readMinBitrate(layers(dir, null)));
    }

    // ------------------------------------------------------------------ a fresh clone

    @Test
    void allThreeFilesAreCreatedAndNamed(@TempDir Path dir) {
        List<String> created = Settings.ensureFiles(dir, warnings::add);
        assertEquals(List.of(Settings.CONFIG_NAME, Settings.SOCKSEEK_CONF,
                Settings.SOCKSEEK_LOCAL_CONF), created);
        for (String name : created) {
            assertTrue(Files.isRegularFile(dir.resolve(name)), name);
        }
    }

    @Test
    void theLoginFileGetsAnInstructionNotJustANotice(@TempDir Path dir) {
        Settings.ensureFiles(dir, warnings::add);
        assertTrue(said(warnings, Settings.SOCKSEEK_LOCAL_CONF, "login"), warnings.toString());
        // and never the value itself
        assertFalse(said(warnings, "your-password"), warnings.toString());
    }

    @Test
    void aFileThatIsThereIsLeftAlone(@TempDir Path dir) throws IOException {
        write(dir.resolve(Settings.CONFIG_NAME), "search:\n  retries: 7\n");
        assertFalse(Settings.ensureFiles(dir, warnings::add).contains(Settings.CONFIG_NAME));
        assertEquals(OptionalInt.of(7), load(dir.resolve(Settings.CONFIG_NAME)).integer("retries"));
    }

    @Test
    void aDeletedSockseekConfComesBackWithTheMeasuredValues(@TempDir Path dir)
            throws IOException {
        // losing it silently would drop the 256 kbps floor and the waiting rules from every
        // run - and those rules ARE the owner's "three tries, then somebody else"
        Settings.ensureFiles(dir, warnings::add);
        Files.delete(dir.resolve(Settings.SOCKSEEK_CONF));
        assertEquals(List.of(Settings.SOCKSEEK_CONF), Settings.ensureFiles(dir, warnings::add));
        Path conf = dir.resolve(Settings.SOCKSEEK_CONF);
        assertEquals(OptionalInt.of(256), Settings.readMinBitrate(conf));
        Map<String, String> keys = Settings.parseConf(Files.readString(conf));
        assertEquals("12000", keys.get("max-stale-time"));
        assertEquals("3", keys.get("max-retries"));
        assertEquals("1", keys.get("fails-to-downrank"));
        assertEquals("2", keys.get("fails-to-ignore"));
        assertEquals(List.of("username", "password"),
                Settings.missingCredentials(Files.readString(conf)));
    }

    @Test
    void aRunThroughTheEngineAloneStillGetsThem(@TempDir Path dir) {
        Settings.effectiveConf(dir, dir.resolve("work"), warnings::add, null);
        assertTrue(Files.isRegularFile(dir.resolve(Settings.SOCKSEEK_LOCAL_CONF)));
        assertTrue(Files.isRegularFile(dir.resolve(Settings.SOCKSEEK_CONF)));
    }
}
