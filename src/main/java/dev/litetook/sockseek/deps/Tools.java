package dev.litetook.sockseek.deps;

import dev.litetook.sockseek.config.Settings;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/**
 * The external tools this one drives, at pinned versions.
 *
 * <p>{@code dependencies.yaml} is the single source of truth: a version, a file name and a
 * sha256 per platform. The same code serves "install everything now" and "a tool is missing,
 * fetch exactly that one", so it makes no difference whether setup was run first.
 *
 * <p>Nothing is installed system-wide and nothing runs as administrator: the binaries land next
 * to the tool.
 */
public final class Tools {

    public static final String DEPENDENCIES_NAME = "dependencies.yaml";
    public static final List<String> TOOL_NAMES = List.of("sockseek", "yt-dlp");

    private Tools() {
    }

    /** windows | macos-arm64 | macos-x64 | linux-x64 | linux-arm. */
    public static String platformKey() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String machine = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = machine.equals("aarch64") || machine.equals("arm64")
                || machine.startsWith("arm");
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return arm ? "macos-arm64" : "macos-x64";
        }
        return arm ? "linux-arm" : "linux-x64";
    }

    /** dependencies.yaml as flat dotted keys ({@code sockseek.windows.sha256}). */
    public static Map<String, String> load(Path path) {
        try {
            return Settings.parseYaml(Files.readString(path, StandardCharsets.UTF_8), null);
        } catch (IOException cannotRead) {
            return Map.of();
        }
    }

    /** Every pinned tool for this platform, with where its binary belongs. */
    public static List<Tool> tools(Map<String, String> config, Path appDir, String key) {
        String platform = key == null ? platformKey() : key;
        List<Tool> found = new ArrayList<>();
        for (String name : TOOL_NAMES) {
            String version = config.get(name + ".version");
            String asset = config.get(name + "." + platform + ".asset");
            String sha256 = config.get(name + "." + platform + ".sha256");
            String url = config.get(name + ".url");
            if (version == null || asset == null || sha256 == null || url == null) {
                continue;
            }
            String file = asset.replace("{version}", version);
            String member = config.get(name + "." + platform + ".member");
            found.add(new Tool(name, version,
                    url.replace("{version}", version).replace("{asset}", file),
                    sha256, member, appDir.resolve(member == null ? file : member)));
        }
        return List.copyOf(found);
    }

    public static Optional<Tool> find(String name, Map<String, String> config, Path appDir) {
        return tools(config, appDir, null).stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst();
    }

    /** {@code <tool>.optional: true} - pinned, but nothing needs it until something does. */
    public static boolean optional(String name, Map<String, String> config) {
        String value = config.getOrDefault(name + ".optional", "").strip().toLowerCase(Locale.ROOT);
        return value.equals("true") || value.equals("yes") || value.equals("1");
    }

    public static String digest(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Download, verify the pinned sha256, unpack if needed, make it executable.
     *
     * <p>The checksum is verified BEFORE the file is put in place, so a truncated or tampered
     * download never becomes the binary this tool runs. The file is written beside the target
     * and renamed: a disk full or an antivirus lock halfway through would otherwise leave a
     * truncated binary that counts as installed from then on.
     */
    public static boolean install(Tool tool, Consumer<String> log, Downloader downloader) {
        byte[] data;
        try {
            log.accept("downloading " + tool.url());
            data = downloader.get(tool.url());
        } catch (IOException cannotDownload) {
            log.accept("ERROR download failed: " + cannotDownload.getMessage());
            return false;
        }
        String got = digest(data);
        if (!got.equalsIgnoreCase(tool.sha256())) {
            log.accept("ERROR checksum mismatch for " + tool.name() + ": expected "
                    + tool.sha256() + ", got " + got);
            return false;
        }
        log.accept("sha256 ok (" + tool.name() + " " + tool.version() + ")");
        Path part = tool.target().resolveSibling(tool.target().getFileName() + ".part");
        try {
            Files.createDirectories(tool.target().getParent());
            if (tool.isArchive()) {
                if (!extract(data, tool.member(), part, log)) {
                    Files.deleteIfExists(part);
                    return false;
                }
            } else {
                Files.write(part, data);
            }
            Files.move(part, tool.target(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException cannotWrite) {
            log.accept("ERROR cannot write " + tool.target() + ": " + cannotWrite.getMessage());
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                log.accept("WARN " + part.getFileName() + " is left behind");
            }
            return false;
        }
        if (!platformKey().equals("windows")) {
            tool.target().toFile().setExecutable(true, false);
        }
        log.accept("installed " + tool.name() + " " + tool.version() + " -> " + tool.target());
        return true;
    }

    /**
     * Take the one file whose name ends with {@code member} out of a .zip or a .tar.gz.
     *
     * <p>The layout inside the archive is not assumed: whatever matches by name is taken, so a
     * release that starts packing into a sub-folder does not break this.
     */
    static boolean extract(byte[] archive, String member, Path target, Consumer<String> log) {
        try {
            Optional<byte[]> found = fromZip(archive, member);
            if (found.isEmpty()) {
                found = fromTarGz(archive, member);
            }
            if (found.isEmpty()) {
                log.accept("ERROR " + member + " is not in the downloaded archive");
                return false;
            }
            Files.write(target, found.get());
            return true;
        } catch (IOException cannotUnpack) {
            log.accept("ERROR cannot unpack the archive: " + cannotUnpack.getMessage());
            return false;
        }
    }

    private static Optional<byte[]> fromZip(byte[] archive, String member) throws IOException {
        try (var zip = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(archive))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().endsWith(member)) {
                    return Optional.of(zip.readAllBytes());
                }
            }
        } catch (IOException notAZip) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Optional<byte[]> fromTarGz(byte[] archive, String member) throws IOException {
        try (InputStream raw = new java.io.ByteArrayInputStream(archive);
             TarArchiveInputStream tar =
                     new TarArchiveInputStream(new GzipCompressorInputStream(raw))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (entry.isFile() && entry.getName().endsWith(member)) {
                    return Optional.of(tar.readAllBytes());
                }
            }
        } catch (IOException notATar) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * The path to {@code name}, installing the pinned version if it is not there yet.
     *
     * <p>{@code ask} is called before a download, so a run can refuse to pull 50 MB unasked;
     * without it the download simply happens, which is what a scheduled run wants.
     */
    public static Optional<Path> ensure(String name, Consumer<String> log,
                                        Map<String, String> config, Path appDir,
                                        Predicate<String> ask, Downloader downloader) {
        Optional<Tool> found = find(name, config, appDir);
        if (found.isEmpty()) {
            log.accept("WARN " + name + " is not pinned in " + DEPENDENCIES_NAME);
            return Optional.empty();
        }
        Tool tool = found.get();
        if (tool.isPresent()) {
            return Optional.of(tool.target());
        }
        if (ask != null && !ask.test(tool.name() + " " + tool.version()
                + " is missing. Download it?")) {
            return Optional.empty();
        }
        return install(tool, log, downloader) ? Optional.of(tool.target()) : Optional.empty();
    }

    /** Every pinned tool with "ok", "missing" or "optional". */
    public static List<String> status(Map<String, String> config, Path appDir) {
        return tools(config, appDir, null).stream()
                .map(tool -> tool.name() + " " + tool.version() + ": "
                        + (tool.isPresent() ? "ok"
                        : optional(tool.name(), config) ? "optional" : "missing"))
                .toList();
    }
}
