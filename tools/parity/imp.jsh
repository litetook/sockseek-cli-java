import dev.litetook.sockseek.importing.*;
import java.nio.file.*;
import java.util.*;
var dir = Path.of("C:/claude_tasks/Soulseek-Batch/Playlists");
var files = new ArrayList<Path>();
try (var stream = Files.list(dir)) { stream.filter(f -> f.toString().endsWith(".txt")).sorted().forEach(files::add); }
for (var file : files) {
    var found = new Importer().read(file);
    System.out.println("== " + file.getFileName() + " [" + found.source() + "] " + found.lines().size() + " lines");
    for (var line : found.lines()) System.out.println("  " + line);
    for (var warning : found.warnings()) System.out.println("  WARN " + warning);
}
/exit
