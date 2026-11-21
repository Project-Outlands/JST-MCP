package top.outlands.jst.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static top.outlands.jst.mcp.Artifacts.JAR;
import static top.outlands.jst.mcp.Artifacts.java;
import static top.outlands.jst.mcp.Artifacts.zip;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MappingExportTest {
    @Test
    void exportsBothDirectionsAndOnlyCompleteParameterOrdinals(@TempDir Path dir) throws Exception {
        var csv = zip(dir.resolve("data.zip"), Map.of(
                "methods.csv", "searge,name,side,desc\nfunc_2_a,store,2,\n",
                "fields.csv", "searge,name,side,desc\nfield_1_a,value,2,\n",
                "params.csv", "param,name,side\np_2_1_,bits,2\np_2_3_,scale,2\np_2_5_,array,2\np_i3_0_,seed,2\np_4_2_,incomplete,2\n"));
        var input = Files.writeString(dir.resolve("input.tsrg"), "a sample/Box\n\tb field_1_a\n\tc (JD[La;)La; func_2_a\n\td (I)V func_3_a\n\te (II)V func_4_a\n");
        var out = dir.resolve("export");
        java("-jar", JAR, "--csv", csv.toString(), "--obf-to-srg", input.toString(), "--output", out.toString()).success();
        assertEquals(-1, Files.mismatch(csv, out.resolve("csvMappings.zip")));
        assertTrue(Files.readString(out.resolve("notchToSrg.srg")).contains("CL: a sample/Box"));
        assertTrue(Files.readString(out.resolve("srgToMcp.srg")).contains("FD: sample/Box/field_1_a sample/Box/value"));
        assertTrue(Files.readString(out.resolve("mcpToSrg.srg")).contains("FD: sample/Box/value sample/Box/field_1_a"));
        assertTrue(Files.readAllLines(out.resolve("srgToMcp.tsrg")).contains("\tfunc_2_a (JD[Lsample/Box;)Lsample/Box; store"));
        assertTrue(Files.readAllLines(out.resolve("mcpToSrg.tsrg")).contains("\tstore (JD[Lsample/Box;)Lsample/Box; func_2_a"));
        var tsrg2 = Files.readAllLines(out.resolve("srgToMcp.tsrg2"));
        assertEquals("tsrg2 left right", tsrg2.get(0));
        assertTrue(tsrg2.containsAll(java.util.List.of("\t\t0 p_2_1_ bits", "\t\t1 p_2_3_ scale", "\t\t2 p_2_5_ array", "\t\t0 p_i3_0_ seed")));
        assertFalse(tsrg2.stream().anyMatch(line -> line.contains("incomplete")));
    }
}
