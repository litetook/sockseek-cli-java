package dev.litetook.sockseek.importing;

import java.nio.file.Path;
import java.util.Optional;

/** The playlist that was written, if one was, and everything the import read and noticed. */
public record ImportResult(Optional<Path> written, Imported found) {
}
