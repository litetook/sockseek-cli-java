import dev.litetook.sockseek.playlist.*;
import dev.litetook.sockseek.index.*;
import dev.litetook.sockseek.explain.*;
import dev.litetook.sockseek.config.*;
import dev.litetook.sockseek.process.*;
import java.nio.file.*;
import java.util.*;
var app = Path.of("C:/claude_tasks/Soulseek-Batch");
var out = app.resolve("output/cry-for-you-linds-edit-top30");
var playlist = Playlist.fromFile(app.resolve("Playlists/cry-for-you-linds-edit-top30.txt"));
var rows = SockseekIndex.read(SockseekIndex.findIndexFiles(out));
var failures = SockseekIndex.failures(playlist.entries(), rows).stream().filter(f -> f.failureReason().equals("4")).toList();
var work = Path.of(System.getProperty("workdir")); Files.createDirectories(work);
var listFile = work.resolve("cry-for-you-linds-edit-top30.list.txt");
Files.writeString(listFile, playlist.entries().stream().map(Entry::listLine).reduce((a,b) -> a + "\n" + b).orElse("") + "\n");
var conf = Settings.effectiveConf(app, work, m -> {}, null);
var client = new Sockseek(app.resolve("sockseek.exe"), app, new Options(45, false), List.<String>of(), 10, 1, conf);
var found = Explain.explainFailures(failures, playlist.entries(), listFile, client, out, m -> System.out.println("LOG " + m), ProcessRunner.system());
var byLine = new HashMap<String, Entry>();
for (var e : playlist.entries()) byLine.put(e.original(), e);
for (var failure : failures) {
    System.out.println(SockseekIndex.format(failure));
    var entry = byLine.get(failure.playlistLine());
    var key = entry == null ? null : entry.track().title();
    var candidates = key == null || !found.containsKey(key) ? Optional.<List<Candidate>>empty() : Optional.of(found.get(key));
    for (var line : Explain.explainFailure(failure, candidates, 45, Settings.readMinBitrate(conf)))
        System.out.println(line);
}
/exit
