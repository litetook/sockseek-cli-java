package dev.litetook.sockseek.playlist;

import java.util.Optional;

/** A playlist file read off disk, with the note that says the encoding was a guess. */
public record Decoded(String text, Optional<String> warning) {
}
