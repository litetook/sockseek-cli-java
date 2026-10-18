package dev.litetook.sockseek.batch;

import dev.litetook.sockseek.process.Options;
import dev.litetook.sockseek.process.Sockseek;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Everything one invocation was told to do.
 *
 * <p>A builder rather than a constructor with eighteen arguments: at the call site it reads as
 * a list of decisions, and a new option does not renumber the others.
 */
public record RunConfig(Path appDir, Path playlistsDir, Path outputDir, Path workDir,
                        Path logsDir, Path sockseek,
                        Options options, double timeoutMinutes, int retries, boolean dryRun,
                        String only, boolean verbose, int explainTracks, List<String> sockseekArgs,
                        Path conf, Path sockseekConf, int runNo, LocalDateTime stamp,
                        double stallSeconds, double skipNotFoundHours, boolean cache) {

    public RunConfig {
        sockseekArgs = List.copyOf(sockseekArgs);
    }

    public Sockseek client() {
        return new Sockseek(sockseek, appDir, options, sockseekArgs, timeoutMinutes, retries,
                conf);
    }

    public RunConfig withRunNo(int number) {
        return new RunConfig(appDir, playlistsDir, outputDir, workDir, logsDir, sockseek,
                options, timeoutMinutes, retries, dryRun, only, verbose, explainTracks,
                sockseekArgs, conf, sockseekConf, number, stamp, stallSeconds,
                skipNotFoundHours, cache);
    }

    public RunConfig withConf(Path merged) {
        return new RunConfig(appDir, playlistsDir, outputDir, workDir, logsDir, sockseek,
                options, timeoutMinutes, retries, dryRun, only, verbose, explainTracks,
                sockseekArgs, merged, sockseekConf, runNo, stamp, stallSeconds,
                skipNotFoundHours, cache);
    }

    public static Builder builder(Path appDir) {
        return new Builder(appDir);
    }

    /** Mutable only while the run is being described; what comes out of it is not. */
    public static final class Builder {
        private final Path appDir;
        private Path playlistsDir;
        private Path outputDir;
        private Path workDir;
        private Path logsDir;
        private Path sockseek;
        private Options options = Options.defaults();
        private double timeoutMinutes = 40;
        private int retries = 2;
        private boolean dryRun;
        private String only;
        private boolean verbose;
        // A budget rather than a switch since 2026-10-18: on a real run 104 of 135 failures
        // carried the one code that means both "nobody had it" and "found it and refused
        // it", and nothing but a second search can tell those apart. Twenty is enough to
        // see which kind a playlist is suffering from without paying for every one.
        private int explainTracks = 20;
        private List<String> sockseekArgs = List.of();
        private Path sockseekConf;
        private double stallSeconds = 300;
        private double skipNotFoundHours = 2;
        private boolean cache = true;

        private Builder(Path appDir) {
            this.appDir = appDir;
            this.playlistsDir = appDir.resolve("Playlists");
            this.outputDir = appDir.resolve("output");
            this.workDir = appDir.resolve("work");
            this.logsDir = appDir.resolve("logs");
            this.sockseek = appDir.resolve("sockseek.exe");
        }

        public Builder playlistsDir(Path path) {
            this.playlistsDir = path;
            return this;
        }

        public Builder outputDir(Path path) {
            this.outputDir = path;
            return this;
        }

        public Builder workDir(Path path) {
            this.workDir = path;
            return this;
        }

        public Builder logsDir(Path path) {
            this.logsDir = path;
            return this;
        }

        public Builder sockseek(Path path) {
            this.sockseek = path;
            return this;
        }

        public Builder options(Options value) {
            this.options = value;
            return this;
        }

        public Builder timeoutMinutes(double value) {
            this.timeoutMinutes = value;
            return this;
        }

        public Builder retries(int value) {
            this.retries = value;
            return this;
        }

        public Builder dryRun(boolean value) {
            this.dryRun = value;
            return this;
        }

        public Builder only(String value) {
            this.only = value;
            return this;
        }

        public Builder verbose(boolean value) {
            this.verbose = value;
            return this;
        }

        public Builder explainTracks(int value) {
            this.explainTracks = value;
            return this;
        }

        public Builder sockseekArgs(List<String> value) {
            this.sockseekArgs = value;
            return this;
        }

        public Builder sockseekConf(Path path) {
            this.sockseekConf = path;
            return this;
        }

        public Builder stallSeconds(double value) {
            this.stallSeconds = value;
            return this;
        }

        public Builder skipNotFoundHours(double value) {
            this.skipNotFoundHours = value;
            return this;
        }

        public Builder cache(boolean value) {
            this.cache = value;
            return this;
        }

        public RunConfig build() {
            return new RunConfig(appDir, playlistsDir, outputDir, workDir, logsDir,
                    sockseek, options, timeoutMinutes, retries, dryRun, only, verbose,
                    explainTracks, sockseekArgs, null, sockseekConf, 0,
                    LocalDateTime.now(),
                    stallSeconds, skipNotFoundHours, cache);
        }
    }
}
