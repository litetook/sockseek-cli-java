import dev.litetook.sockseek.process.*;
import java.nio.file.*;
import java.util.*;
var exe = Path.of("C:/s/sockseek.exe"); var list = Path.of("work/mix.list.txt"); var out = Path.of("output/mix");
var conf = Path.of("work/sockseek.effective.conf");
record Case(String name, Options options, Path log, List<String> extra, Path conf) {}
var cases = List.of(
  new Case("defaults", Options.defaults(), null, List.<String>of(), null),
  new Case("tol -1", new Options(-1, false), null, List.<String>of(), null),
  new Case("preview", new Options(45, true), null, List.<String>of(), null),
  new Case("verbose+conf", new Options(45, false), Path.of("logs/v.log"), List.<String>of(), conf),
  new Case("extra decides", Options.defaults(), null, List.of("--no-config", "--no-listen"), conf));
for (var one : cases) {
  System.out.println("== " + one.name());
  for (var part : Runner.buildCommand(exe, list, out, one.options(), one.log(), one.extra(), Runner.INDEX_NAME, one.conf()))
    System.out.println("   " + part);
}
/exit
