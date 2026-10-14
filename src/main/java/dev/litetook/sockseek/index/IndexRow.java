package dev.litetook.sockseek.index;

import org.apache.commons.csv.CSVRecord;

/**
 * One row of sockseek's {@code _index.csv}.
 *
 * <p>{@code filepath} is relative to the folder the index itself sits in, which is why a row is
 * carried around together with that folder (see {@link Located}).
 */
public record IndexRow(String filepath, String artist, String album, String title, String length,
                       String trackType, String state, String failureReason) {

    static IndexRow from(CSVRecord record) {
        return new IndexRow(get(record, "filepath"), get(record, "artist"), get(record, "album"),
                get(record, "title"), get(record, "length"), get(record, "tracktype"),
                get(record, "state"), get(record, "failurereason"));
    }

    /** A column an older sockseek did not write is empty, never a failure. */
    private static String get(CSVRecord record, String column) {
        return record.isMapped(column) && record.isSet(column) ? record.get(column) : "";
    }
}
