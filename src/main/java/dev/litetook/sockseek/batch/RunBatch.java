package dev.litetook.sockseek.batch;

import dev.litetook.sockseek.config.Settings;
import dev.litetook.sockseek.process.ProcessRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** Set up the folders, the run number and the log, then hand over to {@link Batch}. */
public final class RunBatch {

    private RunBatch() {
    }

    /**
     * 0 the run worked (tracks may still be missing), 1 this tool failed, 2 a setup problem.
     *
     * @param configReport the configuration block, printed before anything happens
     */
    public static int run(RunConfig config, List<String> configReport, List<String> early,
                          Consumer<String> out, ProcessRunner capture) {
        Path logsDir = config.logsDir();
        Path workDir = config.workDir();
        try {
            Files.createDirectories(logsDir);
            Files.createDirectories(workDir);
        } catch (IOException cannotCreate) {
            out.accept("ERROR: cannot create logs/work folders: " + cannotCreate.getMessage());
            return 2;
        }
        // written back before any download starts
        Runs.Numbered numbered = Runs.nextRunNumber(logsDir);
        RunConfig cfg = config.withRunNo(numbered.number());
        Consumer<String> log = Runs.logger(
                Runs.logPath(logsDir, numbered.number(), cfg.stamp()), out);

        log.accept("run #" + Runs.label(numbered.number()));   // the run number is line one
        numbered.warnings().forEach(log);
        configReport.forEach(log);
        early.forEach(log);
        if (!cfg.dryRun() && !Files.isRegularFile(cfg.sockseek())) {
            log.accept("ERROR: sockseek not found: " + cfg.sockseek());
            return 2;
        }
        // --no-config / --config among the passed-through arguments means the caller decides
        // about the config file, which is how the tests run sockseek in mock mode with no login
        boolean decided = cfg.sockseekArgs().stream()
                .anyMatch(argument -> argument.equals("--no-config")
                        || argument.startsWith("--config"));
        if (cfg.sockseekConf() != null && !Files.isRegularFile(cfg.sockseekConf())) {
            log.accept("ERROR: --sockseek-conf: no such file: " + cfg.sockseekConf());
            return 2;
        }
        if (!decided) {
            Path conf = Settings.effectiveConf(cfg.appDir(), workDir, log, cfg.sockseekConf());
            cfg = cfg.withConf(conf);
            String text = "";
            try {
                text = Files.readString(conf, StandardCharsets.UTF_8);
            } catch (IOException noConf) {
                text = "";
            }
            List<String> absent = Settings.missingCredentials(text);
            if (!absent.isEmpty() && !cfg.dryRun()) {
                log.accept("ERROR: no Soulseek login: " + String.join(", ", absent)
                        + " missing in " + cfg.appDir().resolve(Settings.SOCKSEEK_LOCAL_CONF));
                return 2;
            }
        }
        return new Batch(cfg, logsDir, workDir, log, capture).run();
    }
}
