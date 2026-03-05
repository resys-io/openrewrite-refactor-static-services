package io.resys.openrewrite.refactor;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.format.AutoFormat;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Value
@EqualsAndHashCode(callSuper = false)
public class StaticServiceToSingleton extends ScanningRecipe<StaticServiceToSingleton.Accumulator> {

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

    @Option(displayName = "Change static calls through instance",
            description = "When set true, static invocations from static contexts are rewritten as Service.instance().method() instead of being left unchanged.",
            required = false)
    @Nullable
    Boolean changeStaticCallsThroughInstance;

    @Option(displayName = "Extract service interface",
            description = "Should create an interface for a Service and use that instead of actual Service type.",
            required = false)
    @Nullable
    Boolean extractServiceInterface;

    @Option(displayName = "Minimize changes",
            description = "When true, disables auto-formatting so only the structural changes are applied, reducing whitespace-only diffs.",
            required = false)
    @Nullable
    Boolean minimizeChanges;

    @Option(displayName = "Target visibilities",
            description = "Selects which static methods on the Service to modify based on their current visibility. " +
                    "Accepted values: ALL, PUBLIC, PROTECTED, PRIVATE, PACKAGE. " +
                    "Regardless of this setting, the extracted interface only includes PUBLIC methods. Default: PUBLIC.",
            required = false)
    @Nullable
    List<String> targetVisibilities;

    @JsonCreator
    public StaticServiceToSingleton(
            @JsonProperty("serviceClassName") String serviceClassName,
            @JsonProperty("annotateMethods") @Nullable String annotateMethods,
            @JsonProperty("annotateConstructors") @Nullable String annotateConstructors,
            @JsonProperty("addDefaultConstructorToConsumers") @Nullable Boolean addDefaultConstructorToConsumers,
            @JsonProperty("changeStaticCallsThroughInstance") @Nullable Boolean changeStaticCallsThroughInstance,
            @JsonProperty("extractServiceInterface") @Nullable Boolean extractServiceInterface,
            @JsonProperty("minimizeChanges") @Nullable Boolean minimizeChanges,
            @JsonProperty("targetVisibilities") @Nullable List<String> targetVisibilities) {
        super();
        this.serviceClassName = serviceClassName;
        this.annotateMethods = annotateMethods;
        this.annotateConstructors = annotateConstructors;
        this.addDefaultConstructorToConsumers = addDefaultConstructorToConsumers;
        this.changeStaticCallsThroughInstance = changeStaticCallsThroughInstance;
        this.extractServiceInterface = extractServiceInterface;
        this.minimizeChanges = minimizeChanges;
        this.targetVisibilities = targetVisibilities;
    }

    private boolean isTargetedVisibility(J.MethodDeclaration md) {
        List<String> effective = (targetVisibilities != null && !targetVisibilities.isEmpty())
                ? targetVisibilities : Collections.singletonList("PUBLIC");
        for (String v : effective) {
            switch (v) {
                case "ALL": return true;
                case "PUBLIC": if (md.hasModifier(J.Modifier.Type.Public)) return true; break;
                case "PROTECTED": if (md.hasModifier(J.Modifier.Type.Protected)) return true; break;
                case "PRIVATE": if (md.hasModifier(J.Modifier.Type.Private)) return true; break;
                case "PACKAGE":
                    if (!md.hasModifier(J.Modifier.Type.Public)
                            && !md.hasModifier(J.Modifier.Type.Protected)
                            && !md.hasModifier(J.Modifier.Type.Private)) return true;
                    break;
                default: break;
            }
        }
        return false;
    }

    @Override
    public boolean causesAnotherCycle() {
        return true;
    }

    @Override
    public String getDisplayName() {
        return "Convert Static Service to Singleton";
    }

    @Override
    public String getDescription() {
        return "Converts a class with static methods into a singleton service and updates consumers.";
    }

    public static class Accumulator {
        String interfaceName;
        String interfaceSource;
        Path sourcePath;

        boolean hasInterface() {
            return interfaceSource != null;
        }
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        if (!Boolean.TRUE.equals(extractServiceInterface)) {
            return TreeVisitor.noop();
        }
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                if (!TypeUtils.isOfClassType(classDecl.getType(), serviceClassName) || acc.hasInterface()) {
                    return classDecl;
                }

