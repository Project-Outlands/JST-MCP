package top.outlands.jst.mcp.source;

import com.intellij.openapi.project.Project;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import top.outlands.jst.mcp.mapping.McpMappings;

final class SourceRemapper {
    private final Project project;
    private final List<Path> libraries;
    private final McpMappings mappings;
    private final Consumer<String> warning;

    private final List<String> compilerOptions;

    SourceRemapper(Project project, List<Path> libraries, McpMappings mappings, Integer release, Consumer<String> warning) {
        this.project = project;
        this.libraries = libraries;
        this.mappings = mappings;
        this.warning = warning;
        var options = new ArrayList<>(List.of("-proc:none", "-implicit:none", "-Xlint:none"));
        if (release != null) options.addAll(List.of("--release", release.toString()));
        this.compilerOptions = List.copyOf(options);
    }

    Map<String, String> transform(Map<String, String> sources, McpTransformer.Mode mode) throws IOException {
        if (mode != McpTransformer.Mode.members) sources = phase(sources, McpTransformer.Mode.metadata);
        if (mode != McpTransformer.Mode.metadata) sources = phase(sources, McpTransformer.Mode.members);
        return sources;
    }

    private Map<String, String> phase(Map<String, String> sources, McpTransformer.Mode mode) throws IOException {
        var before = Analysis.run(project, libraries, compilerOptions, sources, mappings, mode);
        if (!before.errors.isEmpty()) throw new IllegalArgumentException(String.join("\n", before.errors));
        if (before.changes.edits.isEmpty()) return sources;
        var applied = before.changes.apply(sources, before.parameterGroups.keySet(), Set.of());
        var check = check(before, applied, sources);
        if (check.error == null) return applied.sources();
        if (!check.repair.isEmpty()) {
            applied = before.changes.apply(sources, before.parameterGroups.keySet(), check.repair);
            check = check(before, applied, sources);
            if (check.error == null) return applied.sources();
        }
        if (mode == McpTransformer.Mode.metadata && !before.parameterGroups.isEmpty()) {
            var accepted = new HashSet<String>();
            var docs = before.changes.apply(sources, accepted, Set.of());
            var docsCheck = check(before, docs, sources);
            if (docsCheck.error != null) throw new IllegalArgumentException(docsCheck.error);
            keepSafeParameters(before, sources, List.copyOf(before.parameterGroups.keySet()), accepted);
            return before.changes.apply(sources, accepted, Set.of()).sources();
        }
        throw new IllegalArgumentException(check.error);
    }

    // Only on conflict: isolate whole parameter groups, including references and @param tags.
    private void keepSafeParameters(Analysis before, Map<String, String> sources, List<String> candidates, Set<String> accepted) throws IOException {
        var trial = new HashSet<>(accepted);
        trial.addAll(candidates);
        var checked = check(before, before.changes.apply(sources, trial, Set.of()), sources);
        if (checked.error == null) {
            accepted.addAll(candidates);
            return;
        }
        if (candidates.size() == 1) {
            warning.accept("Skipping parameter " + before.parameterGroups.get(candidates.getFirst()) + ": " + checked.error);
            return;
        }
        int mid = candidates.size() / 2;
        keepSafeParameters(before, sources, candidates.subList(0, mid), accepted);
        keepSafeParameters(before, sources, candidates.subList(mid, candidates.size()), accepted);
    }

    private record Check(String error, Set<Changes.Site> repair) {}

    private Check check(Analysis before, Changes.Applied edits, Map<String, String> original) throws IOException {
        var after = Analysis.run(project, libraries, compilerOptions, edits.sources(), mappings, null);
        String error = after.errors.isEmpty() ? null : after.errors.getFirst();
        var repair = new HashSet<Changes.Site>();
        for (var entry : before.bindings.entrySet()) {
            var site = entry.getKey();
            var target = after.bindings.get(edits.translate(site));
            if (!Objects.equals(entry.getValue(), target)) {
                if (before.changes.receivers.containsKey(site)) repair.add(site);
                if (error == null) {
                    String prefix = original.get(site.file()).substring(0, site.end());
                    error = site.file() + ":" + (1 + prefix.chars().filter(c -> c == '\n').count()) + ":" + (prefix.length() - prefix.lastIndexOf('\n'))
                            + ": rename changes " + site.role() + " target from " + entry.getValue() + " to " + target;
                }
            }
        }
        return new Check(error, repair);
    }
}
