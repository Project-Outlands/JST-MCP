package top.outlands.jst.mcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static top.outlands.jst.mcp.Artifacts.JAR;
import static top.outlands.jst.mcp.Artifacts.entry;
import static top.outlands.jst.mcp.Artifacts.java;
import static top.outlands.jst.mcp.Artifacts.jst;
import static top.outlands.jst.mcp.Artifacts.zip;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipFile;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DistributionTest {
    @Test
    void packagedPluginAndStandaloneCommandHaveNoHiddenRuntimeDependency(@TempDir Path dir) throws Exception {
        try (var jar = new ZipFile(JAR)) {
            assertNotNull(jar.getEntry("META-INF/services/net.neoforged.jst.api.SourceTransformerPlugin"));
            assertFalse(jar.stream().anyMatch(e -> e.getName().startsWith("com/intellij/") || e.getName().startsWith("net/neoforged/jst/")));
        }
        java("-jar", JAR, "--help").success();
        var lib = Files.createDirectory(dir.resolve("lib"));
        var library = Files.writeString(dir.resolve("Dependency.java"), "package api; public class Dependency { public int take(int x) { return x; } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-d", lib.toString(), library.toString()));
        var list = Files.writeString(dir.resolve("libraries.txt"), "-e=" + lib + "\n");
        var csv = zip(dir.resolve("data.zip"), Map.of("methods.csv", "searge,name,desc\nfunc_1_a,read,Reads {@link #field_2_a}.\n",
                "fields.csv", "searge,name\nfield_2_a,value\n", "params.csv", "param,name\np_1_1_,value\n"));
        var in = zip(dir.resolve("src.zip"), Map.of("Box.java", "class Box extends api.Dependency { java.util.jar.Pack200.Packer packer; int field_2_a = 6; int func_1_a(int p_1_1_) { return take(field_2_a) + p_1_1_; } }",
                "assets/data.bin", "\u0000\u00ff\r\nfunc_1_a"));
        var meta = dir.resolve("meta.zip");
        var steps = dir.resolve("steps.zip");
        var full = dir.resolve("full.zip");
        jst(in, full, csv, "--mcp-java-release=8", "--libraries-list", list.toString()).success();
        jst(in, meta, csv, "--mcp-java-release=8", "--classpath", lib.toString(), "--mcp-mode=metadata").success();
        jst(meta, steps, csv, "--mcp-java-release=8", "--classpath", lib.toString(), "--mcp-mode=members").success();
        assertEquals(entry(steps, "Box.java"), entry(full, "Box.java"));
        String result = entry(full, "Box.java");
        assertTrue(result.contains("Reads {@link #value}."), result);
        assertTrue(result.contains("int read(int value) { return take(this.value) + value; }"), result);
        try (var original = new ZipFile(in.toFile()); var transformed = new ZipFile(full.toFile())) {
            assertArrayEquals(original.getInputStream(original.getEntry("assets/data.bin")).readAllBytes(),
                    transformed.getInputStream(transformed.getEntry("assets/data.bin")).readAllBytes());
        }
    }
}
