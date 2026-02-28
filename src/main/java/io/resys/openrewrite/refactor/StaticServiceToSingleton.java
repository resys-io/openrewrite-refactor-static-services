package io.resys.openrewrite.refactor;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Recipe;
import org.openrewrite.RecipeRun;
import org.openrewrite.Result;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.format.AutoFormat;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

    @Option(displayName = "Extract service interface",
            description = "Should create an interface for a Service and use that instead of actual Service type.",
            required = false)
    @Nullable
    Boolean extractServiceInterface;

    @JsonCreator
    public StaticServiceToSingleton(
            @JsonProperty("serviceClassName") String serviceClassName,
            @JsonProperty("annotateMethods") @Nullable String annotateMethods,
            @JsonProperty("annotateConstructors") @Nullable String annotateConstructors,
            @JsonProperty("addDefaultConstructorToConsumers") @Nullable Boolean addDefaultConstructorToConsumers,
            @JsonProperty("addStaticDelegateMethods") @Nullable Boolean addStaticDelegateMethods,
            @JsonProperty("extractServiceInterface") @Nullable Boolean extractServiceInterface) {
        this.serviceClassName = serviceClassName;
        this.annotateMethods = annotateMethods;
        this.annotateConstructors = annotateConstructors;
        this.addDefaultConstructorToConsumers = addDefaultConstructorToConsumers;
        this.addStaticDelegateMethods = addStaticDelegateMethods;
        this.extractServiceInterface = extractServiceInterface;
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
            private final AtomicBoolean interfaceCreated = new AtomicBoolean(false);

            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                J.CompilationUnit result = super.visitCompilationUnit(cu, ctx);

                // If extractServiceInterface is enabled and we're in the service's compilation unit, add interface to same file
                if (Boolean.TRUE.equals(extractServiceInterface) && !interfaceCreated.get()) {
                    boolean hasServiceClass = result.getClasses().stream()
                            .anyMatch(c -> TypeUtils.isOfClassType(c.getType(), serviceClassName));

                    if (hasServiceClass) {
                        interfaceCreated.set(true);
                        // Use doAfterVisit to add the interface after all other transformations
                        doAfterVisit(new JavaIsoVisitor<ExecutionContext>() {
                            @Override
                            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                                return createServiceInterfaceInSameFile(cu, ctx);
                            }
                        });
                    }
                }

                return result;
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                if (TypeUtils.isOfClassType(classDecl.getType(), serviceClassName)) {
                    return refactorServiceClass(classDecl, ctx);
                } else {
                    return refactorConsumerClass(classDecl, ctx);
                }
            }

            private J.CompilationUnit createServiceInterfaceInSameFile(J.CompilationUnit cu, ExecutionContext ctx) {
                // Find the Service class
                J.ClassDeclaration serviceClass = cu.getClasses().stream()
                        .filter(c -> TypeUtils.isOfClassType(c.getType(), serviceClassName))
                        .findFirst()
                        .orElse(null);

                if (serviceClass == null) return cu;

                String simpleName = serviceClass.getSimpleName();
                String interfaceName = "I" + simpleName;

                // Check if interface already exists (to avoid duplication)
                boolean interfaceExists = cu.getClasses().stream()
                        .anyMatch(c -> c.getSimpleName().equals(interfaceName));
                if (interfaceExists) return cu;

                // Collect all public non-static methods that will be in the interface
                // Note: At this point, static modifiers have been removed, so we look for public methods
                List<String> methodSignatures = new ArrayList<>();
                for (Statement s : serviceClass.getBody().getStatements()) {
                    if (s instanceof J.MethodDeclaration) {
                        J.MethodDeclaration md = (J.MethodDeclaration) s;
                        // After transformation, methods are no longer static, so we just check for public non-instance methods
                        if (md.hasModifier(J.Modifier.Type.Public) && !md.hasModifier(J.Modifier.Type.Static) && !md.getSimpleName().equals("instance")) {
                            // Build method signature
                            String returnType = md.getReturnTypeExpression() != null ?
                                    md.getReturnTypeExpression().printTrimmed(new Cursor(null, cu)) : "void";
                            String params = md.getParameters().stream()
                                    .filter(p -> !(p instanceof J.Empty))
                                    .map(p -> p.printTrimmed(new Cursor(null, cu)))
                                    .collect(Collectors.joining(", "));
                            methodSignatures.add(returnType + " " + md.getSimpleName() + "(" + params + ");");
                        }
                    }
                }

                if (methodSignatures.isEmpty()) return cu;

                // Create the interface source code
                String packageDecl = cu.getPackageDeclaration() != null ?
                        "package " + cu.getPackageDeclaration().getExpression().printTrimmed(new Cursor(null, cu)) + ";\n\n" : "";
                String interfaceBody = String.join("\n    ", methodSignatures);
                String interfaceSource = packageDecl + "interface " + interfaceName + " {\n    " + interfaceBody + "\n}\n";

                // Parse the interface source to get a proper ClassDeclaration
                JavaParser parser = JavaParser.fromJavaVersion().build();
                J.CompilationUnit interfaceCu = parser.parse(ctx, interfaceSource).findFirst()
                        .map(J.CompilationUnit.class::cast)
                        .orElse(null);

                if (interfaceCu == null || interfaceCu.getClasses().isEmpty()) return cu;

                // Extract the interface declaration
                J.ClassDeclaration interfaceDecl = interfaceCu.getClasses().get(0);

                // Add the interface to the beginning of the classes list
                List<J.ClassDeclaration> newClasses = new ArrayList<>();
                newClasses.add(interfaceDecl);
                newClasses.addAll(cu.getClasses());

                return cu.withClasses(newClasses);
            }

            private J.ClassDeclaration refactorServiceClass(J.ClassDeclaration cd, ExecutionContext ctx) {
                String simpleName = cd.getSimpleName();
                String interfaceName = "I" + simpleName;

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
                String returnType = Boolean.TRUE.equals(extractServiceInterface) ? interfaceName : simpleName;
                String annotation = annotateMethods != null ? "@" + annotateMethods.substring(annotateMethods.lastIndexOf('.') + 1) + "\n" : "";
                J.ClassDeclaration cdWithInstanceMethod = JavaTemplate.builder(annotation + "public static " + returnType + " instance() {\n    return INSTANCE;\n}")
                        .contextSensitive().build().apply(getCursor(), cd.getBody().getCoordinates().lastStatement());
                statements.add(cdWithInstanceMethod.getBody().getStatements().get(cdWithInstanceMethod.getBody().getStatements().size() - 1));
                if (annotateMethods != null) maybeAddImport(annotateMethods);

                cd = cd.withBody(cd.getBody().withStatements(statements));

                // 4. Add implements clause if extractServiceInterface is true
                if (Boolean.TRUE.equals(extractServiceInterface)) {
                    J.ClassDeclaration cdWithInterface = JavaTemplate.builder("class " + simpleName + " implements " + interfaceName + " {}")
                            .contextSensitive().build().apply(getCursor(), cd.getCoordinates().replace());
                    cd = cd.withImplements(cdWithInterface.getImplements());
                }

                return (J.ClassDeclaration) new AutoFormat().getVisitor().visit(cd, ctx, getCursor());
            }

            private J.ClassDeclaration refactorConsumerClass(J.ClassDeclaration cd, ExecutionContext ctx) {
                // Check if there's usage from non-static context
                AtomicBoolean foundUsageInNonStaticContext = new AtomicBoolean(false);
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                        if (method.getMethodType() != null && TypeUtils.isOfClassType(method.getMethodType().getDeclaringType(), serviceClassName)) {
                            // Check if we're in a non-static method
                            Cursor parentCursor = getCursor();
                            while (parentCursor != null) {
                                Object value = parentCursor.getValue();
                                if (value instanceof J.MethodDeclaration) {
                                    J.MethodDeclaration containingMethod = (J.MethodDeclaration) value;
                                    if (!containingMethod.hasModifier(J.Modifier.Type.Static)) {
                                        // Found usage in a non-static method
                                        foundUsageInNonStaticContext.set(true);
                                    }
                                    break;
                                }
                                if (value instanceof J.ClassDeclaration) {
                                    // Reached class level (e.g., field initializer)
                                    break;
                                }
                                parentCursor = parentCursor.getParent();
                            }
                        }
                        return super.visitMethodInvocation(method, ctx);
                    }
                }.visit(cd, ctx, getCursor());

                // Only refactor if there's usage from non-static context
                if (!foundUsageInNonStaticContext.get()) return cd;

                String serviceSimpleName = serviceClassName.substring(serviceClassName.lastIndexOf('.') + 1);
                String serviceTypeName = Boolean.TRUE.equals(extractServiceInterface) ? "I" + serviceSimpleName : serviceSimpleName;
                String fieldName = Character.toLowerCase(serviceSimpleName.charAt(0)) + serviceSimpleName.substring(1);

                // pass 1: update method calls (only in non-static methods)
                cd = (J.ClassDeclaration) new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                        if (method.getMethodType() != null && TypeUtils.isOfClassType(method.getMethodType().getDeclaringType(), serviceClassName) && !method.getSimpleName().equals("instance")) {
                            // Check if we're in a static method - if so, don't transform
                            Cursor parentCursor = getCursor();
                            while (parentCursor != null) {
                                Object value = parentCursor.getValue();
                                if (value instanceof J.MethodDeclaration) {
                                    J.MethodDeclaration containingMethod = (J.MethodDeclaration) value;
                                    if (containingMethod.hasModifier(J.Modifier.Type.Static)) {
                                        // In a static method, don't transform the call
                                        return super.visitMethodInvocation(method, ctx);
                                    }
                                    // In a non-static method, transform the call
                                    break;
                                }
                                if (value instanceof J.ClassDeclaration) {
                                    // Reached class level without finding a method (e.g., field initializer)
                                    return super.visitMethodInvocation(method, ctx);
                                }
                                parentCursor = parentCursor.getParent();
                            }

                            // Transform the call to use the instance field
                            return method.withSelect(new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, fieldName, method.getMethodType().getDeclaringType(), null));
                        }
                        return super.visitMethodInvocation(method, ctx);
                    }
                }.visit(cd, ctx, getCursor());

                // pass 2: add field
                boolean fieldExists = cd.getBody().getStatements().stream().anyMatch(s -> s instanceof J.VariableDeclarations && ((J.VariableDeclarations) s).getVariables().stream().anyMatch(v -> v.getSimpleName().equals(fieldName)));
                if (!fieldExists) {
                    J.ClassDeclaration tempCd = JavaTemplate.builder("private final " + serviceTypeName + " " + fieldName + ";")
                            .imports(serviceClassName).contextSensitive().build().apply(new Cursor(getCursor(), cd), cd.getBody().getCoordinates().firstStatement());
                    cd = cd.withBody(cd.getBody().withStatements(ListUtils.concat(tempCd.getBody().getStatements().get(0), cd.getBody().getStatements())));
                    maybeAddImport(serviceClassName);
                }

                // pass 3: transform constructors
                boolean hasConstructors = cd.getBody().getStatements().stream().anyMatch(s -> s instanceof J.MethodDeclaration && ((J.MethodDeclaration) s).isConstructor());
                if (!hasConstructors) {
                    String annotation = annotateConstructors != null ? "@" + annotateConstructors.substring(annotateConstructors.lastIndexOf('.') + 1) + "\n" : "";
                    String template = annotation + "public " + cd.getSimpleName() + "(" + serviceTypeName + " " + fieldName + ") {\n    this." + fieldName + " = " + fieldName + ";\n}";
                    if (Boolean.TRUE.equals(addDefaultConstructorToConsumers)) {
                        template += "\npublic " + cd.getSimpleName() + "() {\n    this(" + serviceSimpleName + ".instance());\n}";
                    }
                    J.ClassDeclaration tempCd = JavaTemplate.builder(template).imports(serviceClassName).contextSensitive().build().apply(new Cursor(getCursor(), cd), cd.getBody().getCoordinates().lastStatement());
                    List<Statement> newConstructors = tempCd.getBody().getStatements().stream()
                            .filter(s -> s instanceof J.MethodDeclaration && ((J.MethodDeclaration) s).isConstructor())
                            .map(s -> (Statement) s.withId(Tree.randomId()))
                            .collect(Collectors.toList());

                    // Insert constructors after fields but before methods
                    List<Statement> newStatements = new ArrayList<>();
                    boolean constructorsInserted = false;
                    for (Statement s : cd.getBody().getStatements()) {
                        if (!constructorsInserted && s instanceof J.MethodDeclaration && !((J.MethodDeclaration) s).isConstructor()) {
                            newStatements.addAll(newConstructors);
                            constructorsInserted = true;
                        }
                        newStatements.add(s);
                    }
                    if (!constructorsInserted) {
                        newStatements.addAll(newConstructors);
                    }
                    cd = cd.withBody(cd.getBody().withStatements(newStatements));
                    if (annotateConstructors != null) maybeAddImport(annotateConstructors);
                } else {
                    // Create helper statements
                    J.ClassDeclaration helperCd = JavaTemplate.builder("public void helper(" + serviceTypeName + " " + fieldName + ") { this." + fieldName + " = " + fieldName + "; }")
                            .imports(serviceClassName).contextSensitive().build().apply(new Cursor(getCursor(), cd), cd.getBody().getCoordinates().firstStatement());
                    J.MethodDeclaration helperM = (J.MethodDeclaration) helperCd.getBody().getStatements().get(0);
                    Statement serviceParam = helperM.getParameters().get(0);
                    Statement assignmentStat = helperM.getBody().getStatements().get(0);

                    final J.ClassDeclaration cdFinal = cd;
                    cd = cd.withBody(cd.getBody().withStatements(ListUtils.flatMap(cd.getBody().getStatements(), s -> {
                        if (s instanceof J.MethodDeclaration && ((J.MethodDeclaration) s).isConstructor()) {
                            J.MethodDeclaration m = (J.MethodDeclaration) s;

                            // Check if constructor already has service parameter
                            boolean hasServiceParam = m.getParameters().stream()
                                    .filter(p -> p instanceof J.VariableDeclarations)
                                    .anyMatch(p -> ((J.VariableDeclarations)p).getVariables().stream()
                                            .anyMatch(v -> v.getSimpleName().equals(fieldName)));

                            // Check if constructor already delegates to Service.instance()
                            boolean delegatesToService = m.getBody() != null && m.getBody().getStatements().stream()
                                    .filter(st -> st instanceof J.MethodInvocation)
                                    .filter(st -> ((J.MethodInvocation)st).getSimpleName().equals("this"))
                                    .anyMatch(st -> ((J.MethodInvocation)st).getArguments().stream()
                                            .anyMatch(arg -> arg.print(getCursor()).contains(".instance()")));

                            boolean alreadyProcessed = hasServiceParam || delegatesToService;

                            if (alreadyProcessed) return Collections.singletonList(s);

                            // Full Constructor - filter out empty parameters before adding service parameter
                            List<Statement> nonEmptyParams = m.getParameters().stream()
                                    .filter(p -> !(p instanceof J.Empty))
                                    .collect(Collectors.toList());
                            nonEmptyParams.add(serviceParam.withId(Tree.randomId()));
                            J.MethodDeclaration mFull = m.withParameters(nonEmptyParams);
                            mFull = mFull.withId(Tree.randomId());

                            boolean hasThis = mFull.getBody().getStatements().stream().anyMatch(st -> st instanceof J.MethodInvocation && ((J.MethodInvocation)st).getSimpleName().equals("this"));
                            if (hasThis) {
                                // Update this() call to add service parameter
                                mFull = (J.MethodDeclaration) new JavaIsoVisitor<ExecutionContext>() {
                                    @Override
                                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                                        if (method.getSimpleName().equals("this")) {
                                            // Add the service field as an argument to the this() call
                                            J.Identifier serviceArg = new J.Identifier(
                                                    Tree.randomId(),
                                                    Space.EMPTY,
                                                    Markers.EMPTY,
                                                    fieldName,
                                                    null,
                                                    null
                                            );
                                            return method.withArguments(ListUtils.<Expression>concat(method.getArguments(), serviceArg));
                                        }
                                        return super.visitMethodInvocation(method, ctx);
                                    }
                                }.visitNonNull(mFull, ctx, getCursor());
                            } else {
                                mFull = mFull.withBody(mFull.getBody().withStatements(ListUtils.<Statement>concat(mFull.getBody().getStatements(), assignmentStat.withId(Tree.randomId()))));
                            }
                            
                            if (annotateConstructors != null && !mFull.getLeadingAnnotations().stream().anyMatch(a -> a.print(getCursor()).contains(annotateConstructors.substring(annotateConstructors.lastIndexOf('.') + 1)))) {
                                 mFull = JavaTemplate.builder("@" + annotateConstructors.substring(annotateConstructors.lastIndexOf('.') + 1)).contextSensitive().build().apply(new Cursor(getCursor(), mFull), mFull.getCoordinates().addAnnotation((a1, a2) -> 0));
                                 maybeAddImport(annotateConstructors);
                            }

                            // Delegating Constructor - use template to create delegate body
                            String args = m.getParameters().stream()
                                    .filter(p -> p instanceof J.VariableDeclarations)
                                    .map(p -> ((J.VariableDeclarations)p).getVariables().get(0).getSimpleName())
                                    .collect(Collectors.joining(", "));
                            String delegatingArgs = args.isEmpty() ? serviceSimpleName + ".instance()" : args + ", " + serviceSimpleName + ".instance()";

                            // Create a temporary method to extract the delegate body from
                            String tempMethod = "void temp() { this(" + delegatingArgs + "); }";
                            J.ClassDeclaration tempClass = JavaTemplate.builder(tempMethod)
                                    .imports(serviceClassName)
                                    .contextSensitive()
                                    .build()
                                    .apply(new Cursor(getCursor().getParent(), cdFinal), cdFinal.getBody().getCoordinates().lastStatement());
                            J.MethodDeclaration tempMethodDecl = (J.MethodDeclaration) tempClass.getBody().getStatements()
                                    .get(tempClass.getBody().getStatements().size() - 1);
                            J.Block delegateBlock = tempMethodDecl.getBody();

                            J.MethodDeclaration mDelegate = m.withBody(delegateBlock).withId(Tree.randomId());

                            return Arrays.asList(mFull, mDelegate);
                        }
                        return Collections.singletonList(s);
                    })));
                }

                doAfterVisit(new AutoFormat().getVisitor());
                return cd;
            }
        };
    }
}
