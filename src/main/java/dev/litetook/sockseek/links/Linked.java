package dev.litetook.sockseek.links;

import java.util.List;

/**
 * What one link produced.
 *
 * @param name the playlist's name at the source, which becomes the file name
 * @param source "spotify", "soundcloud", or "link" when it could not be read at all
 */
public record Linked(List<String> lines, List<String> warnings, String source, String name) {

    public Linked {
        lines = List.copyOf(lines);
        warnings = List.copyOf(warnings);
    }

    public String text() {
        return lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
    }
}
