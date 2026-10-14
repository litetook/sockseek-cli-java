package dev.litetook.sockseek.index;

/**
 * One track that did not arrive.
 *
 * @param playlistLine the line as the person wrote it, or empty when the row matches no line
 */
public record Failure(String playlistLine, String indexTitle, String indexLength, String state,
                      String failureReason) {
}
