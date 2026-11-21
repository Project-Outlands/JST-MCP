package top.outlands.jst.mcp.source;

import com.intellij.openapi.roots.PackageIndex;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import net.neoforged.jst.api.Replacements;
import net.neoforged.jst.api.SourceTransformer;
import net.neoforged.jst.api.TransformContext;
import picocli.CommandLine.Option;
import top.outlands.jst.mcp.mapping.McpMappings;

public final class McpTransformer implements SourceTransformer {
    enum Mode {
        full, members, metadata
    }

    @Option(names = "--mcp-csv", required = true, description = "Legacy MCP CSV ZIP.")
    Path csv;
    @Option(names = "--mcp-mode", description = "One of full (default), metadata, members.")
    Mode mode = Mode.full;
    @Option(names = "--mcp-java-release", description = "Target Java release for source and JDK API resolution (default: running JDK).")
    Integer javaRelease;

    private final ConcurrentHashMap<String, String> changed = new ConcurrentHashMap<>();
    private VirtualFile root;

    @Override
    public void beforeRun(TransformContext context) {
        try {
            var mappings = McpMappings.load(csv);
            var project = context.environment().getProjectEnv().getProject();
            root = context.source().createSourceRoot(VirtualFileManager.getInstance());
            var sources = new TreeMap<String, String>();
            try (var files = context.source().streamEntries()) {
                files.filter(e -> !e.directory() && e.hasExtension("java")).forEach(entry -> {
                    var path = entry.relativePath();
                    var vf = root.findFileByRelativePath(path);
                    var file = vf == null ? null : context.environment().getPsiManager().findFile(vf);
                    if (file == null) throw new IllegalArgumentException(path + ":1:1: JST cannot load Java source");
                    if (sources.put(path, file.getViewProvider().getContents().toString()) != null)
                        throw new IllegalArgumentException(path + ": duplicate Java entry");
                });
            }
            var libraries = new ArrayList<Path>();
            for (var dir : PackageIndex.getInstance(project).getDirectoriesByPackageName("", true)) {
                if (dir.equals(root)) continue;
                switch (dir.getFileSystem().getProtocol()) {
                    case "file" -> libraries.add(Path.of(dir.getPath()));
                    case "jar" -> libraries.add(Path.of(dir.getPath().substring(0, dir.getPath().lastIndexOf("!/"))));
                    case "jrt" -> {} // javac already sees the running JDK's modules.
                    default -> throw new IllegalArgumentException("Unsupported classpath root: " + dir.getUrl());
                }
            }
            var engine = new SourceRemapper(project, libraries, mappings, javaRelease, message -> context.logger().error("%s", message));
            var output = engine.transform(sources, mode);
            changed.clear();
            output.forEach((path, text) -> {
                if (!text.equals(sources.get(path))) changed.put(path, text);
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void visitFile(PsiFile file, Replacements replacements) {
        var path = VfsUtilCore.getRelativePath(file.getVirtualFile(), root, '/');
        var result = changed.remove(path);
        if (result != null) replacements.replace(file, result);
    }

    @Override
    public boolean afterRun(TransformContext context) {
        if (changed.isEmpty()) return true;
        context.logger().error("JST skipped files required by MCP remapping: %s", changed.keySet());
        return false;
    }
}
