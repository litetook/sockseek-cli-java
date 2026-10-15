package dev.litetook.sockseek.explain;

import java.util.OptionalInt;

/**
 * One file the conditionless search found.
 *
 * <p>The bitrate is often unknown: the standard Soulseek client reports none, which is why it
 * cannot be a plain zero.
 */
public record Candidate(OptionalInt length, OptionalInt bitrate, String path) {
}
