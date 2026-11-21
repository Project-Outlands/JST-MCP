package top.outlands.jst.mcp.export;

import static net.neoforged.srgutils.IMappingFile.Format.SRG;
import static net.neoforged.srgutils.IMappingFile.Format.TSRG;
import static net.neoforged.srgutils.IMappingFile.Format.TSRG2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import net.neoforged.srgutils.IMappingBuilder;
import net.neoforged.srgutils.IMappingFile;
import top.outlands.jst.mcp.mapping.McpMappings;

public final class ExportMain {
    private static final String USAGE = "java -jar jst-mcp.jar --csv <mcp.zip> --obf-to-srg <mapping> --output <new-directory>";

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println(USAGE);
            return;
        }
        try {
            var options = new HashMap<String, Path>();
            if (args.length != 6) throw new IllegalArgumentException(USAGE);
            for (int i = 0; i < args.length; i += 2) options.put(args[i], Path.of(args[i + 1]));
            if (!options.keySet().equals(Set.of("--csv", "--obf-to-srg", "--output"))) throw new IllegalArgumentException(USAGE);
            export(options.get("--csv"), options.get("--obf-to-srg"), options.get("--output").toAbsolutePath());
        } catch (IOException | RuntimeException e) {
            System.err.println("MCP mapping export: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void export(Path csv, Path mapping, Path output) throws IOException {
        if (Files.exists(output)) throw new IOException("output already exists: " + output);
        var names = McpMappings.load(csv);
        var params = new ParameterOrdinals(names.parameters());
        var obf = IMappingFile.load(mapping.toFile());
        var builder = IMappingBuilder.create("srg", "mcp");
        var classes = new HashSet<String>();
        for (var cls : obf.getClasses()) {
            unique(classes, "class", cls.getMapped());
            var named = builder.addClass(cls.getMapped(), cls.getMapped());
            var fields = new HashSet<String>();
            for (var field : cls.getFields()) {
                String from = field.getMapped();
                String to = names.fields().containsKey(from) ? names.fields().get(from).name() : from;
                unique(fields, cls.getMapped(), to);
                var result = named.field(from, to);
                if (field.getMappedDescriptor() != null) result.descriptor(field.getMappedDescriptor());
            }
            var methods = new HashSet<String>();
            for (var method : cls.getMethods()) {
                String from = method.getMapped(), desc = method.getMappedDescriptor();
                var rename = names.methods().get(from);
                String to = rename == null || from.startsWith("<") ? from : rename.name();
                unique(methods, cls.getMapped(), to + desc);
                var result = named.method(desc, from, to);
                method.getMetadata().forEach(result::meta);
                var ordered = params.forMethod(from, desc, method.getMetadata().containsKey("is_static"));
                for (int i = 0; i < ordered.size(); i++) result.parameter(i, ordered.get(i).key(), ordered.get(i).name());
            }
        }
        var forward = builder.build().getMap("srg", "mcp");
        var reverse = forward.reverse();
        Files.createDirectories(output.getParent());
        var stage = Files.createTempDirectory(output.getParent(), ".mcp-export-");
        try {
            forward.write(stage.resolve("srgToMcp.srg"), SRG, false);
            forward.write(stage.resolve("srgToMcp.tsrg"), TSRG, false);
            forward.write(stage.resolve("srgToMcp.tsrg2"), TSRG2, false);
            reverse.write(stage.resolve("mcpToSrg.srg"), SRG, false);
            reverse.write(stage.resolve("mcpToSrg.tsrg"), TSRG, false);
            obf.write(stage.resolve("notchToSrg.srg"), SRG, false);
            Files.copy(csv, stage.resolve("csvMappings.zip"));
            Files.move(stage, output);
        } finally {
            if (Files.exists(stage)) {
                try (var files = Files.newDirectoryStream(stage)) {
                    for (var file : files) Files.delete(file);
                }
                Files.delete(stage);
            }
        }
    }

    private static void unique(Set<String> seen, String owner, String key) throws IOException {
        if (!seen.add(key)) throw new IOException("non-invertible mapping: duplicate " + owner + "/" + key);
    }
}
