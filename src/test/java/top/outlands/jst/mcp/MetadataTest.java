package top.outlands.jst.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static top.outlands.jst.mcp.Artifacts.entry;
import static top.outlands.jst.mcp.Artifacts.jst;
import static top.outlands.jst.mcp.Artifacts.zip;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetadataTest {
    @TempDir
    Path dir;

    @Test
    void docsAndParametersUseTheOriginalDeclarationAndExactKey() throws Exception {
        var csv = zip(dir.resolve("csv.zip"), Map.of("fields.csv", "searge,name,side,desc\nfield_1_,value,2,\"Has a comma, a \"\"quote\"\"\nand two lines.\"\n",
                "methods.csv", "searge,name,side,desc\nfunc_2_,get,2,Must not duplicate\n",
                "params.csv", "param,name,side\np_i42_1_,seed,2\np_77_1_,count,2\np_77_2_,x,2\n"));
        var in = zip(dir.resolve("src.zip"), Map.of("Box.java", """
                class Box {
                    int field_1_;
                    Box(int p_i42_1_) { field_1_ = p_i42_1_; }
                    /** Existing {@link #field_1_}. */
                    int func_2_() { return field_1_; }
                    /** @param p_77_1_ not a member of methods.csv */
                    int untouched(int p_77_1_, int p_77_2_) { int x = 5; return x+p_77_1_+p_77_2_; }
                }
                """));
        var out = dir.resolve("out.zip");
        var run = jst(in, out, csv, "--mcp-mode=metadata");
        run.success();
        var text = entry(out, "Box.java");
        assertTrue(text.contains("Has a comma, a \"quote\"\n     * and two lines."), text);
        assertEquals(text.indexOf("Has a comma"), text.lastIndexOf("Has a comma"));
        assertFalse(text.contains("Must not duplicate"));
        assertTrue(text.contains("/** Existing {@link #field_1_}. */"));
        assertTrue(text.contains("Box(int seed) { field_1_ = seed; }"));
        assertTrue(text.contains("@param count not a member"));
        assertTrue(text.contains("int untouched(int count, int p_77_2_) { int x = 5; return x+count+p_77_2_; }"));
        assertTrue(run.log().contains("p_77_2_"), run.log());
    }

    @Test
    void refusesToAttachOneFieldsDocToItsNeighbor() throws Exception {
        var in = zip(dir.resolve("src.zip"), Map.of("Box.java", "class Box { int untouched, field_1_; }"));
        var csv = zip(dir.resolve("csv.zip"), Map.of("methods.csv", "searge,name\n", "fields.csv", "searge,name,desc\nfield_1_,value,Only about value\n"));
        var run = jst(in, dir.resolve("out.zip"), csv);
        assertNotEquals(0, run.status());
        assertTrue(run.log().contains("Box.java:1:"), run.log());
    }

    @Test
    void requiresFieldsAndMethodsButNotParams(@TempDir Path dir) throws Exception {
        var in = zip(dir.resolve("src.zip"), Map.of("Box.java", "class Box {}"));
        for (String missing : new String[] { "fields.csv", "methods.csv" }) {
            var key = missing.equals("fields.csv") ? "methods.csv" : "fields.csv";
            var csv = zip(dir.resolve(key + ".zip"), Map.of(key, "searge,name\n"));
            var run = jst(in, dir.resolve(key + "-out.zip"), csv);
            assertNotEquals(0, run.status());
            assertTrue(run.log().contains(missing), run.log());
        }
    }
}
