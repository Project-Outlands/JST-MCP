package top.outlands.jst.mcp.source;

import static top.outlands.jst.mcp.source.Changes.REQUIRED;

import com.intellij.openapi.project.Project;
import com.sun.source.doctree.ReferenceTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModuleTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.PackageTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.DocTreePath;
import com.sun.source.util.DocTreePathScanner;
import com.sun.source.util.DocTrees;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import top.outlands.jst.mcp.mapping.McpMappings;
import top.outlands.jst.mcp.source.Changes.Edit;
import top.outlands.jst.mcp.source.Changes.Site;

final class SourceVisitor extends TreePathScanner<Void, Void> {
    final Project project;
    final String file, text;
    final CompilationUnitTree unit;
    final DocTrees trees;
    final Analysis.Symbols symbols;
    final McpMappings mappings;
    final McpTransformer.Mode mode;
    final Analysis result;
    private DeclarationNames names;
    private final Set<Integer> compoundFields = new HashSet<>();
    private final Set<Integer> visitedDocs = new HashSet<>();

    SourceVisitor(Project project, String file, String text, CompilationUnitTree unit, DocTrees trees,
            Analysis.Symbols symbols, McpMappings mappings, McpTransformer.Mode mode, Analysis result) {
        this.project = project;
        this.file = file;
        this.text = text;
        this.unit = unit;
        this.trees = trees;
        this.symbols = symbols;
        this.mappings = mappings;
        this.mode = mode;
        this.result = result;
    }

    int start(Tree t) {
        return (int) trees.getSourcePositions().getStartPosition(unit, t);
    }

    int end(Tree t) {
        return (int) trees.getSourcePositions().getEndPosition(unit, t);
    }

    String location(int pos) {
        return file + ":" + unit.getLineMap().getLineNumber(pos) + ":" + unit.getLineMap().getColumnNumber(pos);
    }

    void fail(int pos, String why) {
        result.errors.add(location(Math.max(0, pos)) + ": " + why);
    }

    void bind(int end, String role, String id) {
        if (end >= 0) result.bindings.put(new Site(file, end, role), id);
    }

    Element element() {
        return trees.getElement(getCurrentPath());
    }

    String rename(Element e) {
        if (e == null) return null;
        String name = e.getSimpleName().toString();
        if (mode == McpTransformer.Mode.metadata && e.getKind() == ElementKind.PARAMETER) {
            var target = mappings.parameters().get(name);
            return name.equals(target) ? null : target;
        }
        if (mode != McpTransformer.Mode.members) return null;
        var member = switch (e.getKind()) {
            case FIELD, ENUM_CONSTANT -> mappings.fields().get(name);
            case METHOD -> mappings.methods().get(name);
            default -> null;
        };
        return member == null || name.equals(member.name()) ? null : member.name();
    }

    String group(Element e) {
        return e.getKind() == ElementKind.PARAMETER ? symbols.id(e) : REQUIRED;
    }

    void replace(int start, int end, Element e, String target) {
        if (!symbols.source.containsKey(e)) {
            fail(start, "mapped member " + e + " belongs to a dependency, not to the input sources");
            return;
        }
        result.changes.edits.add(new Edit(file, start, end, target, group(e)));
    }

