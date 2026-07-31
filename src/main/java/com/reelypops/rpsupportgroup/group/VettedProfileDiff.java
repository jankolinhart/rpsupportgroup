package com.reelypops.rpsupportgroup.group;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reelypops.rpsupportgroup.group.VettedProfileVersion.ChangeNoteEntry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Computes a structured, field-level diff between two {@link VettedProfile} snapshots (M5 A2 / #5.3). Both are flattened
 * to a map of JSON field PATH &rarr; stringified value; the union of paths is compared, yielding a
 * {@link ChangeNoteEntry} {@code {path, from, to}} for every field that changed / was added / was removed. Path + raw
 * values only (never composed prose) &mdash; the client renders them into the user's language.
 */
public final class VettedProfileDiff {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VettedProfileDiff() {
    }

    /** The field-level changes turning {@code before} into {@code after}; empty when unchanged (or both null). */
    public static List<ChangeNoteEntry> diff(VettedProfile before, VettedProfile after) {
        Map<String, String> a = flatten(before);
        Map<String, String> b = flatten(after);
        Set<String> paths = new HashSet<>(a.keySet());
        paths.addAll(b.keySet());
        List<ChangeNoteEntry> out = new ArrayList<>();
        for (String path : new TreeSet<>(paths)) {
            String from = a.get(path);
            String to = b.get(path);
            if (!Objects.equals(from, to)) {
                out.add(new ChangeNoteEntry(path, from, to));
            }
        }
        return out;
    }

    private static Map<String, String> flatten(VettedProfile profile) {
        Map<String, String> flat = new LinkedHashMap<>();
        if (profile != null) {
            flatten("", MAPPER.valueToTree(profile), flat);
        }
        return flat;
    }

    private static void flatten(String prefix, JsonNode node, Map<String, String> out) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                flatten(prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), e.getValue(), out);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                flatten(prefix + "[" + i + "]", node.get(i), out);
            }
        } else if (!node.isNull()) {
            // Null leaves are omitted (treated as "absent") so an add reads as from=null and a remove as to=null.
            out.put(prefix, node.asText());
        }
    }
}
