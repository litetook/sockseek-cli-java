import dev.litetook.sockseek.playlist.*;
import java.nio.file.*;
var dir = Path.of("C:/claude_tasks/Soulseek-Batch/Playlists");
for (var file : Playlists.find(dir)) {
    var playlist = Playlist.fromFile(file);
    System.out.println("== " + playlist.name());
    for (var entry : playlist.entries()) System.out.println(entry.listLine());
    for (var warning : playlist.warnings()) System.out.println("WARN " + warning);
}
/exit
