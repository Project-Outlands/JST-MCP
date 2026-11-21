package top.outlands.jst.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

class SemanticsTest {
    @TempDir
    Path dir;

    @Test
    void shadowedFieldStillReadsTheInstanceAndNotTheArgument() throws Exception {
        var in = zip(dir.resolve("input.zip"), Map.of("Box.java", """
                class Box {
                    int field_1_a = 37;
                    int read(int value) { return field_1_a; }
                    public static void main(String[] args) { System.out.print(new Box().read(99)); }
                }
                """));
        var csv = zip(dir.resolve("csv.zip"), Map.of("fields.csv", "searge,name\nfield_1_a,value\n", "methods.csv", "searge,name\n"));
        var out = dir.resolve("out.zip");
        jst(in, out, csv).success();
        Path source = Files.writeString(dir.resolve("Box.java"), entry(out, "Box.java"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", source.toString()));
        var read = java("-cp", dir.toString(), "Box");
        read.success();
        assertEquals("37", read.log());
    }

    @Test
    void unsafeRenamesReportPositionAndNeverWritePartOfTheArchive() throws Exception {
        var csv = zip(dir.resolve("csv.zip"), Map.of("fields.csv", "searge,name\n", "methods.csv", "searge,name\nfunc_1_a,read\n"));
        int i = 0;
        for (String broken : new String[] {
                "class Bad { void read(int x) {} void func_1_a(int x) {} }",
                "class Bad { void use(Missing x) { x.func_1_a(); } }",
                "class Bad { void read(String s) {} void func_1_a(Object o) {} void use() {func_1_a(\"x\");} }",
                "class Bad extends Base { void read(Object o) {} }",
                "interface I { default int func_1_a() {return 1;} } class Parent { public int read() {return 2;} } class Bad extends Parent implements I {}" }) {
            Path in = zip(dir.resolve("src" + i + ".zip"), Map.of("Bad.java", broken, "Base.java", "class Base { void func_1_a(Object o) {} }"));
            Path out = dir.resolve("out" + i++ + ".zip");
            var run = jst(in, out, csv);
            assertNotEquals(0, run.status(), broken);
            assertTrue(run.log().matches("(?s).*Bad\\.java:\\d+:\\d+:.*"), run.log());
            if (Files.exists(out)) try (var archive = new ZipFile(out.toFile())) {
                assertEquals(0, archive.size());
            }
        }
    }
}
