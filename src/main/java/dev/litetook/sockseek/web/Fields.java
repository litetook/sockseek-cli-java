package dev.litetook.sockseek.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.litetook.sockseek.config.Knob;
import dev.litetook.sockseek.config.Setting;
import dev.litetook.sockseek.config.Settings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The settings page, described for the browser.
 *
 * <p>The form is not written out by hand anywhere. Every number and switch comes from
 * {@link Settings#ALL}, which already carries the section, the type, the default, the lowest
 * legal value, the unit and a sentence of help for each one - so a setting added in Java
 * appears in the page by itself, with its own explanation, and one that is renamed takes its
 * old name along as {@code oldNames}.
 *
 * <p>Three things are not in that list and are added here, because they are not numbers:
 * the folders, the quality ladder, and sockseek's own block. The last one is passed through
 * untouched by design, so every key in it is offered as a plain field - the ones we have a
 * sentence for carry it, and the ones we do not are still editable rather than hidden.
 */
final class Fields {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What each block of the file is for, in the page's own words. */
    private static final Map<String, String[]> SECTIONS = new LinkedHashMap<>(Map.of(
            "paths", new String[]{"Folders", "where the tool reads and writes"},
            "track", new String[]{"One track", "what counts as the right file"},
            "playlist", new String[]{"One playlist", "how long it may take, and how often"},
            "rate_limit", new String[]{"Search limit",
                    "how fast the account may search; Soulseek bans one that goes faster"},
            "cache", new String[]{"Cache", "what is not searched for again"}));

    /** The folders, in the order the file has them. */
    private static final String[][] PATHS = {
            {"sockseek_bin", "the sockseek binary; .exe is added or dropped to match"},
            {"playlists", "input, one .txt per playlist"},
            {"music", "output, one sub-folder per playlist"},
            {"work", "scratch, rebuilt every run, safe to delete"},
            {"logs", "run logs, session logs, the run counter"}};

    private Fields() {
    }

    /**
     * Every category, every field, and what is in the file right now.
     *
     * @param said the file as it reads today, key by full name, exactly as written - units
     *             and all, because {@code 60min} is what the person typed
     */
    static String describe(Map<String, String> said, Map<String, String> sockseekBlock) {
        ObjectNode out = JSON.createObjectNode();
        ArrayNode categories = out.putArray("categories");
        Set<String> placed = new LinkedHashSet<>();

        ObjectNode folders = category(categories, "paths");
        ArrayNode fields = folders.putArray("fields");
        for (String[] folder : PATHS) {
            String full = "paths." + folder[0];
            placed.add(full);
            field(fields, full, folder[0], "text", said.get(full), null, folder[1], List.of());
        }

        for (String section : List.of("track", "playlist", "rate_limit", "cache")) {
            ObjectNode block = category(categories, section);
            ArrayNode theseFields = block.putArray("fields");
            if (section.equals("track")) {
                // Two keys that belong in one control: the order of the formats, and the
                // worst quality accepted of each. They are separate in the file on purpose
                // (2026-10-20) and would be senseless apart on the page
                String formats = said.get("track." + Settings.FORMATS);
                if (formats == null) {
                    formats = said.get("track." + Settings.FORMAT_ORDER);   // the old name
                }
                placed.add("track." + Settings.FORMATS);
                placed.add("track." + Settings.FORMAT_ORDER);
                ObjectNode ladder = fieldNode(theseFields, "track." + Settings.FORMATS,
                        Settings.FORMATS, "ladder", formats, "",
                        "a pass each over whatever is still missing, in this order; nothing "
                                + "outside the list is taken at all. There is no last pass "
                                + "that takes anything: below its floor, a format is not "
                                + "taken", List.of());
                // the floor of each format travels with it, so the page can show one row
                ObjectNode floors = ladder.putObject("floors");
                said.keySet().stream()
                        .filter(name -> name.startsWith("track.")
                                && name.endsWith(Settings.FLOOR_SUFFIX))
                        .forEach(name -> {
                            placed.add(name);
                            String format = name.substring("track.".length(),
                                    name.length() - Settings.FLOOR_SUFFIX.length());
                            floors.put(format, said.get(name));
                        });
                ladder.put("floorSuffix", Settings.FLOOR_SUFFIX);
            }
            for (Setting setting : Settings.ALL) {
                if (!setting.section().equals(section)) {
                    continue;
                }
                String full = setting.fullName();
                placed.add(full);
                // a file written before a rename says the old name, and the value in it is
                // the one in force - so the field shows it, and saving writes the new name
                String value = said.get(full);
                for (String old : setting.oldKeys()) {
                    String was = setting.section() + "." + old;
                    placed.add(was);
                    if (value == null) {
                        value = said.get(was);
                    }
                }
                field(theseFields, full, setting.key(), kindOf(setting), value,
                        String.valueOf(setting.fallback()), setting.help(), setting.oldKeys());
            }
        }

        // sockseek's own block. The words for each key come from Knob, which the block
        // printed before a run reads too - one table, so the page and the run agree
        for (Map.Entry<String, String[]> block : Knob.BLOCKS.entrySet()) {
            ObjectNode their = categories.addObject();
            their.put("id", "sockseek." + block.getKey());
            their.put("title", "sockseek: " + block.getValue()[0]);
            their.put("help", block.getValue()[1]);
            ArrayNode theseFields = their.putArray("fields");
            for (Knob knob : Knob.in(block.getKey())) {
                String name = "sockseek." + knob.key();
                if (!said.containsKey(name)) {
                    continue;   // not in the file: sockseek decides it, and we do not guess
                }
                placed.add(name);
                field(theseFields, name, knob.key(), theirKind(said.get(name)),
                        said.get(name), null, knob.about(), List.of());
            }
        }

        // whatever else the file holds, so the page can show and keep it
        ArrayNode rest = out.putArray("unplaced");
        said.keySet().stream().filter(name -> !placed.contains(name)).forEach(name -> {
            ObjectNode one = rest.addObject();
            one.put("path", name);
            one.put("value", said.get(name));
        });
        out.put("hardConditions", String.join(", ", Settings.hardConditions(sockseekBlock)));
        return out.toString();
    }

    private static ObjectNode category(ArrayNode categories, String section) {
        String[] about = SECTIONS.get(section);
        ObjectNode block = categories.addObject();
        block.put("id", section);
        block.put("title", about[0]);
        block.put("help", about[1]);
        return block;
    }

    private static void field(ArrayNode fields, String path, String label, String kind,
                              String value, String fallback, String help,
                              List<String> oldNames) {
        fieldNode(fields, path, label, kind, value, fallback, help, oldNames);
    }

    /** The same, handing back the node so a field can carry more of its own. */
    private static ObjectNode fieldNode(ArrayNode fields, String path, String label,
                                        String kind, String value, String fallback,
                                        String help, List<String> oldNames) {
        ObjectNode one = fields.addObject();
        one.put("path", path);
        one.put("label", label);
        one.put("kind", kind);
        one.put("value", value == null ? "" : value);
        one.put("set", value != null);
        if (fallback != null) {
            one.put("fallback", fallback);
        }
        one.put("help", help);
        ArrayNode old = one.putArray("oldNames");
        oldNames.forEach(old::add);
        return one;
    }

    private static String kindOf(Setting setting) {
        return switch (setting.kind()) {
            case BOOLEAN -> "boolean";
            case INTEGER -> "integer";
            case DECIMAL -> "decimal";
        };
    }

    /** A sockseek value is text unless it reads like a switch or a number. */
    private static String theirKind(String value) {
        String said = value == null ? "" : value.strip().toLowerCase(java.util.Locale.ROOT);
        if (said.equals("true") || said.equals("false")) {
            return "boolean";
        }
        return said.matches("\\d+") ? "integer" : "text";
    }

    /** The path of a key, section first, as {@link dev.litetook.sockseek.config.Rewrite} wants it. */
    static List<String> pathOf(String fullName) {
        return List.of(fullName.split("\\."));
    }

    /** The earlier names of a setting, so a rewrite can rename the line it finds. */
    static List<String> oldNamesOf(String fullName) {
        List<String> path = pathOf(fullName);
        return Settings.byKey(path.get(path.size() - 1))
                .map(Setting::oldKeys)
                .orElse(List.of());
    }

}