                String simpleName = classDecl.getSimpleName();
                String interfaceName = "I" + simpleName;

                // Collect method signatures from the ORIGINAL public static methods (before transformation)
                List<String> methodSignatures = new ArrayList<>();
                for (Statement s : classDecl.getBody().getStatements()) {
                    if (s instanceof J.MethodDeclaration) {
                        J.MethodDeclaration md = (J.MethodDeclaration) s;
                        if (md.hasModifier(J.Modifier.Type.Public) && md.hasModifier(J.Modifier.Type.Static) && !md.getSimpleName().equals("instance")) {
                            String returnType = md.getReturnTypeExpression() != null ?
                                    md.getReturnTypeExpression().printTrimmed(getCursor()) : "void";
                            String params = md.getParameters().stream()
                                    .filter(p -> !(p instanceof J.Empty))
                                    .map(p -> p.printTrimmed(getCursor()))
                                    .collect(Collectors.joining(", "));
                            methodSignatures.add(returnType + " " + md.getSimpleName() + "(" + params + ");");
                        }
                    }
                }

                if (methodSignatures.isEmpty()) return classDecl;

                J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
                String packageName = cu != null && cu.getPackageDeclaration() != null ?
                        cu.getPackageDeclaration().getExpression().printTrimmed(getCursor()) : "";
                String packageDecl = packageName.isEmpty() ? "" : "package " + packageName + ";\n\n";
                String interfaceBody = String.join("\n    ", methodSignatures);

                acc.interfaceName = interfaceName;
                acc.interfaceSource = packageDecl + "public interface " + interfaceName + " {\n    " + interfaceBody + "\n}\n";
                if (cu != null) {
                    Path parent = cu.getSourcePath().getParent();
                    acc.sourcePath = parent != null ? parent.resolve(interfaceName + ".java") : Paths.get(interfaceName + ".java");
                }

                return classDecl;
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, Collection<SourceFile> generatedInThisCycle, ExecutionContext ctx) {
        if (!acc.hasInterface()) {
            return Collections.emptyList();
        }
        // Only generate the file once
        boolean alreadyGenerated = generatedInThisCycle.stream()
                .anyMatch(f -> f.getSourcePath().equals(acc.sourcePath));
        if (alreadyGenerated) {
            return Collections.emptyList();
        }

        JavaParser parser = JavaParser.fromJavaVersion().build();
        J.CompilationUnit interfaceCu = parser.parse(ctx, acc.interfaceSource).findFirst()
                .map(J.CompilationUnit.class::cast)
                .orElse(null);

        if (interfaceCu == null) {
            return Collections.emptyList();
        }

        return Collections.singletonList(interfaceCu.withSourcePath(acc.sourcePath));
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
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
                        if (isTargetedVisibility(md) && md.hasModifier(J.Modifier.Type.Static) && !md.getSimpleName().equals("instance")) {

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

                // 2b. Rewrite self-calls: Service.method() → method() in all method bodies
                statements = ListUtils.map(statements, s -> {
                    if (!(s instanceof J.MethodDeclaration)) return s;
                    return (Statement) new JavaIsoVisitor<ExecutionContext>() {
                        @Override
                        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                            if (method.getSelect() instanceof J.Identifier
                                    && ((J.Identifier) method.getSelect()).getSimpleName().equals(simpleName)
                                    && !method.getSimpleName().equals("instance")) {
                                return method.withSelect(null);
                            }
                            return super.visitMethodInvocation(method, ctx);
                        }
                    }.visitNonNull((J.MethodDeclaration) s, ctx, getCursor());
                });

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

                if (Boolean.TRUE.equals(minimizeChanges)) return cd;
                return (J.ClassDeclaration) new AutoFormat(null).getVisitor().visit(cd, ctx, getCursor());
            }

