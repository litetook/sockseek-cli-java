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
public record RunConfig(Path appDir, Path playlistsDir, Path outputDir, Path sockseek,
                        Options options, double timeoutMinutes, int retries, boolean dryRun,
                        String only, boolean verbose, boolean explain, List<String> sockseekArgs,
                        Path conf, Path sockseekConf, int runNo, LocalDateTime stamp,
                        double stallSeconds, int skipNotFoundDays) {

    public RunConfig {
        sockseekArgs = List.copyOf(sockseekArgs);
    }

    public Sockseek client() {
        return new Sockseek(sockseek, appDir, options, sockseekArgs, timeoutMinutes, retries,
                conf);
    }

    public RunConfig withRunNo(int number) {
        return new RunConfig(appDir, playlistsDir, outputDir, sockseek, options, timeoutMinutes,
                retries, dryRun, only, verbose, explain, sockseekArgs, conf, sockseekConf,
                number, stamp, stallSeconds, skipNotFoundDays);
    }

    public RunConfig withConf(Path merged) {
        return new RunConfig(appDir, playlistsDir, outputDir, sockseek, options, timeoutMinutes,
                retries, dryRun, only, verbose, explain, sockseekArgs, merged, sockseekConf,
                runNo, stamp, stallSeconds, skipNotFoundDays);
    }

    public static Builder builder(Path appDir) {
        return new Builder(appDir);
    }

    /** Mutable only while the run is being described; what comes out of it is not. */
    public static final class Builder {
        private final Path appDir;
        private Path playlistsDir;
        private Path outputDir;
        private Path sockseek;
        private Options options = Options.defaults();
        private double timeoutMinutes = 40;
        private int retries = 2;
        private boolean dryRun;
        private String only;
        private boolean verbose;
        // Off by default since 2026-10-17: the extra query per failed track doubled the time a
        // big playlist spent in the search limiter, and the owner reads the reasons rarely.
        private boolean explain;
        private List<String> sockseekArgs = List.of();
        private Path sockseekConf;
        private double stallSeconds = 300;
        private int skipNotFoundDays = 1;

        private Builder(Path appDir) {
            this.appDir = appDir;
            this.playlistsDir = appDir.resolve("Playlists");
            this.outputDir = appDir.resolve("output");
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

        public Builder explain(boolean value) {
            this.explain = value;
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

        public Builder skipNotFoundDays(int value) {
            this.skipNotFoundDays = value;
            return this;
        }

        public RunConfig build() {
            return new RunConfig(appDir, playlistsDir, outputDir, sockseek, options,
                    timeoutMinutes, retries, dryRun, only, verbose, explain, sockseekArgs, null,
                    sockseekConf, 0, LocalDateTime.now(), stallSeconds, skipNotFoundDays);
        }
    }
}
