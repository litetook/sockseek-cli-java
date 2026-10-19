package dev.litetook.sockseek.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Changing one value in config.yaml without touching anything else in it.
 *
 * <p>The file is hand-written and full of the owner's own comments, units and ordering - the
 * work of 2026-10-19 was spent making it readable. A form that collected the values and wrote
 * the file out again would throw all of that away the first time it was used, which is why the
 * web page edits the file rather than regenerating it: the line keeps its place, its
 * indentation and the comment at the end of it, and only the value between the colon and the
 * comment changes.
 *
 * <p>Deliberately not a YAML library. The file is a two-level map of scalars written by hand;
 * a parser would round-trip it into something tidy and different, and tidy is exactly what
 * must not happen here.
 */
public final class Rewrite {

    private Rewrite() {
    }

    /**
     * The text with one value changed, added, or left exactly as it was.
     *
     * @param path the key, section first: {@code [track, attempts_per_pass]} or
     *             {@code [sockseek, refuse, max-samplerate]}
     * @param value what to write after the colon; a blank value writes an empty setting,
     *              which is how "take the default" is said in this file
     * @param oldNames earlier names of the same key: a line found under one of them is
     *                 renamed as it is written, so a form never leaves a dead key behind
     */
    public static String set(String text, List<String> path, String value,
                             List<String> oldNames) {
        if (path.isEmpty()) {
            return text;
        }
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        String key = path.get(path.size() - 1);
        List<String> parents = path.subList(0, path.size() - 1);
        List<String> wanted = new ArrayList<>();
        wanted.add(key);
        wanted.addAll(oldNames);

        int sectionEnd = -1;
        int sectionIndent = -1;
        List<String> here = new ArrayList<>();
        for (int at = 0; at < lines.size(); at++) {
            String line = lines.get(at);
            String bare = line.strip();
            if (bare.isEmpty() || bare.startsWith("#")) {
                continue;
            }
            int indent = indentOf(line);
            int depth = indent / 2;
            while (here.size() > depth) {
                here.remove(here.size() - 1);
            }
            String name = nameOf(bare);
            if (name == null) {
                continue;
            }
            // the parents are a prefix, not an exact match: the reader treats a deeper
            // block as a reading aid, so sockseek.refuse.max-samplerate and
            // sockseek.max-samplerate are the same setting and the same line
            boolean inSection = here.size() >= parents.size()
                    && here.subList(0, parents.size()).equals(parents);
            if (here.equals(parents)) {
                sectionEnd = at;
                sectionIndent = indent;
            }
            // the line may carry its unit in the name - max-stale-time-ms - and the key
            // asked for is the bare one
            if (inSection && (wanted.contains(name)
                    || wanted.contains(Settings.withoutUnit(name)))) {
                // written back under the name the line already had, so a file that says
                // max-stale-time-ms keeps saying it
                lines.set(at, written(line, indent, name.equals(key) ? key
                        : Settings.withoutUnit(name).equals(key) ? name : key, value));
                return String.join("\n", lines);
            }
            if (isMap(bare)) {
                here.add(name);
            }
        }
        // not in the file: put it at the end of its section, or start the section
        String pad = " ".repeat(sectionIndent >= 0 ? sectionIndent : parents.size() * 2);
        String added = pad + key + ": " + value;
        if (sectionEnd >= 0) {
            lines.add(sectionEnd + 1, added);
            return String.join("\n", lines);
        }
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        for (int level = 0; level < parents.size(); level++) {
            lines.add(" ".repeat(level * 2) + parents.get(level) + ":");
        }
        lines.add(" ".repeat(parents.size() * 2) + key + ": " + value);
        lines.add("");
        return String.join("\n", lines);
    }

    /** The same line, with the new key and value and whatever comment it carried. */
    private static String written(String line, int indent, String key, String value) {
        String comment = commentOf(line);
        String head = " ".repeat(indent) + key + ": " + value;
        if (comment.isEmpty()) {
            return head;
        }
        // keep the comment where it was if there is still room, one space away if not
        int column = line.indexOf(comment);
        int gap = Math.max(1, column - head.length());
        return head + " ".repeat(gap) + comment;
    }

    /**
     * The comment at the end of a line, if there is one.
     *
     * <p>A {@code #} only starts a comment after whitespace, the same rule the reader uses:
     * a folder called {@code Rock#1} is a value and not half a value.
     */
    static String commentOf(String line) {
        for (int at = 1; at < line.length(); at++) {
            if (line.charAt(at) == '#' && Character.isWhitespace(line.charAt(at - 1))) {
                return line.substring(at);
            }
        }
        return "";
    }

    /** The key a line declares, or null when the line declares none. */
    private static String nameOf(String bare) {
        int colon = bare.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String name = bare.substring(0, colon).strip().toLowerCase(Locale.ROOT);
        return name.isEmpty() ? null : name;
    }

    /**
     * Whether a line opens a block rather than setting a value.
     *
     * <p>A key with nothing after the colon counts as a block here, which is what an empty
     * setting like {@code format_order:} looks like. It costs nothing: the next line is not
     * indented under it, so the false parent is dropped again immediately.
     */
    private static boolean isMap(String bare) {
        int colon = bare.indexOf(':');
        return colon >= 0 && stripComment(bare.substring(colon + 1)).isBlank();
    }

    /** The text of a line without its trailing comment. */
    private static String stripComment(String text) {
        String comment = commentOf(" " + text);
        return comment.isEmpty() ? text
                : (" " + text).substring(0, (" " + text).length() - comment.length());
    }

    private static int indentOf(String line) {
        int at = 0;
        while (at < line.length() && line.charAt(at) == ' ') {
            at++;
        }
        return at;
    }
}
