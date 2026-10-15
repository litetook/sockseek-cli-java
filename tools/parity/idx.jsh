import dev.litetook.sockseek.playlist.*;
import dev.litetook.sockseek.index.*;
import java.nio.file.*;
var root = Path.of("C:/claude_tasks/Soulseek-Batch");
for (var file : Playlists.find(root.resolve("Playlists"))) {
    var playlist = Playlist.fromFile(file);
    var out = root.resolve("output").resolve(playlist.name());
    var status = SockseekIndex.playlistStatus(playlist.entries(), out);
    System.out.println("== " + playlist.name() + ": " + status.have().size() + " here, " + status.missing().size() + " missing");
    for (var entry : status.have())
        System.out.println("  HAVE " + entry.original() + " -> " + status.files().get(entry.original()).getFileName());
    var rows = SockseekIndex.read(SockseekIndex.findIndexFiles(out));
    for (var failure : SockseekIndex.failures(playlist.entries(), rows))
        System.out.println("  " + SockseekIndex.format(failure));
}
/exit
