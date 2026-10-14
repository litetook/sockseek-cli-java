package dev.litetook.sockseek.importing;

import java.util.List;

/**
 * What one import produced.
 *
 * @param source the format that was recognised: "m3u", "table" or "text"
 */
public record Imported(List<String> lines, List<String> warnings, String source) {

    public Imported {
        lines = List.copyOf(lines);
        warnings = List.copyOf(warnings);
    }

    public String text() {
        return lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
    }
}