    private void declaration(Tree tree, Element e) {
        if (end(tree) < 0 || e == null) return;
        var name = rename(e);
        if (name != null) {
            if (e.getKind() == ElementKind.PARAMETER) result.parameterGroups.put(group(e), location(start(tree)) + ": " + e + " -> " + name);
            if (names == null) names = new DeclarationNames(project, file, text);
            int bound = end(tree);
            if (tree instanceof VariableTree v && v.getInitializer() != null && start(v.getInitializer()) > start(tree)) bound = start(v.getInitializer());
            var range = names.between(start(tree), bound, e.getSimpleName().toString(), e instanceof ExecutableElement);
            if (range == null) fail(start(tree), "cannot locate declaration token for " + e);
            else replace(range.getStartOffset(), range.getEndOffset(), e, name);
        }
        if (mode == McpTransformer.Mode.metadata && trees.getDocCommentTree(getCurrentPath()) == null) {
            var member = switch (e.getKind()) {
                case FIELD, ENUM_CONSTANT -> mappings.fields().get(e.getSimpleName().toString());
                case METHOD -> mappings.methods().get(e.getSimpleName().toString());
                default -> null;
            };
            if (member != null && !member.description().isBlank()) {
                int pos = start(tree);
                if (compoundFields.contains(pos)) {
                    fail(pos, "cannot attach Javadoc for " + e + " to only one field of a compound declaration");
                    return;
                }
                int line = text.lastIndexOf('\n', pos - 1) + 1;
                int indentEnd = line;
                while (indentEnd < pos && (text.charAt(indentEnd) == ' ' || text.charAt(indentEnd) == '\t')) indentEnd++;
                String indent = text.substring(line, indentEnd);
                String body = member.description().replace("\\n", "\n").replace("\\", "&#92;").replace("*/", "*&#47;").replace("\r\n", "\n").replace('\r', '\n');
                String comment = "/**\n" + indent + " * " + body.replace("\n", "\n" + indent + " * ") + "\n" + indent + " */\n" + indent;
                result.changes.edits.add(new Edit(file, pos, pos, comment, REQUIRED));
            }
        }
        documentation();
    }

    @Override
    public Void visitPackage(PackageTree t, Void p) {
        documentation();
        return super.visitPackage(t, p);
    }

    @Override
    public Void visitModule(ModuleTree t, Void p) {
        documentation();
        return super.visitModule(t, p);
    }

    @Override
    public Void visitClass(ClassTree t, Void p) {
        if (mode == McpTransformer.Mode.metadata) {
            var starts = new HashSet<Integer>();
            for (var member : t.getMembers()) if (member instanceof VariableTree && !starts.add(start(member))) compoundFields.add(start(member));
        }
        documentation();
        if (element() instanceof TypeElement type) bind(end(t), "inherited methods", symbols.inheritedMethods(type));
        return super.visitClass(t, p);
    }

    @Override
    public Void visitMethod(MethodTree t, Void p) {
        if (element() instanceof ExecutableElement m && end(t) >= 0) {
            declaration(t, m);
            if (m.getKind() == ElementKind.METHOD && symbols.methodNames.contains(m.getSimpleName().toString())) bind(end(t), "overrides", symbols.overrides(m));
        }
        return super.visitMethod(t, p);
    }

    @Override
    public Void visitVariable(VariableTree t, Void p) {
        declaration(t, element());
        return super.visitVariable(t, p);
    }

    private void reference(Tree t, String spelling) {
        int end = end(t);
        if (end < 0) return; // javac inserts implicit calls and constructors during attribution
        var e = element();
        bind(end, "ref", symbols.id(e));
        var name = rename(e);
        if (name == null) return;
        int start = end - spelling.length();
        if (start < 0 || !text.startsWith(spelling, start) || !e.getSimpleName().contentEquals(spelling)) {
            fail(end, "cannot locate identifier '" + spelling + "'");
            return;
        }
        replace(start, end, e, name);
        if (t instanceof IdentifierTree && e.getKind().isField()) {
            var receiver = receiver(e);
            if (receiver != null) result.changes.receivers.put(new Site(file, end, "ref"), receiver);
        }
    }

    private String receiver(Element field) {
        var owner = (TypeElement) field.getEnclosingElement();
        if (field.getModifiers().contains(Modifier.STATIC) && !owner.getQualifiedName().isEmpty()) return owner.getQualifiedName() + ".";
        boolean inner = true;
        for (var path = getCurrentPath(); path != null; path = path.getParentPath()) {
            if (path.getLeaf() instanceof ClassTree && trees.getElement(path) instanceof TypeElement type) {
                if (symbols.types.isSubtype(symbols.types.erasure(type.asType()), symbols.types.erasure(owner.asType()))) {
                    if (inner) return "this.";
                    return type.getSimpleName().isEmpty() ? null : type.getSimpleName() + ".this.";
                }
                inner = false;
            }
        }
        return null;
    }

    @Override
    public Void visitIdentifier(IdentifierTree t, Void p) {
        reference(t, t.getName().toString());
        return null;
    }

