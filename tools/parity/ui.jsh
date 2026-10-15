import dev.litetook.sockseek.App;
import dev.litetook.sockseek.deps.Downloader;
import dev.litetook.sockseek.links.LinkReader;
import java.nio.file.*;
import java.util.*;

var fromEnv = System.getenv("SOCKSEEK_PY");
var app = Path.of(fromEnv == null || fromEnv.isBlank()
        ? "C:/claude_tasks/Soulseek-Batch" : fromEnv);
var infos = App.scan(app.resolve("Playlists"), app.resolve("output"));
System.out.println("== table");
for (var line : App.formatTable(infos)) System.out.println(line);
for (var info : infos) {
    System.out.println("== missing " + info.name());
    for (var line : App.formatMissing(info)) System.out.println(line);
    for (var warning : info.warnings()) System.out.println("WARN " + warning);
}
String[] answers = {"", "1", "1,3", "3 1", "all", "*", "0", "99", "x", "\u00b2", "1,1", " 2 ",
                    "\u00a01 ", "9999999999"};
System.out.println("== choice");
for (int n = 0; n < answers.length; n++) {
    var picked = App.parseChoice(answers[n], infos.size());
    System.out.println(n + " -> " + (picked.isEmpty() ? "?" : picked.get().toString()));
}
record Scripted(String flags, List<String> said) { }
List<Scripted> runs = List.of(
        new Scripted("--only soulseek-wanted --yes --dry-run", List.of()),
        new Scripted("--only cry-for-you-linds-edit-top30 --yes --length-tol 60 --verbose",
                List.of()),
        new Scripted("--only soulseek-wanted", List.of("y")),
        new Scripted("--only soulseek-wanted", List.of("n")),
        new Scripted("", List.of("1", "1,3", "n", "n")),
        new Scripted("", List.of("")),
        new Scripted("--only nope", List.of()),
        new Scripted("--version", List.of()),
        new Scripted("--import nothing-here.m3u", List.of()));
for (var scripted : runs) {
    System.out.println("== run [" + scripted.flags() + "] answers " + scripted.said().size());
    var left = new ArrayDeque<String>(scripted.said());
    var lines = new ArrayList<String>();
    var calls = new ArrayList<List<String>>();
    App.Ask ask = prompt -> {
        lines.add(prompt);
        return Optional.ofNullable(left.poll());
    };
    App.Engine engine = argv -> {
        calls.add(argv);
        return 0;
    };
    Downloader refuse = url -> {
        throw new java.io.IOException("the parity run must not reach the network");
    };
    var wiring = new App.Wiring(app, engine, new LinkReader(), refuse);
    String[] flags = scripted.flags().isEmpty() ? new String[0] : scripted.flags().split(" ");
    int code = App.run(flags, ask, lines::add, wiring);
    for (var line : lines) System.out.println(line);
    System.out.println("-> code " + code);
    for (var argv : calls) System.out.println("-> engine " + String.join(" ", argv));
}
/exit
