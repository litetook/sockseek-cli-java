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

    /**
     * Words that disqualify a LOOSE header match, per field.
     *
     * <p>Without them "Album Name" wins the title (it contains "name") and "Added Time" wins
     * the duration (it contains "time"), both silently and both wrong - which is exactly what
     * an independent review found on 2026-10-16. A header named by its exact alias is never
     * blocked: "album artist" still works as an artist column.
     */
    private static final List<String> NOT_A_TITLE =
            List.of("album", "artist", "added", "release", "playlist", "uri", "id");
    private static final List<String> NOT_AN_ARTIST =
            List.of("added", "release", "uri", "id");
    private static final List<String> NOT_A_LENGTH =
            List.of("added", "release", "uri", "id");

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
        CSVFormat.Builder shape = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .setDuplicateHeaderMode(DuplicateHeaderMode.ALLOW_ALL);
        List<CSVRecord> rows;
        List<String> headers;
        List<String> warnings = new ArrayList<>();
        boolean lenient = false;
        try (CSVParser parser = CSVParser.parse(new StringReader(text), shape.get())) {
            rows = parser.getRecords();
            headers = parser.getHeaderNames();
        } catch (IOException | RuntimeException notATable) {
            // A real export does this: Exportify writes a label as "ТОВ "Супериор Мьюзик""
            // - quotes inside a quoted field, not doubled, which is malformed CSV. A real
            // Exportify export has them, and a reader that refuses it is a reader that
            // refuses the files people actually have too; found 2026-10-16 on the owner's own Liked Songs export.
            // RuntimeException and not IllegalArgumentException alone: commons-csv throws
            // CSVException (an IllegalStateException) wrapped in an UncheckedIOException.
            try (CSVParser second = CSVParser.parse(new StringReader(text),
                    shape.setTrailingData(true).get())) {
                rows = second.getRecords();
                headers = second.getHeaderNames();
                lenient = true;
                warnings.add("a quote inside a quoted field is not doubled, which is not valid "
                        + "CSV - read it anyway, ignoring what follows the closing quote");
            } catch (IOException | RuntimeException reallyNotATable) {
                return new Imported(List.of(), List.of("this does not read as a table: "
                        + reallyNotATable.getMessage()), "table");
            }
        }
        if (rows.isEmpty()) {
            return new Imported(List.of(), List.of("the table has a header but no rows"),
                    "table");
        }
        Map<String, String> byName = new LinkedHashMap<>();
        for (String header : headers) {
            byName.putIfAbsent(header.strip().toLowerCase(), header);
        }
        Optional<String> title = column(byName, TITLE_COLUMNS, NOT_A_TITLE);
        if (title.isEmpty()) {
            return new Imported(List.of(),
                    List.of("no title column: looked for " + String.join(", ", TITLE_COLUMNS)
                            + "; the header has " + String.join(", ", byName.keySet().stream()
                            .sorted().toList())), "table");
        }
        Optional<String> artist = column(byName, ARTIST_COLUMNS, NOT_AN_ARTIST);
        Optional<String> lengthColumn = column(byName, LENGTH_COLUMNS, NOT_A_LENGTH);
        List<String> lines = new ArrayList<>();
        if (lengthColumn.isEmpty()) {
            warnings.add("no duration column, the lines will have no length - the length is "
                    + "what keeps another version of the song out");
        }
        int number = 1;
        for (CSVRecord row : rows) {
            number++;
            // After the LENIENT retry, a row whose field count does not match the header is a
            // row whose columns have MOVED: what follows a stray closing quote is dropped only
            // up to the next comma, so a comma inside it ends the field early. Reading it
            // would quietly hand us the wrong artist, the wrong title and no length - and a
            // line with no length loses the hard length filter that keeps another version of
            // the song out. Better one named skip than one silent lie (found by an independent
            // review, 2026-10-16).
            //
            // Only after the lenient retry: in a table that parsed cleanly a short row is
            // ordinary and both tools keep it. The tool's own example.txt is such a file - its
            // comment lines hold commas, so it reads as a ragged table - and checking the
            // count there threw five of its six lines away (caught by the imp parity pair).
            if (lenient && row.size() != headers.size()) {
                warnings.add("row " + number + ": skipped (" + row.size() + " fields where the "
                        + "header has " + headers.size() + " - a quote inside a quoted field "
                        + "moved the columns)");
                continue;
            }
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

    private static Optional<String> column(Map<String, String> header, List<String> names,
                                           List<String> avoid) {
        for (String name : names) {
            if (header.containsKey(name)) {
                return Optional.of(header.get(name));
            }
        }
        // By ALIAS in priority order, not by header: iterating the headers meant the first
        // header that happened to contain any alias won, so "Album Name" was taken as the
        // title because it contains "name", and "Added Time" as the duration because it
        // contains "time" - both silently, both wrong (found by an independent review,
        // 2026-10-16). Asking for each alias in turn keeps "Track Name" ahead of "Album Name".
        for (String name : names) {
            Optional<String> loose = header.entrySet().stream()   // "artist(s)" and the like
                    .filter(pair -> pair.getKey().contains(name))
                    .filter(pair -> avoid.stream().noneMatch(word ->
                            !name.contains(word) && pair.getKey().contains(word)))
                    .map(Map.Entry::getValue)
                    .findFirst();
            if (loose.isPresent()) {
                return loose;
            }
        }
        return header.entrySet().stream()
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