            private boolean isServiceCall(J.MethodInvocation method, String serviceSimpleName) {
                if (method.getSimpleName().equals("instance")) {
                    return false;
                }
                // Normal case: type info available - only match static calls
                if (method.getMethodType() != null) {
                    return TypeUtils.isOfClassType(method.getMethodType().getDeclaringType(), serviceClassName)
                            && method.getMethodType().hasFlags(Flag.Static);
                }
                // Fallback: service was already upgraded in a previous run, static method no longer exists,
                // so type resolution fails. Match by select expression name instead.
                Expression select = method.getSelect();
                return select instanceof J.Identifier &&
                       ((J.Identifier) select).getSimpleName().equals(serviceSimpleName);
            }

            private boolean isInStaticContext(Cursor cursor) {
                while (cursor != null) {
                    Object value = cursor.getValue();
                    if (value instanceof J.MethodDeclaration) {
                        return ((J.MethodDeclaration) value).hasModifier(J.Modifier.Type.Static);
                    }
                    if (value instanceof J.ClassDeclaration) {
                        return false;
                    }
                    cursor = cursor.getParent();
                }
                return false;
            }

            private J.ClassDeclaration refactorConsumerClass(J.ClassDeclaration cd, ExecutionContext ctx) {
                String serviceSimpleName = serviceClassName.substring(serviceClassName.lastIndexOf('.') + 1);
                String serviceTypeName = Boolean.TRUE.equals(extractServiceInterface) ? "I" + serviceSimpleName : serviceSimpleName;
                String fieldName = Character.toLowerCase(serviceSimpleName.charAt(0)) + serviceSimpleName.substring(1);

                // Check if there's usage from non-static context
                AtomicBoolean foundUsageInNonStaticContext = new AtomicBoolean(false);
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                        if (isServiceCall(method, serviceSimpleName) && !isInStaticContext(getCursor())) {
                            foundUsageInNonStaticContext.set(true);
                        }
                        return super.visitMethodInvocation(method, ctx);
                    }
                }.visit(cd, ctx, getCursor());

                // Check if there's usage from static context (only relevant when changeStaticCallsThroughInstance is enabled)
                AtomicBoolean foundUsageInStaticContext = new AtomicBoolean(false);
                if (Boolean.TRUE.equals(changeStaticCallsThroughInstance)) {
                    new JavaIsoVisitor<ExecutionContext>() {
                        @Override
                        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                            if (isServiceCall(method, serviceSimpleName) && isInStaticContext(getCursor())) {
                                foundUsageInStaticContext.set(true);
                            }
                            return super.visitMethodInvocation(method, ctx);
                        }
                    }.visit(cd, ctx, getCursor());
                }

                // Only refactor if there's relevant usage
                if (!foundUsageInNonStaticContext.get() && !foundUsageInStaticContext.get()) return cd;

                // pass 1: update method calls
                cd = (J.ClassDeclaration) new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                        if (isServiceCall(method, serviceSimpleName)) {
                            boolean inStaticCtx = isInStaticContext(getCursor());
                            if (!inStaticCtx && foundUsageInNonStaticContext.get()) {
                                // Non-static context: replace with injected field
                                JavaType selectType = method.getMethodType() != null
                                        ? method.getMethodType().getDeclaringType() : null;
                                return method.withSelect(new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(), fieldName, selectType, null));
                            } else if (inStaticCtx && Boolean.TRUE.equals(changeStaticCallsThroughInstance)
                                    && method.getSelect() instanceof J.Identifier) {
                                // Static context: rewrite Service.action(args) as Service.instance().action(args)
                                // Guard: only when select is a plain identifier so we don't double-transform across cycles
                                J.MethodInvocation instanceCall = method
                                        .withId(Tree.randomId())
                                        .withPrefix(Space.EMPTY)
                                        .withName(method.getName().withSimpleName("instance"))
                                        .withArguments(Collections.singletonList(new J.Empty(Tree.randomId(), Space.EMPTY, Markers.EMPTY)))
                                        .withMethodType(null);
                                return method.withSelect(instanceCall);
                            }
                        }
                        return super.visitMethodInvocation(method, ctx);
                    }
                }.visit(cd, ctx, getCursor());

                // If only static-context usage, no field/constructor changes needed
                if (!foundUsageInNonStaticContext.get()) {
                    if (!Boolean.TRUE.equals(minimizeChanges)) doAfterVisit(new AutoFormat(null).getVisitor());
                    return cd;
                }

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

                            boolean hasServiceParam = m.getParameters().stream()
                                    .filter(p -> p instanceof J.VariableDeclarations)
                                    .anyMatch(p -> ((J.VariableDeclarations)p).getVariables().stream()
                                            .anyMatch(v -> v.getSimpleName().equals(fieldName)));

                            boolean delegatesToService = m.getBody() != null && m.getBody().getStatements().stream()
                                    .filter(st -> st instanceof J.MethodInvocation)
                                    .filter(st -> ((J.MethodInvocation)st).getSimpleName().equals("this"))
                                    .anyMatch(st -> ((J.MethodInvocation)st).getArguments().stream()
                                            .anyMatch(arg -> arg.print(getCursor()).contains(".instance()")));

                            if (hasServiceParam || delegatesToService) return Collections.singletonList(s);

                            // Full Constructor - filter out empty parameters before adding service parameter
                            List<Statement> nonEmptyParams = m.getParameters().stream()
                                    .filter(p -> !(p instanceof J.Empty))
                                    .collect(Collectors.toList());
                            nonEmptyParams.add(serviceParam.withId(Tree.randomId()));
                            J.MethodDeclaration mFull = m.withParameters(nonEmptyParams).withId(Tree.randomId());

                            boolean hasThis = mFull.getBody().getStatements().stream().anyMatch(st -> st instanceof J.MethodInvocation && ((J.MethodInvocation)st).getSimpleName().equals("this"));
                            if (hasThis) {
                                mFull = (J.MethodDeclaration) new JavaIsoVisitor<ExecutionContext>() {
                                    @Override
                                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                                        if (method.getSimpleName().equals("this")) {
                                            J.Identifier serviceArg = new J.Identifier(
                                                    Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(),
                                                    fieldName, null, null);
                                            return method.withArguments(ListUtils.<Expression>concat(method.getArguments(), serviceArg));
                                        }
                                        return super.visitMethodInvocation(method, ctx);
                                    }
                                }.visitNonNull(mFull, ctx, getCursor());
                            } else {
                                mFull = mFull.withBody(mFull.getBody().withStatements(ListUtils.<Statement>concat(mFull.getBody().getStatements(), assignmentStat.withId(Tree.randomId()))));
                            }

                            if (annotateConstructors != null && mFull.getLeadingAnnotations().stream().noneMatch(a -> a.print(getCursor()).contains(annotateConstructors.substring(annotateConstructors.lastIndexOf('.') + 1)))) {
                                mFull = JavaTemplate.builder("@" + annotateConstructors.substring(annotateConstructors.lastIndexOf('.') + 1)).contextSensitive().build().apply(new Cursor(getCursor(), mFull), mFull.getCoordinates().addAnnotation((a1, a2) -> 0));
                                maybeAddImport(annotateConstructors);
                            }

                            // Delegating Constructor
                            String args = m.getParameters().stream()
                                    .filter(p -> p instanceof J.VariableDeclarations)
                                    .map(p -> ((J.VariableDeclarations)p).getVariables().get(0).getSimpleName())
                                    .collect(Collectors.joining(", "));
                            String delegatingArgs = args.isEmpty() ? serviceSimpleName + ".instance()" : args + ", " + serviceSimpleName + ".instance()";

                            String tempMethod = "void temp() { this(" + delegatingArgs + "); }";
                            J.ClassDeclaration tempClass = JavaTemplate.builder(tempMethod)
                                    .imports(serviceClassName).contextSensitive().build()
                                    .apply(new Cursor(getCursor().getParent(), cdFinal), cdFinal.getBody().getCoordinates().lastStatement());
                            J.Block delegateBlock = ((J.MethodDeclaration) tempClass.getBody().getStatements()
                                    .get(tempClass.getBody().getStatements().size() - 1)).getBody();

                            return Arrays.asList(mFull, m.withBody(delegateBlock).withId(Tree.randomId()));
                        }
                        return Collections.singletonList(s);
                    })));
                }

                if (!Boolean.TRUE.equals(minimizeChanges)) doAfterVisit(new AutoFormat(null).getVisitor());
                return cd;
            }
        };
    }
}
