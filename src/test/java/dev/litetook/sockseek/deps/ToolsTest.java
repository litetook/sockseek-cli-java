package dev.litetook.sockseek.deps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The pinned external tools: resolving, downloading, verifying, installing. */
class ToolsTest {

    private final List<String> logs = new ArrayList<>();
    private static final byte[] PAYLOAD = "a binary, more or less".getBytes(StandardCharsets.UTF_8);

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static Map<String, String> pinned(String asset, String member, String sha) {
        Map<String, String> config = new java.util.LinkedHashMap<>();
        config.put("sockseek.version", "3.0.5");
        config.put("sockseek.url", "https://example.invalid/v{version}/{asset}");
        config.put("sockseek.windows.asset", asset);
        config.put("sockseek.windows.sha256", sha);
        if (member != null) {
            config.put("sockseek.windows.member", member);
        }
        return config;
    }

    private static byte[] zipped(String name, byte[] payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(payload);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static byte[] tarred(String name, byte[] payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar =
                     new TarArchiveOutputStream(new GzipCompressorOutputStream(bytes))) {
            TarArchiveEntry entry = new TarArchiveEntry(name);
            entry.setSize(payload.length);
            tar.putArchiveEntry(entry);
            tar.write(payload);
            tar.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }

    /** A download that always answers with the same bytes. */
    private static Downloader serving(byte[] payload) {
        return url -> payload;
    }

    // ------------------------------------------------------------------ resolving

    @Test
    void theVersionIsSubstitutedIntoTheAssetAndTheUrl(@TempDir Path dir) {
        Tool tool = Tools.tools(pinned("sockseek_{version}_win.zip", "sockseek.exe", "ab"),
                dir, "windows").get(0);
        assertEquals("3.0.5", tool.version());
        assertEquals("https://example.invalid/v3.0.5/sockseek_3.0.5_win.zip", tool.url());
    }

    @Test
    void anArchiveIsRecognisedByHavingAMember(@TempDir Path dir) {
        assertTrue(Tools.tools(pinned("x_{version}.zip", "sockseek.exe", "ab"), dir, "windows")
                .get(0).isArchive());
        assertFalse(Tools.tools(pinned("yt-dlp.exe", null, "ab"), dir, "windows")
                .get(0).isArchive());
    }

    @Test
    void theBinaryLandsNextToTheTool(@TempDir Path dir) {
        assertEquals(dir.resolve("sockseek.exe"),
                Tools.tools(pinned("x_{version}.zip", "sockseek.exe", "ab"), dir, "windows")
                        .get(0).target());
        assertEquals(dir.resolve("yt-dlp.exe"),
                Tools.tools(pinned("yt-dlp.exe", null, "ab"), dir, "windows").get(0).target());
    }

    @Test
    void aPlatformThatIsNotPinnedYieldsNothing(@TempDir Path dir) {
        assertEquals(List.of(),
                Tools.tools(pinned("x.zip", "sockseek", "ab"), dir, "macos-arm64"));
    }

    @Test
    void thisMachineResolvesToAKnownPlatform() {
        assertTrue(List.of("windows", "macos-arm64", "macos-x64", "linux-x64", "linux-arm")
                .contains(Tools.platformKey()), Tools.platformKey());
    }

    // ------------------------------------------------------------------ installing

    @Test
    void aPlainBinaryIsWrittenAsItIs(@TempDir Path dir) throws Exception {
        Tool tool = Tools.tools(pinned("yt-dlp.exe", null, sha256(PAYLOAD)), dir, "windows")
                .get(0);
        assertTrue(Tools.install(tool, logs::add, serving(PAYLOAD)));
        assertEquals(new String(PAYLOAD, StandardCharsets.UTF_8),
                Files.readString(tool.target()));
        assertTrue(logs.stream().anyMatch(line -> line.contains("sha256 ok")), logs.toString());
    }

    @Test
    void aWrongChecksumInstallsNothing(@TempDir Path dir) throws Exception {
        Tool tool = Tools.tools(pinned("yt-dlp.exe", null, "00".repeat(32)), dir, "windows")
                .get(0);
        assertFalse(Tools.install(tool, logs::add, serving(PAYLOAD)));
        assertFalse(Files.exists(tool.target()));
        assertTrue(logs.stream().anyMatch(line -> line.contains("checksum mismatch")),
                logs.toString());
    }

    @Test
    void theMemberIsTakenOutOfAZip(@TempDir Path dir) throws Exception {
        byte[] archive = zipped("sockseek.exe", PAYLOAD);
        Tool tool = Tools.tools(pinned("s_{version}.zip", "sockseek.exe", sha256(archive)),
                dir, "windows").get(0);
        assertTrue(Tools.install(tool, logs::add, serving(archive)));
        assertEquals(new String(PAYLOAD, StandardCharsets.UTF_8), Files.readString(tool.target()));
    }

    @Test
    void aMemberInsideASubFolderIsStillFound(@TempDir Path dir) throws Exception {
        // a release that starts packing into a folder must not break the install
        byte[] archive = zipped("sockseek-3.0.5/sockseek.exe", PAYLOAD);
        Tool tool = Tools.tools(pinned("s_{version}.zip", "sockseek.exe", sha256(archive)),
                dir, "windows").get(0);
        assertTrue(Tools.install(tool, logs::add, serving(archive)));
        assertEquals(new String(PAYLOAD, StandardCharsets.UTF_8), Files.readString(tool.target()));
    }

    @Test
    void aTarGzWorksTheSameWay(@TempDir Path dir) throws Exception {
        byte[] archive = tarred("sockseek", PAYLOAD);
        Tool tool = Tools.tools(pinned("s_{version}.tar.gz", "sockseek", sha256(archive)),
                dir, "windows").get(0);
        assertTrue(Tools.install(tool, logs::add, serving(archive)));
        assertEquals(new String(PAYLOAD, StandardCharsets.UTF_8), Files.readString(tool.target()));
    }

    @Test
    void aMissingMemberIsAnErrorNotAnEmptyFile(@TempDir Path dir) throws Exception {
        byte[] archive = zipped("something-else.exe", PAYLOAD);
        Tool tool = Tools.tools(pinned("s_{version}.zip", "sockseek.exe", sha256(archive)),
                dir, "windows").get(0);
        assertFalse(Tools.install(tool, logs::add, serving(archive)));
        assertFalse(Files.exists(tool.target()));
        assertFalse(Files.exists(tool.target().resolveSibling("sockseek.exe.part")));
    }

    @Test
    void aDownloadThatFailsSaysSoAndWritesNothing(@TempDir Path dir) throws Exception {
        Tool tool = Tools.tools(pinned("yt-dlp.exe", null, sha256(PAYLOAD)), dir, "windows")
                .get(0);
        Downloader broken = url -> {
            throw new IOException("name or service not known");
        };
        assertFalse(Tools.install(tool, logs::add, broken));
        assertFalse(Files.exists(tool.target()));
        assertTrue(logs.stream().anyMatch(line -> line.contains("download failed")),
                logs.toString());
    }

    @Test
    void aGoodInstallLeavesOnlyTheBinary(@TempDir Path dir) throws Exception {
        Tool tool = Tools.tools(pinned("yt-dlp.exe", null, sha256(PAYLOAD)), dir, "windows")
                .get(0);
        Tools.install(tool, logs::add, serving(PAYLOAD));
        try (var files = Files.list(dir)) {
            assertEquals(List.of("yt-dlp.exe"),
                    files.map(file -> file.getFileName().toString()).toList());
        }
    }

    // ------------------------------------------------------------------ on demand

    @Test
    void anInstalledToolIsReturnedWithoutTouchingTheNetwork(@TempDir Path dir) throws Exception {
        Map<String, String> config = pinned("yt-dlp.exe", null, sha256(PAYLOAD));
        Files.write(dir.resolve("yt-dlp.exe"), PAYLOAD);
        Downloader never = url -> {
            throw new AssertionError("nothing should be downloaded");
        };
        assertEquals(Optional.of(dir.resolve("yt-dlp.exe")),
                Tools.ensure("sockseek", logs::add, config, dir, null, never));
    }

    @Test
    void aRefusedQuestionDownloadsNothing(@TempDir Path dir) throws Exception {
        Map<String, String> config = pinned("yt-dlp.exe", null, sha256(PAYLOAD));
        Downloader never = url -> {
            throw new AssertionError("nothing should be downloaded");
        };
        assertEquals(Optional.empty(),
                Tools.ensure("sockseek", logs::add, config, dir, question -> false, never));
        assertFalse(Files.exists(dir.resolve("yt-dlp.exe")));
    }

    @Test
    void anAcceptedQuestionInstallsThePinnedVersion(@TempDir Path dir) throws Exception {
        Map<String, String> config = pinned("yt-dlp.exe", null, sha256(PAYLOAD));
        List<String> asked = new ArrayList<>();
        Optional<Path> found = Tools.ensure("sockseek", logs::add, config, dir, question -> {
            asked.add(question);
            return true;
        }, serving(PAYLOAD));
        assertEquals(Optional.of(dir.resolve("yt-dlp.exe")), found);
        assertTrue(asked.get(0).contains("3.0.5"), asked.toString());
    }

    @Test
    void aToolThatIsNotPinnedIsNamedInAWarning(@TempDir Path dir) {
        assertEquals(Optional.empty(),
                Tools.ensure("ffmpeg", logs::add, Map.of(), dir, null, serving(PAYLOAD)));
        assertTrue(logs.stream().anyMatch(line -> line.contains("not pinned")), logs.toString());
    }

    // ------------------------------------------------------------------ the shipped file

    @Test
    void everyPlatformIsPinnedForEveryToolWithARealChecksum() throws IOException {
        Path file = Path.of("dependencies.yaml");
        Map<String, String> config = Tools.load(file);
        assertFalse(config.isEmpty(), "dependencies.yaml should be readable from the repo root");
        for (String name : Tools.TOOL_NAMES) {
            for (String platform : List.of("windows", "macos-arm64", "macos-x64", "linux-x64",
                    "linux-arm")) {
                String sha = config.get(name + "." + platform + ".sha256");
                assertTrue(sha != null && sha.matches("[0-9a-f]{64}"),
                        name + "." + platform + " = " + sha);
                assertTrue(config.get(name + ".url").startsWith("https://github.com/"),
                        name + " url");
            }
        }
    }

    @Test
    void thePinsAreInsideTheJarSoAFreshFolderCanFetchSockseek() {
        // a release is one jar and nothing else. Until 2026-10-18 the pins were only read
        // from a dependencies.yaml beside it, so a clean install stopped at "sockseek is not
        // pinned in dependencies.yaml" - measured on a fresh folder that day
        Map<String, String> pinned = Tools.builtIn();
        assertFalse(pinned.isEmpty(), "dependencies.yaml is not on the classpath");
        assertTrue(pinned.containsKey("sockseek.version"), pinned.toString());
        for (String platform : List.of("windows", "macos-arm64", "macos-x64", "linux-x64")) {
            assertTrue(pinned.containsKey("sockseek." + platform + ".sha256"), platform);
        }
    }

    @Test
    void aFileBesideTheJarWinsOverThePinsInsideIt(@TempDir Path dir) throws Exception {
        // so a pin can be changed without rebuilding
        Path file = dir.resolve(Tools.DEPENDENCIES_NAME);
        Files.writeString(file, "sockseek.version: 9.9.9\n");
        assertEquals("9.9.9", Tools.load(file).get("sockseek.version"));
        // and with no file at all, the built-in copy answers
        assertEquals(Tools.builtIn().get("sockseek.version"),
                Tools.load(dir.resolve("not-there.yaml")).get("sockseek.version"));
    }
}
