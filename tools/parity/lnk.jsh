import dev.litetook.sockseek.links.*;
import java.nio.file.*;
var tool = Path.of("C:/claude_tasks/Soulseek-Batch/yt-dlp.exe");
var reader = new LinkReader();
String[][] jobs = { {"https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M", null},
                    {"https://soundcloud.com/bonobo/sets/outlier-radio-on-nts", "yes"} };
for (var job : jobs) {
    var linked = reader.read(job[0], job[1] == null ? null : tool, null);
    System.out.println("== " + linked.source() + " name='" + linked.name() + "' " + linked.lines().size() + " lines");
    for (var line : linked.lines()) System.out.println("  " + line);
    for (var warning : linked.warnings()) System.out.println("  WARN " + warning);
}
/exit
