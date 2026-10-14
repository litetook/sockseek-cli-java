package dev.litetook.sockseek.index;

import java.nio.file.Path;

/** An index row together with the folder its {@code filepath} is relative to. */
public record Located(IndexRow row, Path folder) {
}
