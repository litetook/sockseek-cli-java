package dev.litetook.sockseek.playlist;

/**
 * One playlist line, ready for sockseek.
 *
 * @param listLine what goes into the generated list file, e.g. {@code s:"title=T, length=249"}
 * @param original the line as the person wrote it, which is what reports show them
 */
public record Entry(String listLine, String original, Track track) {
}
