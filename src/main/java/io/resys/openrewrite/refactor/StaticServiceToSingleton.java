package io.resys.openrewrite.refactor;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.format.AutoFormat;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Value
@EqualsAndHashCode(callSuper = true)
public class StaticServiceToSingleton extends Recipe {

    @Option(displayName = "Service Class Name",
            description = "The fully qualified name of the service class to refactor.",
            example = "com.example.Service")
    String serviceClassName;

    @Option(displayName = "Annotate Methods",
            description = "The fully qualified name of the annotation to be added to new methods.",
            example = "javax.inject.Inject",
            required = false)
    @Nullable
    String annotateMethods;

    @Option(displayName = "Annotate Constructors",
            description = "The fully qualified name of the annotation to be added to new constructors.",
            example = "javax.inject.Inject",
            required = false)
    @Nullable
    String annotateConstructors;

    @Option(displayName = "Add default constructor to consumers",
            description = "Whether default constructor is added to a consumer classes.",
            required = false)
    @Nullable
    Boolean addDefaultConstructorToConsumers;

    @Option(displayName = "Add static delegate methods",
            description = "When set true, create deprecated static method that delegates invocation to matching singleton instance.",
            required = false)
    @Nullable
    Boolean addStaticDelegateMethods;

    @JsonCreator
    public StaticServiceToSingleton(
            @JsonProperty("serviceClassName") String serviceClassName,
            @JsonProperty("annotateMethods") @Nullable String annotateMethods,
            @JsonProperty("annotateConstructors") @Nullable String annotateConstructors,
            @JsonProperty("addDefaultConstructorToConsumers") @Nullable Boolean addDefaultConstructorToConsumers,
            @JsonProperty("addStaticDelegateMethods") @Nullable Boolean addStaticDelegateMethods) {
        this.serviceClassName = serviceClassName;
        this.annotateMethods = annotateMethods;
        this.annotateConstructors = annotateConstructors;
        this.addDefaultConstructorToConsumers = addDefaultConstructorToConsumers;
        this.addStaticDelegateMethods = addStaticDelegateMethods;
    }

    @Override
    public String getDisplayName() {
        return "Convert Static Service to Singleton";
    }

