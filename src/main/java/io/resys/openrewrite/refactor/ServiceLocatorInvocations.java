package io.resys.openrewrite.refactor;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.format.AutoFormat;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.*;
import java.util.stream.Collectors;

@Value
@EqualsAndHashCode(callSuper = false)
public class ServiceLocatorInvocations extends Recipe {

    @Option(displayName = "Method Pattern",
            description = "The method pattern used to identify the service locator call (e.g. `com.example.ServiceLocator getService(..)`).",
            example = "com.example.ServiceLocator getService(..)")
    String methodPattern;

    @Option(displayName = "Use Constructor Injection",
            description = "When set to true, services used in non-static contexts are injected via constructor instead of field initialization.",
            required = false)
    @Nullable
    Boolean useConstructorInjection;

    @Option(displayName = "Annotate Constructors",
            description = "The fully qualified name of the annotation to be added to the modified constructor (e.g. `javax.inject.Inject`).",
            example = "javax.inject.Inject",
            required = false)
    @Nullable
    String annotateConstructors;

    @Option(displayName = "Minimize Changes",
            description = "When set to `true`, skips the auto-formatting pass so that only structural AST changes are applied.",
            required = false)
    @Nullable
    Boolean minimizeChanges;

    @JsonCreator
    public ServiceLocatorInvocations(
            @JsonProperty("methodPattern") String methodPattern,
            @JsonProperty("useConstructorInjection") @Nullable Boolean useConstructorInjection,
            @JsonProperty("annotateConstructors") @Nullable String annotateConstructors,
            @JsonProperty("minimizeChanges") @Nullable Boolean minimizeChanges) {
        this.methodPattern = methodPattern;
        this.useConstructorInjection = useConstructorInjection;
        this.annotateConstructors = annotateConstructors;
        this.minimizeChanges = minimizeChanges;
    }

    @Override
    public String getDisplayName() {
        return "Refactor Service Locator Invocations into Fields";
    }

    @Override
    public String getDescription() {
        return "Refactors static service locator method calls into class fields, deduplicating multiple lookups of the same service.";
    }

    /** Info collected from a local variable declaration whose initializer is a service locator call. */
    static class LocalServiceVar {
        final String varName;
        final String typeStr;
        final J.MethodInvocation invocation;
        final J.VariableDeclarations originalDecl;   // original AST node (carries type info)
        final String argKey;
        final boolean inStaticContext;
        final java.util.UUID enclosingMethodId;

        LocalServiceVar(String varName, String typeStr, J.MethodInvocation invocation,
                        J.VariableDeclarations originalDecl, String argKey, boolean inStaticContext,
                        java.util.UUID enclosingMethodId) {
            this.varName = varName;
            this.typeStr = typeStr;
            this.invocation = invocation;
            this.originalDecl = originalDecl;
            this.argKey = argKey;
            this.inStaticContext = inStaticContext;
            this.enclosingMethodId = enclosingMethodId;
        }
    }

    /** Resolved field to be created (or reused) for one service. */
    static class ServiceField {
        final String fieldName;
        final String typeStr;
        final J.MethodInvocation invocation;
        final J.VariableDeclarations originalDecl;
        final boolean isStatic;
        boolean alreadyExists;

        ServiceField(String fieldName, String typeStr, J.MethodInvocation invocation,
                     J.VariableDeclarations originalDecl, boolean isStatic) {
            this.fieldName = fieldName;
            this.typeStr = typeStr;
            this.invocation = invocation;
            this.originalDecl = originalDecl;
            this.isStatic = isStatic;
        }
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        MethodMatcher matcher = new MethodMatcher(methodPattern, true);

        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                classDecl = super.visitClassDeclaration(classDecl, ctx);

                Cursor outerCursor = getCursor();

                // Phase 1: collect local variable declarations with service locator initializers
                List<LocalServiceVar> localVars = collectLocalServiceVars(classDecl, outerCursor);
                if (localVars.isEmpty()) return classDecl;

                // Phase 2: build deduplicated service field map keyed by argument expression string
                Map<String, ServiceField> serviceFields = buildServiceFields(classDecl, localVars, outerCursor);

                // Phase 3: remove matched local variable declarations from method bodies
                classDecl = removeLocalVarDecls(classDecl, ctx, outerCursor);

                // Phase 3.5: rename identifiers in method bodies where the field name was derived from the type
                classDecl = renameLocalVarReferences(classDecl, localVars, serviceFields, ctx, outerCursor);

