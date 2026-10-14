package dev.litetook.sockseek.playlist;

import java.util.OptionalInt;

/**
 * One track asked of sockseek.
 *
 * <p>The artist is usually empty: a playlist line is a free-form title like
 * "Nova Blue - Kate Ryan - Song (Remix)", where splitting the artist off is a guess, and the
 * whole line is searched as one string instead. Proven 2026-10-07 (runs #0004/#0005): the
 * search finds the same files either way.
 *
 * <p>The length is what keeps another version of the song out, and a track without one is
 * searched without that filter - so it is an {@link OptionalInt}, never a silent zero.
 */
public record Track(String artist, String title, OptionalInt length) {
    public Track {
        artist = artist == null ? "" : artist;
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("a track needs a title");
        }
    }
}
