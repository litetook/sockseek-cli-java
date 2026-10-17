package dev.litetook.sockseek.gates;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Invented playlists that behave like real ones.
 *
 * <pre>{@code gradlew makeFixture}</pre>
 *
 * <p>The snapshot needs a folder that does not move. For half a day it was a copy of the
 * author's own, and that was wrong twice over: it went red every time he downloaded a track,
 * and it published his music to a public repository. Neither is what a fixture is for.
 *
 * <p>So it is generated from a fixed seed. What matters is not which songs these are but
 * the SHAPE of them, and the shape is deliberately nasty, because every one of these has
 * broken something at least once:
 *
 * <ul>
 *   <li>non-ASCII titles, Cyrillic and accented Latin
 *   <li>commas, ampersands, slashes, brackets of both kinds, apostrophes
 *   <li>lengths as m:ss, as h:mm:ss, as bare seconds, and missing altogether
 *   <li>duplicate lines, blank lines, comments
 *   <li>titles far wider than any console
 *   <li>index rows for files that are not there, and files with no row
 * </ul>
 *
 * <p>Re-running this rewrites the fixture, and the recordings then have to be made again -
 * which is deliberate. A fixture is data, and data that changes quietly is how a check
 * starts lying.
 */
public final class Fixture {

    private static final long SEED = 20261017;

    private static final List<String> ARTISTS = List.of(
            "Aurora Beltrán", "Blue Kettle", "Cosmic Drift", "Döner Kebab Sound System",
            "Echo & The Mechanics", "Fjord", "Gábor Szabó Tribute", "Hollow Coast",
            "Iris Morningstar", "Járn", "Kettle Drum Jones", "Låpsley Covers",
            "Midnight Sorter", "Núria Vega", "Oblique Strategy", "Pavel Zaytsev",
            "Quiet Harbour", "Rafael's Theory", "Sunken Chapel", "Tölva",
            "Умовний Рефлекс", "Vølur", "Wandering Spoke", "Ясне Поле");
    private static final List<String> WORDS = List.of(
            "Acid", "Amber", "Backroom", "Cathedral", "Daylight", "Drift", "Ember",
            "Fracture", "Glasshouse", "Harbour", "Iron", "Jetty", "Kelvin", "Lantern",
            "Mirage", "Northline", "Orbit", "Pressure", "Quarry", "Rivet", "Slipstream",
            "Tideline", "Undertow", "Vertigo", "Wharf", "Xenon", "Yellowline", "Zephyr");
    private static final List<String> TAILS = List.of(
            "", "", "", " (Extended Mix)", " (Radio Edit)", " [FREE DOWNLOAD]",
            " (feat. Núria Vega)", " - Original Mix", " (Pavel Zaytsev Remix)", " / B-side",
            " (Live at the Quarry, 1998)");

    /** The long tail of small playlists is what a real folder looks like. */
    private static final Map<String, Integer> PLAYLISTS = new LinkedHashMap<>();

    static {
        PLAYLISTS.put("ambient-night", 25);
        PLAYLISTS.put("breaks", 29);
        PLAYLISTS.put("chill", 69);
        PLAYLISTS.put("classics", 10);
        PLAYLISTS.put("dnb", 38);
        PLAYLISTS.put("dubstep-bass", 45);
        PLAYLISTS.put("example", 3);
        PLAYLISTS.put("hip-hop", 54);
        PLAYLISTS.put("house", 84);
        PLAYLISTS.put("nothing-here", 17);
        PLAYLISTS.put("one-track", 1);
        PLAYLISTS.put("other", 36);
        PLAYLISTS.put("phonk-dark", 31);
        PLAYLISTS.put("pop", 90);
        PLAYLISTS.put("rock-metal", 43);
        PLAYLISTS.put("techno", 40);
        PLAYLISTS.put("techno-acid", 27);
        PLAYLISTS.put("techno-hard", 107);
        PLAYLISTS.put("techno-melodic", 61);
        PLAYLISTS.put("trance", 33);
        PLAYLISTS.put("ukrainian", 146);
        PLAYLISTS.put("wanted", 4);
        PLAYLISTS.put("weird-edges", 12);
        PLAYLISTS.put("with-links", 8);
    }

    private Fixture() {
    }

