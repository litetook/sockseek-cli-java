import dev.litetook.sockseek.config.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
var app = Path.of(System.getenv().getOrDefault("SOCKSEEK_PY", "C:\\claude_tasks\\Soulseek-Batch"));
var warnings = new ArrayList<String>();
var cfg = Settings.loadConfig(app.resolve("config.yaml"), warnings::add);
var all = new TreeMap<String,String>();
cfg.paths().forEach((k,v) -> all.put(k, v));
cfg.numbers().forEach((k,v) -> all.put(k, String.valueOf(v)));
System.out.println("config: " + all.entrySet().stream().map(e -> "('" + e.getKey() + "', '" + e.getValue() + "')").toList());
System.out.println("warnings: " + warnings);
var paths = new TreeMap<>(Settings.configPaths(cfg, app));
paths.forEach((k,v) -> System.out.println("path " + k + " = " + v));
var work = Path.of(System.getProperty("workdir"));
var merged = Settings.effectiveConf(app, work, m -> {}, null);
var text = Files.readString(merged);
// printed the way Python prints a sorted list of strings, so only real differences show up
var keys = new TreeSet<>(Settings.parseConf(text).keySet());
System.out.println("keys: [" + keys.stream().map(k -> "'" + k + "'").reduce((a,b) -> a + ", " + b).orElse("") + "]");
var sha = MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8"));
var hex = new StringBuilder(); for (byte b : sha) hex.append(String.format("%02x", b));
System.out.println("sha256: " + hex);
System.out.println("min-bitrate: " + Settings.readMinBitrate(merged).getAsInt());
System.out.println("missing credentials: " + Settings.missingCredentials(text));
/exit
