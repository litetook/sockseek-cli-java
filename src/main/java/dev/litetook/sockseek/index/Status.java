package dev.litetook.sockseek.index;

import dev.litetook.sockseek.playlist.Entry;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * What of a playlist is already on disk and what is still missing.
 *
 * @param files playlist line -> the file it already has
 */
public record Status(List<Entry> have, List<Entry> missing, Map<String, Path> files) {
}
