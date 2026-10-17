package dev.litetook.sockseek.gates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The whole command over a fixture, every line against a recorded answer.
 *
 * <p>It asks one question that no other test here asks: did anything change that nobody
 * meant to change? The tool is run as a process - the jar, not a class - over two dozen
 * playlists in each of the three states a folder can be in, and all of it is compared,
 * line by line, with what it printed on a day it was known to be right.
 *
 * <p>When a change IS meant, re-record and read the diff in the commit:
 *
 * <pre>{@code gradlew test -Dgates.record=true}</pre>
 *
 * <p>That diff is the review. It says in plain text what every playlist will now see, which
 * is a thing no description of a change can do as honestly.
 *
 * <p>The fixture is generated and lives in {@code src/test/resources/gates} - see
 * {@link Fixture}. It was a copy of the author's own folder for half a day, which was wrong
 * twice: the recording went red whenever he downloaded a track, and his music ended up in a
 * public repository.
 */
class SnapshotTest {

    /** --all so nothing is asked, --dry-run so nothing is downloaded or searched. */
    private static final List<String> FLAGS = List.of("--all", "--yes", "--dry-run");

    @ParameterizedTest(name = "{0}")
    @EnumSource(Gates.State.class)
    void theWholeCommandSaysWhatItSaidBefore(Gates.State state, @TempDir Path where)
            throws IOException {
        Path folder = Gates.sandbox(where.resolve("sockseek-gate-" + state.label()), state);
        List<String> said = Gates.run(folder, FLAGS.toArray(String[]::new));
        Path recorded = Gates.SNAPSHOTS.resolve(state.label() + ".txt");

        if (Gates.RECORDING) {
            Files.createDirectories(recorded.getParent());
            Files.writeString(recorded, String.join("\n", said) + "\n", StandardCharsets.UTF_8);
            System.out.println("recorded " + said.size() + " lines for " + state.label());
            return;
        }

        assertTrue(Files.isRegularFile(recorded),
                "nothing recorded for " + state.label() + " yet - run with -Dgates.record=true");
        List<String> before = Files.readAllLines(recorded, StandardCharsets.UTF_8);
        assertEquals(before, said, () -> "the tool prints something different now.\n"
                + Gates.differences(before, said, 20)
                + "\n\nIf that is the change you meant, re-record it:\n"
                + "    gradlew test -Dgates.record=true\n");
    }
}