    @Override
    public String getDescription() {
        return "Converts a class with static methods into a singleton service and updates consumers.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                if (TypeUtils.isOfClassType(classDecl.getType(), serviceClassName)) {
                    return refactorServiceClass(classDecl, ctx);
                } else {
                    return refactorConsumerClass(classDecl, ctx);
                }
            }

            private J.ClassDeclaration refactorServiceClass(J.ClassDeclaration cd, ExecutionContext ctx) {
                String simpleName = cd.getSimpleName();
                
                boolean hasInstanceField = cd.getBody().getStatements().stream()
                        .filter(s -> s instanceof J.VariableDeclarations)
                        .map(s -> (J.VariableDeclarations) s)
                        .anyMatch(vd -> vd.getVariables().stream().anyMatch(v -> v.getSimpleName().equals("INSTANCE")));
                if (hasInstanceField) return cd;

                List<Statement> statements = new ArrayList<>();
                
                // 1. Add INSTANCE field
                J.ClassDeclaration cdWithInstance = JavaTemplate.builder("private static final " + simpleName + " INSTANCE = new " + simpleName + "();")
                        .contextSensitive().build().apply(getCursor(), cd.getBody().getCoordinates().firstStatement());
                statements.add(cdWithInstance.getBody().getStatements().get(0));

                // 2. Process existing methods
                for (Statement s : cd.getBody().getStatements()) {
                    if (s instanceof J.MethodDeclaration) {
                        J.MethodDeclaration md = (J.MethodDeclaration) s;
                        if (md.hasModifier(J.Modifier.Type.Public) && md.hasModifier(J.Modifier.Type.Static) && !md.getSimpleName().equals("instance")) {
                            
                            if (Boolean.TRUE.equals(addStaticDelegateMethods)) {
                                String params = md.getParameters().stream()
                                        .filter(p -> !(p instanceof J.Empty))
                                        .map(p -> p.print(getCursor()).trim())
                                        .collect(Collectors.joining(", "));
                                String args = md.getParameters().stream()
                                        .filter(p -> p instanceof J.VariableDeclarations)
                                        .map(p -> ((J.VariableDeclarations) p).getVariables().get(0).getSimpleName())
                                        .collect(Collectors.joining(", "));
                                
                                String returnType = md.getReturnTypeExpression() != null ? md.getReturnTypeExpression().print(getCursor()).trim() : "void";
                                String prefix = returnType.equals("void") ? "" : "return ";
                                
                                String delegateTemplate = "@Deprecated\npublic static " + returnType + " " + md.getSimpleName() + "(" + params + ") {\n" +
                                        "    " + prefix + "instance()." + md.getSimpleName() + "(" + args + ");\n}";
                                
                                J.ClassDeclaration cdWithDelegate = JavaTemplate.builder(delegateTemplate)
                                        .contextSensitive().build().apply(getCursor(), cd.getBody().getCoordinates().firstStatement());
                                statements.add(cdWithDelegate.getBody().getStatements().get(0));
                            }

                            md = md.withModifiers(ListUtils.map(md.getModifiers(), m -> m.getType() == J.Modifier.Type.Static ? null : m));
                            if (md.getMethodType() != null) {
                                Set<Flag> flags = new java.util.HashSet<>(md.getMethodType().getFlags());
                                flags.remove(Flag.Static);
                                md = md.withMethodType(md.getMethodType().withFlags(flags));
                            }
                            statements.add(md);
                            continue;
                        }
                    }
                    statements.add(s);
                }

                // 3. Add instance() method
                String annotation = annotateMethods != null ? "@" + annotateMethods.substring(annotateMethods.lastIndexOf('.') + 1) + "\n" : "";
                J.ClassDeclaration cdWithInstanceMethod = JavaTemplate.builder(annotation + "public static " + simpleName + " instance() {\n    return INSTANCE;\n}")
                        .contextSensitive().build().apply(getCursor(), cd.getBody().getCoordinates().lastStatement());
                statements.add(cdWithInstanceMethod.getBody().getStatements().get(cdWithInstanceMethod.getBody().getStatements().size() - 1));
                if (annotateMethods != null) maybeAddImport(annotateMethods);

                cd = cd.withBody(cd.getBody().withStatements(statements));
                return (J.ClassDeclaration) new AutoFormat().getVisitor().visit(cd, ctx, getCursor());
            }

            private J.ClassDeclaration refactorConsumerClass(J.ClassDeclaration cd, ExecutionContext ctx) {
                AtomicBoolean foundUsage = new AtomicBoolean(false);
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                        if (method.getMethodType() != null && TypeUtils.isOfClassType(method.getMethodType().getDeclaringType(), serviceClassName)) {
                            foundUsage.set(true);
                        }
                        return super.visitMethodInvocation(method, ctx);
                    }
                }.visit(cd, ctx, getCursor());
                if (!foundUsage.get()) return cd;

                String serviceSimpleName = serviceClassName.substring(serviceClassName.lastIndexOf('.') + 1);
                String fieldName = Character.toLowerCase(serviceSimpleName.charAt(0)) + serviceSimpleName.substring(1);

                return (J.ClassDeclaration) new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                        J.ClassDeclaration c = super.visitClassDeclaration(classDecl, ctx);

                        // 1. Add constructors if missing
                        boolean hasConstructor = c.getBody().getStatements().stream().anyMatch(s -> s instanceof J.MethodDeclaration && ((J.MethodDeclaration)s).isConstructor());
                        if (!hasConstructor) {
                             String annotation = annotateConstructors != null ? "@" + annotateConstructors.substring(annotateConstructors.lastIndexOf('.') + 1) + "\n" : "";
                             String template = annotation + "public " + c.getSimpleName() + "(" + serviceSimpleName + " " + fieldName + ") {\n    this." + fieldName + " = " + fieldName + ";\n}";
                             if (Boolean.TRUE.equals(addDefaultConstructorToConsumers)) {
                                 template += "\npublic " + c.getSimpleName() + "() {\n    this(" + serviceSimpleName + ".instance());\n}";
                             }
                             c = JavaTemplate.builder(template).imports(serviceClassName).contextSensitive().build().apply(new Cursor(getCursor().getParent(), c), c.getBody().getCoordinates().firstStatement());
                             if (annotateConstructors != null) maybeAddImport(annotateConstructors);
                        } else if (Boolean.TRUE.equals(addDefaultConstructorToConsumers)) {
                             // Add default constructor if missing
                             boolean hasDefault = c.getBody().getStatements().stream()
                                     .filter(s -> s instanceof J.MethodDeclaration && ((J.MethodDeclaration)s).isConstructor())
                                     .map(s -> (J.MethodDeclaration)s)
                                     .anyMatch(m -> m.getParameters().isEmpty() || (m.getParameters().size() == 1 && m.getParameters().get(0) instanceof J.Empty));
                             if (!hasDefault) {
                                 J.MethodDeclaration target = (J.MethodDeclaration) c.getBody().getStatements().stream().filter(s -> s instanceof J.MethodDeclaration && ((J.MethodDeclaration)s).isConstructor()).findFirst().get();
                                 StringBuilder args = new StringBuilder();
                                 for (int j = 0; j < target.getParameters().size(); j++) {
                                     Statement p = target.getParameters().get(j);
                                     if (p instanceof J.VariableDeclarations) {
                                         if (j > 0) args.append(", ");
                                         if (p.print(getCursor()).contains(serviceSimpleName)) args.append(serviceSimpleName).append(".instance()");
                                         else args.append("null");
                                     }
                                 }
                                 c = JavaTemplate.builder("public " + c.getSimpleName() + "() {\n    this(" + args + ");\n}").contextSensitive().build().apply(new Cursor(getCursor().getParent(), c), target.getCoordinates().after());
                             }
                        }

                        // 2. Add field if missing (DO THIS LAST to ensure it's at the top)
                        boolean hasField = c.getBody().getStatements().stream().anyMatch(s -> s instanceof J.VariableDeclarations && ((J.VariableDeclarations)s).getVariables().stream().anyMatch(v -> v.getSimpleName().equals(fieldName)));
                        if (!hasField) {
                            c = JavaTemplate.builder("private final " + serviceSimpleName + " " + fieldName + ";")
                                    .imports(serviceClassName).contextSensitive().build().apply(new Cursor(getCursor().getParent(), c), c.getBody().getCoordinates().firstStatement());
                            maybeAddImport(serviceClassName);
                        }

                        return (J.ClassDeclaration) new AutoFormat().getVisitor().visit(c, ctx, getCursor().getParent());
                    }

                    @Override
                    public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                        if (method.isConstructor()) {
                            String source = method.print(getCursor());
                            if (source.contains(serviceSimpleName + " " + fieldName) || source.contains(serviceSimpleName + ".instance()")) {
                                return method;
                            }
                            
                            boolean isZeroArg = method.getParameters().isEmpty() || (method.getParameters().size() == 1 && method.getParameters().get(0) instanceof J.Empty);
                            if (isZeroArg && Boolean.TRUE.equals(addDefaultConstructorToConsumers)) {
                                return method;
                            }

                            String currentParams = method.getParameters().stream().filter(p -> !(p instanceof J.Empty)).map(p -> p.print(getCursor())).collect(Collectors.joining(", "));
                            String newParams = currentParams.isEmpty() ? serviceSimpleName + " " + fieldName : currentParams + ", " + serviceSimpleName + " " + fieldName;
                            J.MethodDeclaration m = JavaTemplate.builder(newParams).imports(serviceClassName).contextSensitive().build().apply(getCursor(), method.getCoordinates().replaceParameters());
                            m = JavaTemplate.builder("this." + fieldName + " = " + fieldName + ";").contextSensitive().build().apply(new Cursor(getCursor(), m), m.getBody().getCoordinates().lastStatement());
                            if (annotateConstructors != null && !m.getLeadingAnnotations().stream().anyMatch(a -> a.print(getCursor()).contains(annotateConstructors.substring(annotateConstructors.lastIndexOf('.') + 1)))) {
                                 m = JavaTemplate.builder("@" + annotateConstructors.substring(annotateConstructors.lastIndexOf('.') + 1)).contextSensitive().build().apply(new Cursor(getCursor(), m), m.getCoordinates().addAnnotation((a1, a2) -> 0));
                                 maybeAddImport(annotateConstructors);
                            }
                            return m;
                        }
                        return super.visitMethodDeclaration(method, ctx);
                    }

                    @Override
                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                        if (method.getMethodType() != null && TypeUtils.isOfClassType(method.getMethodType().getDeclaringType(), serviceClassName) && !method.getSimpleName().equals("instance")) {
                            return method.withSelect(new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, fieldName, method.getMethodType().getDeclaringType(), null));
                        }
                        return super.visitMethodInvocation(method, ctx);
                    }
                }.visit(cd, ctx, getCursor());
            }
        };
    }
}
