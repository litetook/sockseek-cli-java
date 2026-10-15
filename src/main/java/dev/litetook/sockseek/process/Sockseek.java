package dev.litetook.sockseek.process;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** The binary plus the few things the tool decides for it. */
public record Sockseek(Path exe, Path cwd, Options options, List<String> extraArgs,
                       double timeoutMinutes, int retries, Path conf) {

    public Sockseek {
        extraArgs = List.copyOf(extraArgs);
    }

    /** Zero means no limit, as it does in the config file. */
    public double timeoutSeconds() {
        return timeoutMinutes > 0 ? timeoutMinutes * 60 : 0;
    }

    public Sockseek withTimeoutMinutes(double minutes) {
        return new Sockseek(exe, cwd, options, extraArgs, minutes, retries, conf);
    }

    public List<String> command(Path listFile, Path outDir, Path logFile, Options override,
                                String indexName) {
        return Runner.buildCommand(exe, listFile, outDir, override == null ? options : override,
                logFile, extraArgs, indexName, conf);
    }

    public Status run(List<String> command, Consumer<String> log) {
        return Runner.runWithRetry(command, cwd, timeoutSeconds(), retries, log,
                Runner.RETRY_PAUSE_SECONDS);
    }

    public boolean isPresent() {
        return Files.isRegularFile(exe);
    }
}
