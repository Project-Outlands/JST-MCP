package top.outlands.jst.mcp.source;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Edits use original UTF-16 offsets, as do both javac source positions and JST ranges. */
final class Changes {
    record Site(String file, int end, String role) {}

    record Edit(String file, int start, int end, String text, String group) {}

    static final String REQUIRED = "";
    final List<Edit> edits = new ArrayList<>();
    final Map<Site, String> receivers = new HashMap<>();

    record Applied(Map<String, String> sources, Map<String, Offsets> offsets) {
        Site translate(Site site) {
            var offset = offsets.get(site.file);
            return offset == null ? site : new Site(site.file, offset.translate(site.end), site.role);
        }
    }

    static final class Offsets {
        final int[] ends, differences;

        Offsets(int size) {
            ends = new int[size];
            differences = new int[size];
        }

        int translate(int end) {
            int lo = 0, hi = ends.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (ends[mid] <= end) lo = mid + 1;
                else hi = mid;
            }
            return end + (lo == 0 ? 0 : differences[lo - 1]);
        }
    }

    Applied apply(Map<String, String> input, Set<String> groups, Set<Site> qualify) {
        var byFile = new HashMap<String, List<Edit>>();
        for (var e : edits) if (e.group.isEmpty() || groups.contains(e.group))
            byFile.computeIfAbsent(e.file, k -> new ArrayList<>()).add(e);
        var output = new HashMap<>(input);
        var translations = new HashMap<String, Offsets>();
        byFile.forEach((file, edits) -> {
            edits.sort(Comparator.comparingInt(Edit::start).thenComparingInt(Edit::end));
            String source = input.get(file);
            var text = new StringBuilder(source.length());
            var offsets = new Offsets(edits.size());
            int pos = 0, i = 0;
            for (var edit : edits) {
                if (edit.start < pos) throw new IllegalStateException(file + ": overlapping MCP edits at " + edit.start);
                text.append(source, pos, edit.start);
                var site = new Site(file, edit.end, "ref");
                if (qualify.contains(site)) text.append(receivers.get(site));
                text.append(edit.text);
                pos = edit.end;
                offsets.ends[i] = pos + (edit.start == pos ? 1 : 0);
                offsets.differences[i++] = text.length() - pos;
            }
            output.put(file, text.append(source, pos, source.length()).toString());
            translations.put(file, offsets);
        });
        return new Applied(output, translations);
    }
}
