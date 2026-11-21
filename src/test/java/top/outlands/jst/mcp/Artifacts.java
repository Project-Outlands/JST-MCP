package top.outlands.jst.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

class Artifacts {
    static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    static final String JAR = System.getProperty("mcp.jar");
    static final String BUNDLE = System.getProperty("jst.jar");

    static Path zip(Path path, Map<String, String> contents) throws IOException {
        try (var out = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : contents.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return path;
    }

    static String entry(Path archive, String name) throws IOException {
        try (var zip = new ZipFile(archive.toFile())) {
            return new String(zip.getInputStream(zip.getEntry(name)).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static Result jst(Path sources, Path output, Path csv, String... options) throws Exception {
        var args = new ArrayList<>(List.of("-cp", BUNDLE + java.io.File.pathSeparator + JAR,
                "net.neoforged.jst.cli.Main", "--enable-mcp", "--mcp-csv", csv.toString()));
        args.addAll(List.of(options));
        args.addAll(List.of(sources.toString(), output.toString()));
        return java(args.toArray(String[]::new));
    }

    record Result(int status, String log) {
        void success() {
            assertEquals(0, status, log);
        }
    }

    static Result java(String... args) throws Exception {
        var command = new ArrayList<>(List.of(JAVA, "-Duser.language=en", "-Dfile.encoding=UTF-8"));
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(process.waitFor(), log);
    }
}
