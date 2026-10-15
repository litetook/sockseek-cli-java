import dev.litetook.sockseek.cli.*;
import java.nio.file.*;
import java.util.*;
var app = Path.of("C:/claude_tasks/Soulseek-Batch");
var cases = List.of(List.<String>of(), List.of("--only", "mix"),
  List.of("--retries", "3", "--length-tol", "-1", "--dry-run"),
  List.of("--playlists", "lists", "--no-explain", "--verbose"));
for (var argv : cases) {
  var cli = new Cli().appDir(app);
  var line = new picocli.CommandLine(cli);
  cli.resolve(line.parseArgs(argv.toArray(String[]::new)));
  System.out.println("== " + String.join(" ", argv));
  cli.describeConfig().forEach(System.out::println);
}
/exit
