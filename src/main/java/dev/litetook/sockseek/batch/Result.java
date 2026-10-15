package dev.litetook.sockseek.batch;

/**
 * Outcome of one playlist.
 *
 * <p>{@code broke} is the only thing that makes a run a failure: a track nobody shares is news
 * about Soulseek, not an error in this tool.
 */
public record Result(int total, int missing, boolean broke) {
}