                // Phase 4: add new fields to the class (skipped if a field with same name already exists)
                classDecl = addServiceFields(classDecl, serviceFields);

                // Phase 5: optional constructor injection for non-static fields
                if (Boolean.TRUE.equals(useConstructorInjection)) {
                    classDecl = applyConstructorInjection(classDecl, serviceFields, ctx);
                }

                if (!Boolean.TRUE.equals(minimizeChanges)) doAfterVisit(new AutoFormat(null).getVisitor());
                return classDecl;
            }

            private List<LocalServiceVar> collectLocalServiceVars(J.ClassDeclaration classDecl, Cursor outerCursor) {
                List<LocalServiceVar> result = new ArrayList<>();
                new JavaIsoVisitor<Integer>() {
                    @Override
                    public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVar, Integer ignored) {
                        Cursor enclosing = getCursor().dropParentUntil(
                                v -> v instanceof J.MethodDeclaration || v instanceof J.ClassDeclaration);
                        if (!(enclosing.getValue() instanceof J.MethodDeclaration)) {
                            return super.visitVariableDeclarations(multiVar, ignored);
                        }
                        J.MethodDeclaration enclosingMethod = (J.MethodDeclaration) enclosing.getValue();
                        boolean inStaticCtx = enclosingMethod.hasModifier(J.Modifier.Type.Static);

                        if (multiVar.getVariables().size() == 1) {
                            J.VariableDeclarations.NamedVariable namedVar = multiVar.getVariables().get(0);
                            Expression initializer = namedVar.getInitializer();
                            if (initializer instanceof J.MethodInvocation && matcher.matches((J.MethodInvocation) initializer)) {
                                J.MethodInvocation inv = (J.MethodInvocation) initializer;
                                String argKey = inv.getArguments().isEmpty()
                                        ? "" : inv.getArguments().get(0).printTrimmed(outerCursor);
                                String typeStr = multiVar.getTypeExpression() != null
                                        ? multiVar.getTypeExpression().printTrimmed(outerCursor) : "Object";
                                result.add(new LocalServiceVar(namedVar.getSimpleName(), typeStr, inv, multiVar, argKey, inStaticCtx, enclosingMethod.getId()));
                            }
                        }
                        return super.visitVariableDeclarations(multiVar, ignored);
                    }
                }.visit(classDecl, 0, outerCursor.getParent());
                return result;
            }

            private Map<String, ServiceField> buildServiceFields(J.ClassDeclaration classDecl, List<LocalServiceVar> localVars, Cursor outerCursor) {
                Set<String> existingFieldNames = classDecl.getBody().getStatements().stream()
                        .filter(s -> s instanceof J.VariableDeclarations)
                        .flatMap(s -> ((J.VariableDeclarations) s).getVariables().stream())
                        .map(J.VariableDeclarations.NamedVariable::getSimpleName)
                        .collect(Collectors.toSet());

                Set<String> usedFieldNames = new HashSet<>(existingFieldNames);
                Map<String, ServiceField> serviceFields = new LinkedHashMap<>();
                for (LocalServiceVar lv : localVars) {
                    if (serviceFields.containsKey(lv.argKey)) continue;
                    boolean isStatic = localVars.stream()
                            .filter(v -> v.argKey.equals(lv.argKey))
                            .allMatch(v -> v.inStaticContext);

                    String fieldName = lv.varName;
                    boolean alreadyExists = existingFieldNames.contains(fieldName);

                    if (!alreadyExists && usedFieldNames.contains(fieldName)) {
                        // Name conflict with another service field: derive a name from the type
                        fieldName = deriveFieldName(lv.typeStr);
                        String base = fieldName;
                        int suffix = 2;
                        while (usedFieldNames.contains(fieldName)) {
                            fieldName = base + suffix++;
                        }
                    }
                    usedFieldNames.add(fieldName);

                    ServiceField sf = new ServiceField(fieldName, lv.typeStr, lv.invocation, lv.originalDecl, isStatic);
                    sf.alreadyExists = alreadyExists;
                    serviceFields.put(lv.argKey, sf);
                }
                return serviceFields;
            }

            private String deriveFieldName(String typeStr) {
                // Use the simple type name with a lowercase first character
                int dot = typeStr.lastIndexOf('.');
                String simple = dot >= 0 ? typeStr.substring(dot + 1) : typeStr;
                int lt = simple.indexOf('<');
                if (lt >= 0) simple = simple.substring(0, lt);
                if (simple.isEmpty()) return "service";
                return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
            }

            private J.ClassDeclaration renameLocalVarReferences(J.ClassDeclaration classDecl, List<LocalServiceVar> localVars,
                                                                   Map<String, ServiceField> serviceFields,
                                                                   ExecutionContext ctx, Cursor outerCursor) {
                // Build per-method rename map: methodId -> (oldName -> newName) for vars whose field name changed
                Map<java.util.UUID, Map<String, String>> renameMap = new HashMap<>();
                for (LocalServiceVar lv : localVars) {
                    ServiceField sf = serviceFields.get(lv.argKey);
                    if (sf != null && !sf.fieldName.equals(lv.varName)) {
                        renameMap.computeIfAbsent(lv.enclosingMethodId, k -> new HashMap<>())
                                 .put(lv.varName, sf.fieldName);
                    }
                }
                if (renameMap.isEmpty()) return classDecl;

                return (J.ClassDeclaration) new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                        Map<String, String> renames = renameMap.get(method.getId());
                        if (renames == null || method.getBody() == null) {
                            return super.visitMethodDeclaration(method, ctx);
                        }
                        J.Block newBody = (J.Block) new JavaIsoVisitor<Map<String, String>>() {
                            @Override
                            public J.Identifier visitIdentifier(J.Identifier identifier, Map<String, String> renames) {
                                String newName = renames.get(identifier.getSimpleName());
                                if (newName != null) {
                                    JavaType.Variable newFieldType = identifier.getFieldType() != null
                                            ? identifier.getFieldType().withName(newName) : null;
                                    return identifier.withSimpleName(newName).withFieldType(newFieldType);
                                }
                                return super.visitIdentifier(identifier, renames);
                            }
                        }.visitNonNull(method.getBody(), renames, getCursor());
                        return method.withBody(newBody);
                    }
                }.visitNonNull(classDecl, ctx, outerCursor.getParent());
            }

            private J.ClassDeclaration removeLocalVarDecls(J.ClassDeclaration classDecl, ExecutionContext ctx, Cursor outerCursor) {
                return (J.ClassDeclaration) new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.Block visitBlock(J.Block block, ExecutionContext ctx) {
                        Cursor blockParent = getCursor().getParent();
                        if (blockParent == null || !(blockParent.getValue() instanceof J.MethodDeclaration)) {
                            return super.visitBlock(block, ctx);
                        }
                        J.Block updated = block.withStatements(ListUtils.map(block.getStatements(), stmt -> {
                            if (!(stmt instanceof J.VariableDeclarations)) return stmt;
                            J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
                            if (vd.getVariables().size() != 1) return stmt;
                            Expression init = vd.getVariables().get(0).getInitializer();
                            if (init instanceof J.MethodInvocation && matcher.matches((J.MethodInvocation) init)) {
                                return null;
                            }
                            return stmt;
                        }));
                        return super.visitBlock(updated, ctx);
                    }
                }.visitNonNull(classDecl, ctx, outerCursor.getParent());
            }

            /**
             * Builds a class-level field from the original local variable declaration by:
             * 1. Replacing its modifiers with [private, (static,) final]
             * 2. Optionally stripping the initializer (for constructor injection)
             */
            private J.VariableDeclarations buildField(ServiceField sf) {
                J.VariableDeclarations original = sf.originalDecl;

                // Build new modifiers: private [static] final
                List<J.Modifier> mods = new ArrayList<>();
                mods.add(new J.Modifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, null,
                        J.Modifier.Type.Private, Collections.emptyList()));
                if (sf.isStatic) {
                    mods.add(new J.Modifier(Tree.randomId(), Space.SINGLE_SPACE, Markers.EMPTY, null,
                            J.Modifier.Type.Static, Collections.emptyList()));
                }
                mods.add(new J.Modifier(Tree.randomId(), Space.SINGLE_SPACE, Markers.EMPTY, null,
                        J.Modifier.Type.Final, Collections.emptyList()));

                J.VariableDeclarations field = original
                        .withId(Tree.randomId())
                        .withPrefix(Space.EMPTY)
                        .withModifiers(mods)
                        .withLeadingAnnotations(Collections.emptyList());

                // Rename the variable if the field name was derived from the type (name conflict resolution)
                String originalVarName = original.getVariables().get(0).getSimpleName();
                if (!sf.fieldName.equals(originalVarName)) {
                    field = field.withVariables(ListUtils.map(field.getVariables(), v -> {
                        J.Identifier nameId = v.getName();
                        JavaType.Variable newVarType = v.getVariableType() != null
                                ? v.getVariableType().withName(sf.fieldName) : null;
                        JavaType.Variable newFieldType = nameId.getFieldType() != null
                                ? nameId.getFieldType().withName(sf.fieldName) : null;
                        return v.withName(nameId.withSimpleName(sf.fieldName).withFieldType(newFieldType))
                                .withVariableType(newVarType)
                                .withId(Tree.randomId());
                    }));
                }

                // Strip initializer when constructor injection is used for instance fields
                if (Boolean.TRUE.equals(useConstructorInjection) && !sf.isStatic) {
                    field = field.withVariables(ListUtils.map(field.getVariables(), v ->
                            v.withInitializer(null)
                              .withId(Tree.randomId())));
                }

                return field;
            }

            private J.ClassDeclaration addServiceFields(J.ClassDeclaration classDecl, Map<String, ServiceField> serviceFields) {
                List<Statement> newFields = new ArrayList<>();
                for (ServiceField sf : serviceFields.values()) {
                    if (sf.alreadyExists) continue;
                    newFields.add(buildField(sf));
                }

                if (newFields.isEmpty()) return classDecl;

                List<Statement> statements = new ArrayList<>(newFields);
                statements.addAll(classDecl.getBody().getStatements());
                return classDecl.withBody(classDecl.getBody().withStatements(statements));
            }

            private J.ClassDeclaration applyConstructorInjection(J.ClassDeclaration classDecl, Map<String, ServiceField> serviceFields, ExecutionContext ctx) {
                List<ServiceField> instanceFields = serviceFields.values().stream()
                        .filter(sf -> !sf.isStatic && !sf.alreadyExists)
                        .collect(Collectors.toList());
                if (instanceFields.isEmpty()) return classDecl;

                boolean hasConstructors = classDecl.getBody().getStatements().stream()
                        .anyMatch(s -> s instanceof J.MethodDeclaration && ((J.MethodDeclaration) s).isConstructor());

                if (!hasConstructors) {
                    return addNewConstructorsWithInjection(classDecl, instanceFields, ctx);
                } else {
                    return modifyExistingConstructors(classDecl, instanceFields, ctx);
                }
            }

            /** Collects FQNs of service types and service locator types for JavaTemplate imports. */
            private String[] serviceImports(List<ServiceField> fields) {
                Set<String> fqns = new LinkedHashSet<>();
                for (ServiceField sf : fields) {
                    if (sf.originalDecl.getType() instanceof JavaType.FullyQualified) {
                        fqns.add(((JavaType.FullyQualified) sf.originalDecl.getType()).getFullyQualifiedName());
                    }
                    if (sf.invocation.getMethodType() != null
                            && sf.invocation.getMethodType().getDeclaringType() instanceof JavaType.FullyQualified) {
                        fqns.add(((JavaType.FullyQualified) sf.invocation.getMethodType().getDeclaringType()).getFullyQualifiedName());
                    }
                }
                return fqns.toArray(new String[0]);
            }

            private J.ClassDeclaration addNewConstructorsWithInjection(J.ClassDeclaration classDecl, List<ServiceField> instanceFields, ExecutionContext ctx) {
                String className = classDecl.getSimpleName();
                Cursor cdCursor = new Cursor(getCursor(), classDecl);
                String[] imports = serviceImports(instanceFields);

                String fullParams = instanceFields.stream()
                        .map(sf -> sf.typeStr + " " + sf.fieldName)
                        .collect(Collectors.joining(", "));
                String assignments = instanceFields.stream()
                        .map(sf -> "this." + sf.fieldName + " = " + sf.fieldName + ";")
                        .collect(Collectors.joining("\n"));
                String delegatingArgs = instanceFields.stream()
                        .map(sf -> sf.invocation.printTrimmed(cdCursor))
                        .collect(Collectors.joining(", "));

                // Include annotation in the template string (matches StaticServiceToSingleton pattern)
                String annotation = annotateConstructors != null
                        ? "@" + annotateConstructors.substring(annotateConstructors.lastIndexOf('.') + 1) + "\n"
                        : "";

                // Build both constructors in a single template for correct type resolution
                String template = annotation + "public " + className + "(" + fullParams + ") {\n" + assignments + "\n}"
                        + "\npublic " + className + "() {\nthis(" + delegatingArgs + ");\n}";

                J.ClassDeclaration tempCd = JavaTemplate.builder(template)
                        .imports(imports)
                        .contextSensitive()
                        .build()
                        .apply(cdCursor, classDecl.getBody().getCoordinates().lastStatement());

                List<Statement> newConstructors = tempCd.getBody().getStatements().stream()
                        .filter(s -> s instanceof J.MethodDeclaration && ((J.MethodDeclaration) s).isConstructor())
                        .map(s -> (Statement) s.withId(Tree.randomId()))
                        .collect(Collectors.toList());

                if (annotateConstructors != null) maybeAddImport(annotateConstructors, false);

                // Insert constructors after fields, before regular methods
                List<Statement> newStatements = new ArrayList<>();
                boolean inserted = false;
                for (Statement s : classDecl.getBody().getStatements()) {
                    if (!inserted && s instanceof J.MethodDeclaration && !((J.MethodDeclaration) s).isConstructor()) {
                        newStatements.addAll(newConstructors);
                        inserted = true;
                    }
                    newStatements.add(s);
                }
                if (!inserted) {
                    newStatements.addAll(newConstructors);
                }
                return classDecl.withBody(classDecl.getBody().withStatements(newStatements));
            }

            private J.ClassDeclaration modifyExistingConstructors(J.ClassDeclaration classDecl, List<ServiceField> instanceFields, ExecutionContext ctx) {
                Cursor cdCursor = new Cursor(getCursor(), classDecl);
                String[] imports = serviceImports(instanceFields);

                // Build typed AST nodes for each service field's parameter and assignment via helper methods.
                // Find helpers by name rather than by index to be robust against class body ordering.
                List<Statement> serviceParams = new ArrayList<>();
                List<Statement> serviceAssignments = new ArrayList<>();
                for (ServiceField sf : instanceFields) {
                    String helperName = "_helper_" + sf.fieldName;
                    String helperSrc = "void " + helperName + "(" + sf.typeStr + " " + sf.fieldName + ") { this." + sf.fieldName + " = " + sf.fieldName + "; }";
                    J.ClassDeclaration helperCd = JavaTemplate.builder(helperSrc)
                            .imports(imports)
                            .contextSensitive()
                            .build()
                            .apply(cdCursor, classDecl.getBody().getCoordinates().firstStatement());
                    J.MethodDeclaration helperM = helperCd.getBody().getStatements().stream()
                            .filter(s -> s instanceof J.MethodDeclaration)
                            .map(s -> (J.MethodDeclaration) s)
                            .filter(m -> m.getSimpleName().equals(helperName))
                            .findFirst()
                            .orElse(null);
                    if (helperM == null) continue;
                    serviceParams.add(helperM.getParameters().get(0));
                    serviceAssignments.add(helperM.getBody().getStatements().get(0));
                }

                J.MethodDeclaration primaryCtor = classDecl.getBody().getStatements().stream()
                        .filter(s -> s instanceof J.MethodDeclaration && ((J.MethodDeclaration) s).isConstructor())
                        .map(s -> (J.MethodDeclaration) s)
                        .max(Comparator.comparingInt(m -> (int) m.getParameters().stream().filter(p -> !(p instanceof J.Empty)).count()))
                        .orElse(null);
                if (primaryCtor == null) return classDecl;

                final J.MethodDeclaration primaryCtorFinal = primaryCtor;
                final J.ClassDeclaration cdForDelegate = classDecl;

                classDecl = classDecl.withBody(classDecl.getBody().withStatements(ListUtils.flatMap(classDecl.getBody().getStatements(), s -> {
                    if (!(s instanceof J.MethodDeclaration) || !((J.MethodDeclaration) s).isConstructor()) {
                        return Collections.singletonList(s);
                    }
                    J.MethodDeclaration m = (J.MethodDeclaration) s;

                    boolean alreadyInjected = instanceFields.stream().anyMatch(sf ->
                            m.getParameters().stream()
                                    .filter(p -> p instanceof J.VariableDeclarations)
                                    .anyMatch(p -> ((J.VariableDeclarations) p).getVariables().stream()
                                            .anyMatch(v -> v.getSimpleName().equals(sf.fieldName))));
                    boolean alreadyDelegates = m.getBody() != null && m.getBody().getStatements().stream()
                            .filter(st -> st instanceof J.MethodInvocation && ((J.MethodInvocation) st).getSimpleName().equals("this"))
                            .anyMatch(st -> instanceFields.stream().anyMatch(sf ->
                                    ((J.MethodInvocation) st).getArguments().stream()
                                            .anyMatch(arg -> arg.print(cdCursor).contains(sf.fieldName))));

                    if (alreadyInjected || alreadyDelegates) {
                        return Collections.singletonList(s);
                    }

                    if (!m.getId().equals(primaryCtorFinal.getId())) {
                        return Collections.singletonList(s);
                    }

                    // Build modified (full) constructor: service params first, then original params
                    List<Statement> nonEmptyParams = m.getParameters().stream()
                            .filter(p -> !(p instanceof J.Empty))
                            .collect(Collectors.toList());

                    List<Statement> allParams = new ArrayList<>();
                    for (Statement sp : serviceParams) {
                        allParams.add(sp.withId(Tree.randomId()));
                    }
                    allParams.addAll(nonEmptyParams);

                    J.MethodDeclaration mFull = m.withParameters(allParams).withId(Tree.randomId());

                    // Prepend service assignments to constructor body, keeping super()/this() first
                    if (mFull.getBody() != null) {
                        List<Statement> existing = mFull.getBody().getStatements();
                        List<Statement> bodyStmts = new ArrayList<>();
                        int insertIdx = 0;
                        if (!existing.isEmpty() && existing.get(0) instanceof J.MethodInvocation) {
                            String firstName = ((J.MethodInvocation) existing.get(0)).getSimpleName();
                            if ("super".equals(firstName) || "this".equals(firstName)) {
                                bodyStmts.add(existing.get(0));
                                insertIdx = 1;
                            }
                        }
                        for (Statement sa : serviceAssignments) {
                            bodyStmts.add(sa.withId(Tree.randomId()));
                        }
                        bodyStmts.addAll(existing.subList(insertIdx, existing.size()));
                        mFull = mFull.withBody(mFull.getBody().withStatements(bodyStmts));
                    }

                    // Apply annotation to the modified constructor if configured
                    if (annotateConstructors != null) {
                        String shortAnnotation = annotateConstructors.substring(annotateConstructors.lastIndexOf('.') + 1);
                        boolean alreadyAnnotated = mFull.getLeadingAnnotations().stream()
                                .anyMatch(a -> a.print(cdCursor).contains(shortAnnotation));
                        if (!alreadyAnnotated) {
                            mFull = JavaTemplate.builder("@" + shortAnnotation)
                                    .contextSensitive()
                                    .build()
                                    .apply(new Cursor(getCursor(), mFull), mFull.getCoordinates().addAnnotation((a1, a2) -> 0));
                            maybeAddImport(annotateConstructors);
                        }
                    }

                    // Build delegating constructor (original signature delegates to new full constructor)
                    String originalArgs = m.getParameters().stream()
                            .filter(p -> p instanceof J.VariableDeclarations)
                            .map(p -> ((J.VariableDeclarations) p).getVariables().get(0).getSimpleName())
                            .collect(Collectors.joining(", "));
                    String serviceLocatorArgs = instanceFields.stream()
                            .map(sf -> sf.invocation.printTrimmed(cdCursor))
                            .collect(Collectors.joining(", "));
                    String delegatingArgs = originalArgs.isEmpty()
                            ? serviceLocatorArgs
                            : serviceLocatorArgs + ", " + originalArgs;

                    String tempMethod = "void temp() { this(" + delegatingArgs + "); }";
                    J.ClassDeclaration tempClass = JavaTemplate.builder(tempMethod)
                            .imports(imports)
                            .contextSensitive()
                            .build()
                            .apply(cdCursor, cdForDelegate.getBody().getCoordinates().lastStatement());
                    J.Block delegateBlock = ((J.MethodDeclaration) tempClass.getBody().getStatements()
                            .get(tempClass.getBody().getStatements().size() - 1)).getBody();

                    J.MethodDeclaration mDelegate = m.withBody(delegateBlock).withId(Tree.randomId());

                    return Arrays.asList(mFull, mDelegate);
                })));

                return classDecl;
            }
        };
    }
}
