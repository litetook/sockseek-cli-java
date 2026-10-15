package dev.litetook.sockseek.deps;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One pinned external tool for this machine.
 *
 * @param member the file to take out of an archive; null means the download is the binary
 * @param target where the binary belongs, next to this tool
 */
public record Tool(String name, String version, String url, String sha256, String member,
                   Path target) {

    public boolean isArchive() {
        return member != null;
    }

    public boolean isPresent() {
        return Files.isRegularFile(target);
    }
}
