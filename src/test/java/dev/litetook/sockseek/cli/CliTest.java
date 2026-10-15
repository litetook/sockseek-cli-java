package dev.litetook.sockseek.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The engine's command line, and the configuration block the run log opens with. */
class CliTest {

    private static Cli parsed(Path app, String... argv) {
        Cli cli = new Cli().appDir(app);
        picocli.CommandLine line = new picocli.CommandLine(cli);
        cli.resolve(line.parseArgs(argv));
        return cli;
    }

    private static String block(Cli cli) {
        return String.join("\n", cli.describeConfig());
    }

    @Test
    void aFreshFolderGetsTheTemplateAndTheValuesComeFromIt(@TempDir Path app) {
        // a missing config.yaml is written from the template and read back, so the very first
        // run already uses exactly what is in the file - and the block says so
        String said = block(parsed(app));
        assertTrue(Files.isRegularFile(app.resolve("config.yaml")));
        assertTrue(said.contains("  length_tol_s = 45 [config]"), said);
        assertTrue(said.contains("  playlist_timeout_min = 10 [config]"), said);
        assertTrue(said.contains("  retries = 1 [config]"), said);
        // a whole number reads as a whole number, not 10.0
        assertTrue(!said.contains("10.0"), said);
    }

    @Test
    void aKeyThatTheFileDoesNotSetFallsBackToTheBuiltInDefault(@TempDir Path app)
            throws IOException {
        Files.writeString(app.resolve("config.yaml"), "search:\n  retries: 3\n");
        String said = block(parsed(app));
        assertTrue(said.contains("  retries = 3 [config]"), said);
        assertTrue(said.contains("  length_tol_s = 45 [default]"), said);
        assertTrue(said.contains("  playlist_timeout_min = 40 [default]"), said);
    }

    @Test
    void theConfigFileIsTheMiddleSourceAndTheFlagWins(@TempDir Path app) throws IOException {
        Files.writeString(app.resolve("config.yaml"),
                "search:\n  length_tol_s: 6\n  retries: 5\n");
        String said = block(parsed(app, "--retries", "3"));
        assertTrue(said.contains("  length_tol_s = 6 [config]"), said);
        assertTrue(said.contains("  retries = 3 [cli]"), said);
        assertTrue(said.contains("  playlist_timeout_min = 40 [default]"), said);
    }

    @Test
    void theFoldersAreNamedWithWhereTheyCameFrom(@TempDir Path app) throws IOException {
        Files.writeString(app.resolve("config.yaml"), "paths:\n  output: exported\n");
        String said = block(parsed(app, "--playlists", "lists"));
        assertTrue(said.contains("playlists_dir = lists [cli]"), said);
        assertTrue(said.contains("output_dir = " + app.resolve("exported") + " [config]"), said);
        assertTrue(said.contains("sockseek_exe = " + app.resolve("sockseek.exe") + " [default]"),
                said);
    }

    @Test
    void theBlockOpensWithTheVersionAndTheLegend(@TempDir Path app) {
        assertEquals("effective configuration of sockseek-cli " + Cli.VERSION
                        + " (key = value [source: cli | config | default]):",
                parsed(app).describeConfig().get(0));
    }

    @Test
    void onlyTheFlagsThatWereGivenAppearAtTheEnd(@TempDir Path app) {
        String bare = block(parsed(app));
        assertTrue(!bare.contains("dry_run"), bare);
        String said = block(parsed(app, "--dry-run", "--only", "mix", "--no-explain"));
        assertTrue(said.contains("  only = mix [cli]"), said);
        assertTrue(said.contains("  dry_run = True [cli]"), said);   // True, as Python prints it
        assertTrue(said.contains("  explain = False [cli]"), said);
    }

    @Test
    void aValueBelowTheMinimumStopsTheRunWithTwo(@TempDir Path app) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        for (String[] argv : List.of(new String[] {"--retries", "0"},
                new String[] {"--length-tol", "-2"},
                new String[] {"--playlist-timeout", "-5"})) {
            assertEquals(2, Cli.main(List.of(argv), app,
                    new PrintStream(err, true, StandardCharsets.UTF_8)), String.join(" ", argv));
        }
        String said = err.toString(StandardCharsets.UTF_8);
        assertTrue(said.contains("retries"), said);
        assertTrue(said.contains("below the minimum"), said);
    }

    @Test
    void anUnknownFlagIsRefusedRatherThanIgnored(@TempDir Path app) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(2, Cli.main(List.of("--nonsense"), app,
                new PrintStream(err, true, StandardCharsets.UTF_8)));
    }

    @Test
    void minusOneIsALegalLengthTolerance(@TempDir Path app) {
        assertTrue(block(parsed(app, "--length-tol", "-1")).contains("length_tol_s = -1 [cli]"));
    }

    @Test
    void theExtraSockseekArgumentsAreSplitLikeAQuotedShellString(@TempDir Path app) {
        Cli cli = parsed(app, "--sockseek-args=--mock-files-dir \"C:\\a b\\mock\" --no-config");
        assertEquals(List.of("--mock-files-dir", "C:\\a b\\mock", "--no-config"),
                cli.splitSockseekArgs());
    }

    @Test
    void theRunConfigCarriesWhatWasAskedFor(@TempDir Path app) {
        var config = parsed(app, "--only", "mix", "--length-tol", "60", "--retries", "1",
                "--dry-run").runConfig();
        assertEquals("mix", config.only());
        assertEquals(60, config.options().lengthTol());
        assertEquals(1, config.retries());
        assertTrue(config.dryRun());
    }
}
