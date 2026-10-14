package dev.litetook.sockseek.importing;

import dev.litetook.sockseek.playlist.Decoded;
import dev.litetook.sockseek.playlist.Playlists;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;

/**
 * Reads an exported list and gives back playlist lines.
 *
 * <p>The playlist {@code .txt} stays the tool's own interface: readable, editable by hand,
 * diffable and needing no network. This only fills one in from what another program exported,
 * so the fragile part - someone else's format - cannot reach the engine.
 */
public final class Importer {

    static final List<String> TITLE_COLUMNS =
            List.of("track name", "title", "name", "track", "song", "track title");
    static final List<String> ARTIST_COLUMNS =
            List.of("artist name(s)", "artist", "artists", "artist name", "album artist");
    static final List<String> LENGTH_COLUMNS =
            List.of("duration (ms)", "duration_ms", "duration", "time", "length",
                    "track duration");

    private static final String EXTINF = "#EXTINF:";

    /** A single track four hours long: the number is milliseconds, not seconds. */
    static final int IMPLAUSIBLE_SECONDS = 4 * 3600;

    /** The exported list, in whatever shape it arrived. */
    public Imported read(Path path) throws IOException {
        Decoded decoded = Playlists.read(path);
        List<String> warnings = new ArrayList<>(decoded.warning().map(List::of).orElse(List.of()));
        String name = path.getFileName().toString().toLowerCase();
        String text = decoded.text();
        Imported found;
        if (name.endsWith(".m3u") || name.endsWith(".m3u8") || text.stripLeading()
                .startsWith("#EXTM3U")) {
            found = fromM3u(text);
        } else if (name.endsWith(".csv") || name.endsWith(".tsv") || looksTabular(text)) {
            found = fromTable(text);
        } else {
            found = fromText(text);
        }
        warnings.addAll(found.warnings());
        return new Imported(found.lines(), List.copyOf(warnings), found.source());
    }

    private static boolean looksTabular(String text) {
        String head = text.isBlank() ? "" : text.lines().findFirst().orElse("").toLowerCase();
        return TITLE_COLUMNS.stream().anyMatch(head::contains)
                && (head.contains(",") || head.contains("\t"));
    }

    /** {@code #EXTINF:249,Artist - Title} pairs; the file path lines are ignored. */
    public Imported fromM3u(String text) {
        List<String> lines = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (String raw : text.lines().toList()) {
            String line = raw.strip();
            if (!line.startsWith(EXTINF)) {
                continue;
            }
            String body = line.substring(EXTINF.length());
            int comma = body.indexOf(',');
            String name = comma < 0 ? "" : body.substring(comma + 1).strip();
            if (name.isEmpty()) {
                warnings.add("skipped an entry without a title: '" + line + "'");
                continue;
            }
            // #EXTINF is defined in seconds, so a 64-minute set is 3875 here, not 3.9 seconds
            OptionalInt length = Imports.seconds(comma < 0 ? "" : body.substring(0, comma),
                    "", false);
            lines.add(Imports.trackLine(name, length));
        }
        if (lines.isEmpty()) {
            warnings.add("no #EXTINF entries found - is this really an m3u playlist?");
        }
        return new Imported(List.copyOf(lines), List.copyOf(warnings), "m3u");
    }

    /**
     * A csv/tsv export with a header: the title, artist and duration columns are looked up by
     * name, everything else is ignored.
     */
    public Imported fromTable(String text) {
        String first = text.lines().findFirst().orElse("");
        char delimiter = first.indexOf('\t') >= 0 ? '\t' : ',';
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .setDuplicateHeaderMode(DuplicateHeaderMode.ALLOW_ALL)
                .get();
        List<CSVRecord> rows;
        List<String> headers;
        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            rows = parser.getRecords();
            headers = parser.getHeaderNames();
        } catch (IOException | IllegalArgumentException notATable) {
            return new Imported(List.of(), List.of("this does not read as a table: "
                    + notATable.getMessage()), "table");
        }
        if (rows.isEmpty()) {
            return new Imported(List.of(), List.of("the table has a header but no rows"),
                    "table");
        }
        Map<String, String> byName = new LinkedHashMap<>();
        for (String header : headers) {
            byName.putIfAbsent(header.strip().toLowerCase(), header);
        }
        Optional<String> title = column(byName, TITLE_COLUMNS);
        if (title.isEmpty()) {
            return new Imported(List.of(),
                    List.of("no title column: looked for " + String.join(", ", TITLE_COLUMNS)
                            + "; the header has " + String.join(", ", byName.keySet().stream()
                            .sorted().toList())), "table");
        }
        Optional<String> artist = column(byName, ARTIST_COLUMNS);
        Optional<String> lengthColumn = column(byName, LENGTH_COLUMNS);
        List<String> lines = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (lengthColumn.isEmpty()) {
            warnings.add("no duration column, the lines will have no length - the length is "
                    + "what keeps another version of the song out");
        }
        int number = 1;
        for (CSVRecord row : rows) {
            number++;
            String name = value(row, title.get());
            if (name.isEmpty()) {
                warnings.add("row " + number + ": skipped (no title)");
                continue;
            }
            String who = artist.map(column -> value(row, column)).orElse("");
            OptionalInt length = lengthColumn
                    .map(column -> Imports.seconds(value(row, column), column, true))
                    .orElse(OptionalInt.empty());
            lines.add(Imports.trackLine(who.isEmpty() ? name : who + " - " + name, length));
        }
        return new Imported(List.copyOf(lines), List.copyOf(warnings), "table");
    }

    private static String value(CSVRecord row, String column) {
        return row.isMapped(column) && row.isSet(column) ? row.get(column).strip() : "";
    }

    private static Optional<String> column(Map<String, String> header, List<String> names) {
        for (String name : names) {
            if (header.containsKey(name)) {
                return Optional.of(header.get(name));
            }
        }
        return header.entrySet().stream()                      // "artist(s)" and the like
                .filter(pair -> names.stream().anyMatch(name -> pair.getKey().contains(name)))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    /**
     * A plain list of names, one per line. A line that already carries {@code | 4:09} is kept
     * as it is, so re-importing a playlist of ours changes nothing.
     */
    public Imported fromText(String text) {
        List<String> lines = text.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .map(Imports::collapse)
                .toList();
        return new Imported(lines, lines.isEmpty()
                ? List.of("the file has no usable lines") : List.of(), "text");
    }
}