    @Override
    public Void visitMemberSelect(MemberSelectTree t, Void p) {
        reference(t, t.getIdentifier().toString());
        return super.visitMemberSelect(t, p);
    }

    @Override
    public Void visitMemberReference(MemberReferenceTree t, Void p) {
        reference(t, t.getName().toString());
        return super.visitMemberReference(t, p);
    }

    @Override
    public Void visitNewClass(NewClassTree t, Void p) {
        bind(end(t), "new", symbols.id(element()));
        return super.visitNewClass(t, p);
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree t, Void p) {
        bind(end(t), "call", symbols.id(element()));
        return super.visitMethodInvocation(t, p);
    }

    @Override
    public Void visitImport(ImportTree t, Void p) {
        if (!t.isStatic() || !(t.getQualifiedIdentifier() instanceof MemberSelectTree ref) || ref.getIdentifier().contentEquals("*")) return super.visitImport(t, p);
        var owner = trees.getElement(TreePath.getPath(getCurrentPath(), ref.getExpression()));
        if (!(owner instanceof TypeElement type)) return null;
        String name = ref.getIdentifier().toString();
        var targetNames = new TreeSet<String>();
        for (var member : symbols.elements.getAllMembers(type)) {
            if (member.getModifiers().contains(Modifier.STATIC) && !member.getModifiers().contains(Modifier.PRIVATE) && member.getSimpleName().contentEquals(name)) {
                var id = symbols.id(member);
                bind(end(ref), "import " + id, id);
                String to = rename(member);
                if (to != null && !symbols.source.containsKey(member)) fail(start(ref), "mapped static import from dependency: " + member);
                targetNames.add(to == null ? name : to);
            }
        }
        if (targetNames.size() > 1) fail(start(ref), "static import " + name + " has incompatible target names " + targetNames);
        else if (targetNames.size() == 1 && !targetNames.contains(name)) {
            int pos = end(ref) - name.length();
            if (!text.startsWith(name, pos)) fail(pos, "cannot locate static import " + name);
            else result.changes.edits.add(new Edit(file, pos, end(ref), targetNames.first(), REQUIRED));
        }
        return null;
    }

    private void documentation() {
        var doc = trees.getDocCommentTree(getCurrentPath());
        if (doc == null) return;
        int offset = (int) trees.getSourcePositions().getStartPosition(unit, doc, doc);
        if (offset >= 0 && !visitedDocs.add(offset)) return;
        var owner = element();
        new DocTreePathScanner<Void, Void>() {
            @Override
            public Void visitParam(com.sun.source.doctree.ParamTree tag, Void p) {
                if (!tag.isTypeParameter() && owner instanceof ExecutableElement method) {
                    for (var parameter : method.getParameters()) {
                        var token = tag.getName();
                        String to = rename(parameter);
                        if (to != null && parameter.getSimpleName().equals(token.getName())) {
                            int start = (int) trees.getSourcePositions().getStartPosition(unit, doc, token);
                            int end = (int) trees.getSourcePositions().getEndPosition(unit, doc, token);
                            replace(start, end, parameter, to);
                        }
                    }
                }
                return super.visitParam(tag, p);
            }

            @Override
            public Void visitReference(ReferenceTree ref, Void p) {
                var e = trees.getElement(getCurrentPath());
                if (e == null || (!e.getKind().isField() && e.getKind() != ElementKind.METHOD)) return null;
                int start = (int) trees.getSourcePositions().getStartPosition(unit, doc, ref);
                int end = (int) trees.getSourcePositions().getEndPosition(unit, doc, ref);
                bind(end, "doc", symbols.id(e));
                var to = rename(e);
                if (to != null) {
                    String old = e.getSimpleName().toString();
                    int hash = text.indexOf('#', start);
                    int pos = hash < 0 || hash >= end ? start : hash + 1;
                    while (pos < end && Character.isWhitespace(text.charAt(pos))) pos++;
                    if (pos + old.length() <= end && text.startsWith(old, pos)) replace(pos, pos + old.length(), e, to);
                    else fail(start, "cannot locate Javadoc reference to " + e);
                }
                return null;
            }
        }.scan(new DocTreePath(getCurrentPath(), doc), null);
    }
}
