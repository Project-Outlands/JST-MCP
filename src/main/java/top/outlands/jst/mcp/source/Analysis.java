package top.outlands.jst.mcp.source;

import com.intellij.openapi.project.Project;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import top.outlands.jst.mcp.mapping.McpMappings;

/** One attributed view of the whole input. No compiler/PSI trees escape a pass. */
final class Analysis {
    final Changes changes = new Changes();
    final Map<Changes.Site, String> bindings = new LinkedHashMap<>();
    final List<String> errors = new ArrayList<>();
    final Map<String, String> parameterGroups = new LinkedHashMap<>();

    static Analysis run(Project project, List<Path> libraries, List<String> options, Map<String, String> sources, McpMappings mappings,
            McpTransformer.Mode planning) throws IOException {
        var result = new Analysis();
        if (sources.isEmpty()) return result;
        var javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) throw new IllegalStateException("MCP source remapping requires a JDK, not a JRE");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var fm = javac.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            fm.setLocationFromPaths(StandardLocation.CLASS_PATH, libraries);
            fm.setLocationFromPaths(StandardLocation.SOURCE_PATH, libraries);
            var files = sources.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(e -> new Source(e.getKey(), e.getValue())).toList();
            var task = (JavacTask) javac.getTask(null, fm, diagnostics, options, null, files);
            var units = new ArrayList<CompilationUnitTree>();
            task.parse().forEach(units::add);
            task.analyze();
            for (var d : diagnostics.getDiagnostics()) if (d.getKind() == Diagnostic.Kind.ERROR) {
                result.errors.add((d.getSource() == null ? "<input>" : d.getSource().getName()) + ":" + d.getLineNumber() + ":" + d.getColumnNumber() + ": " + d.getMessage(Locale.ROOT));
            }
            if (planning != null && !result.errors.isEmpty()) return result;
            var trees = DocTrees.instance(task);
            var symbols = new Symbols(task.getElements(), task.getTypes(), mappings);
            for (var unit : units) symbols.index(unit, trees);
            for (var unit : units) {
                var path = unit.getSourceFile().getName();
                new SourceVisitor(project, path, sources.get(path), unit, trees, symbols, mappings, planning, result).scan(unit, null);
            }
        }
        return result;
    }

    private static final class Source extends SimpleJavaFileObject {
        final String path, text;

        Source(String path, String text) {
            super(uri(path), Kind.SOURCE);
            this.path = path;
            this.text = text;
        }

        @Override
        public CharSequence getCharContent(boolean ignored) {
            return text;
        }

        @Override
        public String getName() {
            return path;
        }

        static URI uri(String path) {
            try {
                return new URI("mem", null, "/" + path, null);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException(path, e);
            }
        }
    }

    static final class Symbols {
        final Elements elements;
        final Types types;
        final Map<Element, String> source = new IdentityHashMap<>();
        final Set<String> methodNames = new HashSet<>();
        final Map<Element, String> external = new IdentityHashMap<>();

        Symbols(Elements elements, Types types, McpMappings mappings) {
            this.elements = elements;
            this.types = types;
            methodNames.addAll(mappings.methods().keySet());
            mappings.methods().values().forEach(m -> methodNames.add(m.name()));
        }

        String inheritedMethods(TypeElement type) {
            var visible = new TreeSet<String>();
            var byName = new HashMap<String, List<ExecutableElement>>();
            for (var e : elements.getAllMembers(type)) {
                if (e.getKind() != ElementKind.METHOD || e.getEnclosingElement() == type || !methodNames.contains(e.getSimpleName().toString())) continue;
                visible.add(id(e));
                byName.computeIfAbsent(e.getSimpleName().toString(), k -> new ArrayList<>()).add((ExecutableElement) e);
            }
            for (var group : byName.values()) for (var first : group) for (var second : group) {
                if (first != second && elements.overrides(first, second, type)) visible.add(id(first) + " overrides " + id(second));
            }
            return visible.toString();
        }

        // A rename changes spelling and source offsets but never declaration order.
        void index(CompilationUnitTree unit, Trees trees) {
            new TreePathScanner<Void, Void>() {
                int ordinal;

                void register() {
                    var e = trees.getElement(getCurrentPath());
                    if (e != null) source.put(e, unit.getSourceFile().getName() + "@" + ordinal++);
                }

                @Override
                public Void visitClass(ClassTree n, Void p) {
                    register();
                    return super.visitClass(n, p);
                }

                @Override
                public Void visitMethod(MethodTree n, Void p) {
                    register();
                    return super.visitMethod(n, p);
                }

                @Override
                public Void visitVariable(VariableTree n, Void p) {
                    register();
                    return super.visitVariable(n, p);
                }

                @Override
                public Void visitTypeParameter(TypeParameterTree n, Void p) {
                    register();
                    return super.visitTypeParameter(n, p);
                }
            }.scan(unit, null);
        }

        String id(Element e) {
            if (e == null) return "<unresolved>";
            String id = source.get(e);
            if (id != null) return id;
            id = external.get(e);
            if (id == null) {
                id = externalId(e);
                external.put(e, id);
            }
            return id;
        }

        private String externalId(Element e) {
            if (e instanceof TypeElement t) return elements.getBinaryName(t).toString();
            if (e instanceof PackageElement p) return p.getQualifiedName().toString();
            if (e instanceof ModuleElement m) return m.getQualifiedName().toString();
            return id(e.getEnclosingElement()) + "#" + e.getKind() + ":" + e.getSimpleName() +
                    (e instanceof ExecutableElement m ? m.getParameters().stream().map(v -> types.erasure(v.asType()).toString()).toList().toString() : "");
        }

        String overrides(ExecutableElement method) {
            var owner = (TypeElement) method.getEnclosingElement();
            var supers = new TreeSet<String>();
            var seen = new HashSet<Element>();
            var queue = new ArrayDeque<javax.lang.model.type.TypeMirror>(types.directSupertypes(owner.asType()));
            while (!queue.isEmpty()) {
                var type = queue.removeFirst();
                var base = types.asElement(type);
                if (base == null || !seen.add(base)) continue;
                for (var member : base.getEnclosedElements()) {
                    if (member instanceof ExecutableElement m && m.getSimpleName().equals(method.getSimpleName()) && elements.overrides(method, m, owner)) supers.add(id(m));
                }
                queue.addAll(types.directSupertypes(type));
            }
            return supers.toString();
        }
    }
}
