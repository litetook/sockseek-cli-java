package dev.litetook.sockseek.playlist;

import java.util.List;

/** What one playlist file turned into: the entries, and everything worth saying about it. */
public record Converted(List<Entry> entries, List<String> warnings) {
}
