package top.outlands.jst.mcp;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static top.outlands.jst.mcp.Artifacts.entry;
import static top.outlands.jst.mcp.Artifacts.jst;
import static top.outlands.jst.mcp.Artifacts.zip;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemberRemapTest {
    @Test
    void renamesCrossFileReferencesWithoutTouchingIncidentalText(@TempDir Path dir) throws Exception {
        var csv = zip(dir.resolve("csv.zip"), Map.of("fields.csv", "searge,name\nfield_1_a,value\nfield_2_b,flag\n",
                "methods.csv", "searge,name\nfunc_3_a,read\nfunc_4_a,twice\n"));
        var in = zip(dir.resolve("src.zip"), Map.of(
                "ex/Base.java", """
                        package ex;
                        public class Base {
                            public int field_1_a = 7;
                            public static int field_2_b = 1;
                            public int func_3_a(int x) { return field_1_a + x; }
                            public int func_3_a(String s) { return s.length(); }
                            public static int func_4_a(int x) { return 2*x; }
                            public static int twice(String s) { return s.length()*2; }
                        }
                        """,
                "ex/package-info.java", "/** See {@link ex.Base#field_1_a}. */ package ex;",
                "ex/Child.java", """
                        package ex;
                        public class Child extends Base {
                            @Override public int func_3_a(int x) { this.field_1_a++; return super.func_3_a(x); }
                        }
                        """,
                "ex/Caller.java", """
                        package ex;
                        import static ex.Base.field_2_b;
                        import static ex.Base.func_4_a;
                        import java.util.function.ToIntBiFunction;
                        class Caller {
                            /** See {@link Base#func_3_a(int) the int overload}. */
                            int call(Child c) {
                                ToIntBiFunction<Base,String> f = Base::func_3_a;
                                // func_3_a is a reflection name here
                                String prefix_func_3_a = "func_3_a";
                                c.field_1_a = f.applyAsInt(c,prefix_func_3_a);
                                return c.func_3_a(func_4_a(field_2_b));
                            }
                        }
                        """));
        var out = dir.resolve("out.zip");
        jst(in, out, csv, "--mcp-mode=members").success();
        assertTrue(entry(out, "ex/Base.java").contains("int read(String s)"));
        assertTrue(entry(out, "ex/Child.java").contains("int read(int x) { this.value++; return super.read(x); }"));
        assertTrue(entry(out, "ex/package-info.java").contains("ex.Base#value"));
        String caller = entry(out, "ex/Caller.java");
        for (var expected : new String[] { "import static ex.Base.flag;", "import static ex.Base.twice;", "Base::read",
                "Base#read(int) the int overload", "c.value =", "c.read(twice(flag))", "// func_3_a", "prefix_func_3_a = \"func_3_a\"" }) {
            assertTrue(caller.contains(expected), caller);
        }
    }
}
