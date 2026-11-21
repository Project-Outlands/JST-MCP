package top.outlands.jst.mcp.mapping;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;
import javax.lang.model.SourceVersion;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.DuplicateHeaderMode;

public record McpMappings(Map<String, Member> fields, Map<String, Member> methods, Map<String, String> parameters) {
    public record Member(String name, String description) {}

    private static final CSVFormat FORMAT = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true)
            .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).setIgnoreEmptyLines(true).get();

    public static McpMappings load(Path path) throws IOException {
        try (var zip = new ZipFile(path.toFile())) {
            var params = new HashMap<String, String>();
            read(zip, "params.csv", "param", false).forEach((key, value) -> params.put(key, value.name));
            return new McpMappings(read(zip, "fields.csv", "searge", true), read(zip, "methods.csv", "searge", true), Map.copyOf(params));
        }
    }

    private static Map<String, Member> read(ZipFile zip, String file, String keyColumn, boolean required) throws IOException {
        var entry = zip.getEntry(file);
        if (entry == null || entry.isDirectory()) {
            if (!required) return Map.of();
            throw new IOException(zip.getName() + ": missing " + file);
        }
        try (var reader = new BufferedReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))) {
            reader.mark(1);
            if (reader.read() != '\uFEFF') reader.reset();
            try (var parser = FORMAT.parse(reader)) {
                var columns = parser.getHeaderMap();
                if (!columns.containsKey(keyColumn) || !columns.containsKey("name")) {
                    throw new IOException("expected columns '" + keyColumn + "' and 'name'");
                }
                var result = new HashMap<String, Member>();
                for (var row : parser) {
                    var key = row.get(keyColumn);
                    var name = row.get("name");
                    if (key.isBlank() || !SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) {
                        throw new IOException("invalid mapping '" + key + "' -> '" + name + "' at record " + row.getRecordNumber());
                    }
                    var mapping = new Member(name, row.isSet("desc") ? row.get("desc") : "");
                    var previous = result.putIfAbsent(key, mapping);
                    if (previous != null && !previous.equals(mapping)) throw new IOException("conflicting mappings for " + key);
                }
                return Map.copyOf(result);
            }
        } catch (RuntimeException | IOException e) {
            throw new IOException(zip.getName() + "!/" + file + ": " + e.getMessage(), e);
        }
    }
}
