package dev.litetook.sockseek.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * One value changes, nothing else does.
 *
 * <p>The point of the whole class: the file is hand-written, and the comments in it are worth
 * more than the convenience of regenerating it. Every test here is really the same test -
 * what did the rest of the file look like afterwards.
 */
class RewriteTest {

    private static final String FILE = """
            # the settings
            playlist:
              attempts: 1                  # goes at one playlist
              attempt_timeout_min: 60min   # the WHOLE playlist; 0 = none

            track:
              format_order: mp3@256, flac
              max_sources: 5               # tries inside one pass

            sockseek:
              refuse:
                max-samplerate: 48000    # Hz
            """;

    @Test
    void theCommentAndTheColumnSurviveTheChange() {
        String after = Rewrite.set(FILE, List.of("playlist", "attempts"), "3", List.of());
        assertTrue(after.contains("  attempts: 3                  # goes at one playlist"),
                after);
        // and nothing else moved
        assertEquals(FILE.lines().count(), after.lines().count());
        assertTrue(after.contains("attempt_timeout_min: 60min   # the WHOLE playlist"), after);
    }

    @Test
    void aNestedKeyIsFoundByItsWholePath() {
        String after = Rewrite.set(FILE, List.of("sockseek", "refuse", "max-samplerate"),
                "44100", List.of());
        assertTrue(after.contains("    max-samplerate: 44100    # Hz"), after);
    }

    @Test
    void anOldNameIsRenamedAsItIsWritten() {
        // the line says max_sources; the form asks for attempts_per_pass
        String after = Rewrite.set(FILE, List.of("track", "attempts_per_pass"), "3",
                List.of("max_sources"));
        assertTrue(after.contains("  attempts_per_pass: 3         # tries inside one pass"),
                after);
        assertTrue(!after.contains("max_sources"), after);
    }

    @Test
    void aKeyThatIsNotThereIsAddedToItsOwnSection() {
        String after = Rewrite.set(FILE, List.of("track", "replace_if_better"), "true",
                List.of());
        assertTrue(after.contains("  replace_if_better: true"), after);
        // in the track block, not at the end of the file
        assertTrue(after.indexOf("replace_if_better") < after.indexOf("sockseek:"), after);
    }

    @Test
    void aSectionThatIsNotThereIsStarted() {
        String after = Rewrite.set("playlist:\n  attempts: 1\n", List.of("cache", "enabled"),
                "false", List.of());
        assertEquals("playlist:\n  attempts: 1\ncache:\n  enabled: false\n", after);
    }

    @Test
    void aHashInsideAValueIsNotACommentAndIsNotLost() {
        // the reader's own rule since 2026-10-19: # starts a comment only after a space
        String file = "paths:\n  music: Rock#1   # where it lands\n";
        String after = Rewrite.set(file, List.of("paths", "music"), "Rock#2", List.of());
        assertTrue(after.contains("  music: Rock#2   # where it lands"), after);
    }

}