    public static void main(String[] args) throws IOException {
        Random rng = new Random(SEED);
        Gates.delete(Gates.FIXTURE.resolve("Playlists"));
        Gates.delete(Gates.FIXTURE.resolve("output"));
        Files.createDirectories(Gates.FIXTURE.resolve("Playlists"));
        int total = 0;
        for (Map.Entry<String, Integer> one : PLAYLISTS.entrySet()) {
            List<String> lines = playlist(rng, one.getValue());
            Files.writeString(Gates.FIXTURE.resolve("Playlists").resolve(one.getKey() + ".txt"),
                    String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
            total += one.getValue();
            if (!one.getKey().equals("nothing-here")) {   // one sockseek never saw
                index(rng, lines, Gates.FIXTURE.resolve("output").resolve(one.getKey()));
            }
        }
        System.out.println("wrote " + PLAYLISTS.size() + " playlists, " + total
                + " lines, seed " + SEED);
    }

    private static List<String> playlist(Random rng, int count) {
        List<String> lines = new ArrayList<>();
        for (int n = 0; n < count; n++) {
            lines.add(line(rng));
        }
        if (count > 6) {
            lines.add(rng.nextInt(count), lines.get(0));     // a duplicate, which must collapse
            lines.add(rng.nextInt(count), "");               // a blank line
            lines.add(rng.nextInt(count), "# a comment");    // and a comment
        }
        return lines;
    }

    private static String line(Random rng) {
        StringBuilder text = new StringBuilder(pick(rng, ARTISTS) + " - " + title(rng));
        if (rng.nextDouble() < 0.04) {
            text.append(" ").append("very ".repeat(20)).append("long");
        }
        String tail = length(rng);
        return tail.isEmpty() ? text.toString() : text + " | " + tail;
    }

    private static String title(Random rng) {
        int words = List.of(1, 1, 2, 2, 3).get(rng.nextInt(5));
        List<String> chosen = new ArrayList<>(WORDS);
        java.util.Collections.shuffle(chosen, rng);
        return String.join(" ", chosen.subList(0, words)) + pick(rng, TAILS);
    }

    /** m:ss mostly, sometimes h:mm:ss or bare seconds, sometimes nothing at all. */
    private static String length(Random rng) {
        int seconds = 95 + rng.nextInt(8906);
        double shape = rng.nextDouble();
        if (shape < 0.08) {
            return "";
        }
        if (shape < 0.14) {
            return Integer.toString(seconds);
        }
        if (seconds >= 3600) {
            return "%d:%02d:%02d".formatted(seconds / 3600, seconds % 3600 / 60, seconds % 60);
        }
        return "%d:%02d".formatted(seconds / 60, seconds % 60);
    }

    /** An index for some of them: a few downloaded, a few failed, a few never searched. */
    private static void index(Random rng, List<String> lines, Path folder) throws IOException {
        List<String> rows = new ArrayList<>();
        for (String one : lines) {
            if (one.isBlank() || one.startsWith("#") || rng.nextDouble() < 0.45) {
                continue;   // never seen by sockseek at all
            }
            String name = one.split(" \\| ")[0];
            int roll = rng.nextInt(100);
            // downloaded, failed, already there, never searched - roughly as a real one looks
            String state = roll < 45 ? "1" : roll < 80 ? "2" : roll < 95 ? "3" : "0";
            String path = state.equals("1") || state.equals("3")
                    ? (name + ".mp3").replace("/", "_") : "";
            rows.add(String.join(",", quote(path), "", "", quote(name), "-1", "0", state,
                    state.equals("2") ? "9" : "0"));
        }
        if (rows.isEmpty()) {
            return;
        }
        Files.createDirectories(folder);
        List<String> all = new ArrayList<>();
        all.add("filepath,artist,album,title,length,tracktype,state,failurereason");
        all.addAll(rows);
        Files.writeString(folder.resolve("_index.csv"), String.join("\n", all) + "\n",
                StandardCharsets.UTF_8);
    }

    /** sockseek quotes any field holding a comma, and these titles do. */
    private static String quote(String value) {
        return value.contains(",") || value.contains("\"")
                ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    private static String pick(Random rng, List<String> from) {
        return from.get(rng.nextInt(from.size()));
    }
}
