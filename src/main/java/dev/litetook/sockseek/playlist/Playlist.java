package dev.litetook.sockseek.playlist;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A playlist file read into entries, with everything worth saying about the file itself. */
public record Playlist(String name, Path path, List<Entry> entries, List<String> warnings) {

    public static Playlist fromFile(Path path) throws IOException {
        Decoded decoded = Playlists.read(path);
        Converted converted = Playlists.convert(decoded.text());
        List<String> warnings = new ArrayList<>();
        decoded.warning().ifPresent(warnings::add);   // the encoding guess comes first
        warnings.addAll(converted.warnings());
        return new Playlist(stem(path), path, converted.entries(), List.copyOf(warnings));
    }

    /** The file name without its last extension: {@code my-mix.txt} is {@code my-mix}. */
    private static String stem(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    public int size() {
        return entries.size();
    }
}
