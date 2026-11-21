package top.outlands.jst.mcp.source;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import java.util.NavigableMap;
import java.util.TreeMap;

/** javac exposes the declaration range, not the name token (annotations can also contain its name). */
final class DeclarationNames {
    private record Name(String text, TextRange range, boolean method) {}

    private final NavigableMap<Integer, Name> names = new TreeMap<>();

    DeclarationNames(Project project, String path, String text) {
        var psi = PsiFileFactory.getInstance(project).createFileFromText(path.substring(path.lastIndexOf('/') + 1), JavaLanguage.INSTANCE, text);
        psi.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(PsiElement element) {
                if (element instanceof PsiNameIdentifierOwner owner && (owner instanceof PsiField || owner instanceof PsiParameter || owner instanceof PsiMethod)) {
                    var id = owner.getNameIdentifier();
                    if (id != null) names.put(id.getTextOffset(), new Name(owner.getName(), id.getTextRange(), owner instanceof PsiMethod));
                }
                super.visitElement(element);
            }
        });
    }

    TextRange between(int start, int end, String name, boolean method) {
        var candidates = names.subMap(start, true, end, false);
        // javac starts every variable in 'int a = ..., b;' at the shared type. An earlier
        // initializer can itself contain declarations, so work back from this declarator.
        for (var n : (method ? candidates : candidates.descendingMap()).values())
            if (n.text.equals(name) && n.method == method) return n.range;
        return null;
    }
}
