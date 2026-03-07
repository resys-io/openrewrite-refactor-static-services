package io.resys.openrewrite.refactor;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import java.util.Arrays;

import static org.openrewrite.java.Assertions.java;

class StaticServiceToSingletonTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new StaticServiceToSingleton("com.example.Service", null, null, true, null, null, null, null))
          .expectedCyclesThatMakeChanges(2)
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void refactorServiceClass() {
        rewriteRun(
          spec -> spec.expectedCyclesThatMakeChanges(1),
            java(
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    public static void action() { }\n" +
                "}",
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    private static final Service INSTANCE = new Service();\n" +
                "\n" +
                "    public void action() {\n" +
                "    }\n" +
                "\n" +
                "    public static Service instance() {\n" +
                "        return INSTANCE;\n" +
                "    }\n" +
                "}"
            )
        );
    }

    @Test
    void refactorConsumerClass() {
        rewriteRun(
            java(
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    public static void action() { }\n" +
                "}",
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    private static final Service INSTANCE = new Service();\n" +
                "\n" +
                "    public void action() {\n" +
                "    }\n" +
                "\n" +
                "    public static Service instance() {\n" +
                "        return INSTANCE;\n" +
                "    }\n" +
                "}"
            ),
            java(
                "package com.example;\n" +
                "\n" +
                "class ServiceConsumer {\n" +
                "    public void doThing() {\n" +
                "        Service.action();\n" +
                "    }\n" +
                "}",
                "package com.example;\n" +
                "\n" +
                "class ServiceConsumer {\n" +
                "    private final Service service;\n" +
                "\n" +
                "    public ServiceConsumer(Service service) {\n" +
                "        this.service = service;\n" +
                "    }\n" +
                "\n" +
                "    public ServiceConsumer() {\n" +
                "        this(Service.instance());\n" +
                "    }\n" +
                "\n" +
                "    public void doThing() {\n" +
                "        service.action();\n" +
                "    }\n" +
                "}"
            )
        );
    }

    @Test
    void refactorConsumerClassWhenServiceAlreadyUpgraded() {
        // Service has already been upgraded in a previous run - only the consumer needs updating
        rewriteRun(
          spec -> spec.expectedCyclesThatMakeChanges(1),
          java(
            "package com.example;\n" +
              "\n" +
              "class ServiceConsumer {\n" +
              "    public void doThing() {\n" +
              "        Service.action();\n" +
              "    }\n" +
              "}",
            "package com.example;\n" +
              "\n" +
              "class ServiceConsumer {\n" +
              "    private final Service service;\n" +
              "\n" +
              "    public ServiceConsumer(Service service) {\n" +
              "        this.service = service;\n" +
              "    }\n" +
              "\n" +
              "    public ServiceConsumer() {\n" +
              "        this(Service.instance());\n" +
              "    }\n" +
              "\n" +
              "    public void doThing() {\n" +
              "        service.action();\n" +
              "    }\n" +
              "}"
          )
        );
    }

    @Test
    void doNotRefactorConsumerClassWhenCallingNonStaticServiceMethod() {
        rewriteRun(
          spec -> spec.expectedCyclesThatMakeChanges(0),
          java(
            "package com.example;\n" +
              "\n" +
              "class ServiceConsumer {\n" +
              "    Service service;\n" +
              "    public void doThing() {\n" +
              "        service.action();\n" +
              "    }\n" +
              "}")
        );
    }


    @Test
    void refactorWithAnnotations() {
        rewriteRun(
            spec -> spec.recipe(new StaticServiceToSingleton("com.example.Service", "javax.inject.Singleton", "javax.inject.Inject", true, null, null, null, null)),
            java(
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    public static void action() { }\n" +
                "}",
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    private static final Service INSTANCE = new Service();\n" +
                "\n" +
                "    public void action() {\n" +
                "    }\n" +
                "\n" +
                "    @Singleton\n" +
                "    public static Service instance() {\n" +
                "        return INSTANCE;\n" +
                "    }\n" +
                "}"
            ),
            java(
                "package com.example;\n" +
                "\n" +
                "class ServiceConsumer {\n" +
                "    public void doThing() {\n" +
                "        Service.action();\n" +
                "    }\n" +
                "}",
                "package com.example;\n" +
                "\n" +
                "class ServiceConsumer {\n" +
                "    private final Service service;\n" +
                "\n" +
                "    @Inject\n" +
                "    public ServiceConsumer(Service service) {\n" +
                "        this.service = service;\n" +
                "    }\n" +
                "\n" +
                "    public ServiceConsumer() {\n" +
                "        this(Service.instance());\n" +
                "    }\n" +
                "\n" +
                "    public void doThing() {\n" +
                "        service.action();\n" +
                "    }\n" +
                "}"
            )
        );
    }

    @Test
    void changeStaticCallsThroughInstance() {
        rewriteRun(
            spec -> spec.recipe(new StaticServiceToSingleton("com.example.Service", null, null, true, true, null, null, null)),
            java(
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    public static void action() { }\n" +
                "}",
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    private static final Service INSTANCE = new Service();\n" +
                "\n" +
                "    public void action() {\n" +
                "    }\n" +
                "\n" +
                "    public static Service instance() {\n" +
                "        return INSTANCE;\n" +
                "    }\n" +
                "}"
            ),
            java(
                "package com.example;\n" +
                "\n" +
                "class ServiceConsumer {\n" +
                "    public void nonStaticMethod() {\n" +
                "        Service.action();\n" +
                "    }\n" +
                "\n" +
                "    public static void staticMethod() {\n" +
                "        Service.action();\n" +
                "    }\n" +
                "}",
                "package com.example;\n" +
                "\n" +
                "class ServiceConsumer {\n" +
                "    private final Service service;\n" +
                "\n" +
                "    public ServiceConsumer(Service service) {\n" +
                "        this.service = service;\n" +
                "    }\n" +
                "\n" +
                "    public ServiceConsumer() {\n" +
                "        this(Service.instance());\n" +
                "    }\n" +
                "\n" +
                "    public void nonStaticMethod() {\n" +
                "        service.action();\n" +
                "    }\n" +
                "\n" +
                "    public static void staticMethod() {\n" +
                "        Service.instance().action();\n" +
                "    }\n" +
                "}"
            )
        );
    }

    @Test
    void modifyExistingConstructor() {
        rewriteRun(
            java(
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    public static void action() { }\n" +
                "}",
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    private static final Service INSTANCE = new Service();\n" +
                "\n" +
                "    public void action() {\n" +
                "    }\n" +
                "\n" +
                "    public static Service instance() {\n" +
                "        return INSTANCE;\n" +
                "    }\n" +
                "}"
            ),
            java(
                "package com.example;\n" +
                "\n" +
                "class ServiceConsumer {\n" +
                "\n" +
                "    private String name;\n" +
                "    public ServiceConsumer(String name) {\n" +
                "        this.name = name;\n" +
                "    }\n" +
                "    public void doThing() {\n" +
                "        Service.action();\n" +
                "    }\n" +
                "}",
                "package com.example;\n" +
                "\n" +
                "class ServiceConsumer {\n" +
                "\n" +
                "    private final Service service;\n" +
                "\n" +
                "    private String name;\n" +
                "\n" +
                "    public ServiceConsumer(String name, Service service) {\n" +
                "        this.name = name;\n" +
                "        this.service = service;\n" +
                "    }\n" +
                "\n" +
                "    public ServiceConsumer(String name) {\n" +
                "        this(name, Service.instance());\n" +
                "    }\n" +
                "\n" +
                "    public void doThing() {\n" +
                "        service.action();\n" +
                "    }\n" +
                "}"
            )
        );
    }

    @Test
    void modifyExistingConstructorWithDelegation() {
        rewriteRun(
            java(
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    public static void action() { }\n" +
                "}",
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    private static final Service INSTANCE = new Service();\n" +
                "\n" +
                "    public void action() {\n" +
                "    }\n" +
                "\n" +
                "    public static Service instance() {\n" +
                "        return INSTANCE;\n" +
                "    }\n" +
                "}"
            ),
            java(
                "package com.example;\n" +
                "\n" +
                "class ServiceConsumer {\n" +
                "    private String name;\n" +
                "    public ServiceConsumer() {\n" +
                "        this(\"default\");\n" +
                "    }\n" +
                "    public ServiceConsumer(String name) {\n" +
                "        this.name = name;\n" +
                "    }\n" +
                "    public void doThing() {\n" +
                "        Service.action();\n" +
                "    }\n" +
                "}",
                "package com.example;\n" +
                "\n" +
                "class ServiceConsumer {\n" +
                "    private final Service service;\n" +
                "    private String name;\n" +
                "\n" +
                "    public ServiceConsumer(Service service) {\n" +
                "        this(\"default\", service);\n" +
                "    }\n" +
                "\n" +
                "    public ServiceConsumer() {\n" +
                "        this(Service.instance());\n" +
                "    }\n" +
                "\n" +
                "    public ServiceConsumer(String name, Service service) {\n" +
                "        this.name = name;\n" +
                "        this.service = service;\n" +
                "    }\n" +
                "\n" +
                "    public ServiceConsumer(String name) {\n" +
                "        this(name, Service.instance());\n" +
                "    }\n" +
                "\n" +
                "    public void doThing() {\n" +
                "        service.action();\n" +
                "    }\n" +
                "}"
            )
        );
    }

    @Test
    void extractServiceInterface() {
        rewriteRun(
            spec -> spec.recipe(new StaticServiceToSingleton("com.example.Service", null, null, true, null, true, null, null)),
            java(
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    public static void action() { }\n" +
                "    public static String getData(int id) { return \"data\"; }\n" +
                "}",
                "package com.example;\n" +
                "\n" +
                "class Service implements IService {\n" +
                "    private static final Service INSTANCE = new Service();\n" +
                "\n" +
                "    public void action() {\n" +
                "    }\n" +
                "\n" +
                "    public String getData(int id) {\n" +
                "        return \"data\";\n" +
                "    }\n" +
                "\n" +
                "    public static IService instance() {\n" +
                "        return INSTANCE;\n" +
                "    }\n" +
                "}"
            ),
            java(
                null,
                "package com.example;\n" +
                "\n" +
                "public interface IService {\n" +
                "    void action();\n" +
                "    String getData(int id);\n" +
                "}",
                spec -> spec.path("com/example/IService.java")
            ),
            java(
                "package com.example;\n" +
                "\n" +
                "class ServiceConsumer {\n" +
                "    public void doThing() {\n" +
                "        Service.action();\n" +
                "    }\n" +
                "}",
                "package com.example;\n" +
                "\n" +
                "class ServiceConsumer {\n" +
                "    private final IService service;\n" +
                "\n" +
                "    public ServiceConsumer(IService service) {\n" +
                "        this.service = service;\n" +
                "    }\n" +
                "\n" +
                "    public ServiceConsumer() {\n" +
                "        this(Service.instance());\n" +
                "    }\n" +
                "\n" +
                "    public void doThing() {\n" +
                "        service.action();\n" +
                "    }\n" +
                "}"
            )
        );
    }

    @Test
    void doNotTransformStaticMethodCalls() {
        rewriteRun(
            java(
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    public static void action() { }\n" +
                "}",
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    private static final Service INSTANCE = new Service();\n" +
                "\n" +
                "    public void action() {\n" +
                "    }\n" +
                "\n" +
                "    public static Service instance() {\n" +
                "        return INSTANCE;\n" +
                "    }\n" +
                "}"
            ),
            java(
                "package com.example;\n" +
                "\n" +
                "class ServiceConsumer {\n" +
                "    public void nonStaticMethod() {\n" +
                "        Service.action();\n" +
                "    }\n" +
                "\n" +
                "    public static void staticMethod() {\n" +
                "        Service.action();\n" +
                "    }\n" +
                "}",
                "package com.example;\n" +
                "\n" +
                "class ServiceConsumer {\n" +
                "    private final Service service;\n" +
                "\n" +
                "    public ServiceConsumer(Service service) {\n" +
                "        this.service = service;\n" +
                "    }\n" +
                "\n" +
                "    public ServiceConsumer() {\n" +
                "        this(Service.instance());\n" +
                "    }\n" +
                "\n" +
                "    public void nonStaticMethod() {\n" +
                "        service.action();\n" +
                "    }\n" +
                "\n" +
                "    public static void staticMethod() {\n" +
                "        Service.action();\n" +
                "    }\n" +
                "}"
            )
        );
    }

    @Test
    void transformLambdaInNonStaticMethod() {
        rewriteRun(
            java(
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    public static void action() { }\n" +
                "}",
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    private static final Service INSTANCE = new Service();\n" +
                "\n" +
                "    public void action() {\n" +
                "    }\n" +
                "\n" +
                "    public static Service instance() {\n" +
                "        return INSTANCE;\n" +
                "    }\n" +
                "}"
            ),
            java(
                "package com.example;\n" +
                "\n" +
                "class ServiceConsumer {\n" +
                "    public void nonStaticMethod() {\n" +
                "        Runnable r = () -> Service.action();\n" +
                "    }\n" +
                "}",
                "package com.example;\n" +
                "\n" +
                "class ServiceConsumer {\n" +
                "    private final Service service;\n" +
                "\n" +
                "    public ServiceConsumer(Service service) {\n" +
                "        this.service = service;\n" +
                "    }\n" +
                "\n" +
                "    public ServiceConsumer() {\n" +
                "        this(Service.instance());\n" +
                "    }\n" +
                "\n" +
                "    public void nonStaticMethod() {\n" +
                "        Runnable r = () -> service.action();\n" +
                "    }\n" +
                "}"
            )
        );
    }

    @Test
    void configureViaYaml() {
        rewriteRun(
            spec -> spec.recipeFromYaml(
                "---\n" +
                "type: specs.openrewrite.org/v1beta/recipe\n" +
                "name: com.example.TestRecipe\n" +
                "displayName: Test Recipe\n" +
                "description: Test recipe for YAML configuration.\n" +
                "recipeList:\n" +
                "  - io.resys.openrewrite.refactor.StaticServiceToSingleton:\n" +
                "      serviceClassName: com.example.Service\n" +
                "      addDefaultConstructorToConsumers: true\n",
                "com.example.TestRecipe"
            ).expectedCyclesThatMakeChanges(2).typeValidationOptions(TypeValidation.none()),
            java(
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    public static void action() { }\n" +
                "}",
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    private static final Service INSTANCE = new Service();\n" +
                "\n" +
                "    public void action() {\n" +
                "    }\n" +
                "\n" +
                "    public static Service instance() {\n" +
                "        return INSTANCE;\n" +
                "    }\n" +
                "}"
            ),
            java(
                "package com.example;\n" +
                "\n" +
                "class ServiceConsumer {\n" +
                "    public void doThing() {\n" +
                "        Service.action();\n" +
                "    }\n" +
                "}",
                "package com.example;\n" +
                "\n" +
                "class ServiceConsumer {\n" +
                "    private final Service service;\n" +
                "\n" +
                "    public ServiceConsumer(Service service) {\n" +
                "        this.service = service;\n" +
                "    }\n" +
                "\n" +
                "    public ServiceConsumer() {\n" +
                "        this(Service.instance());\n" +
                "    }\n" +
                "\n" +
                "    public void doThing() {\n" +
                "        service.action();\n" +
                "    }\n" +
                "}"
            )
        );
    }

    @Test
    void minimizeChangesSkipsAutoFormat() {
        // With minimizeChanges=true, AutoFormat is skipped — structural changes still apply
        // but whitespace is not normalized by a separate AutoFormat pass.
        rewriteRun(
            spec -> spec.recipe(new StaticServiceToSingleton("com.example.Service", null, null, true, null, null, true, null))
                        .expectedCyclesThatMakeChanges(1),
            java(
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    public static void action() { }\n" +
                "}",
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    private static final Service INSTANCE = new Service();\n" +
                "    public void action() { }\n" +
                "\n" +
                "    public static Service instance() {\n" +
                "        return INSTANCE;\n" +
                "    }\n" +
                "}"
            )
        );
    }

    @Test
    void doNotTransformLambdaInStaticMethod() {
        rewriteRun(
          spec -> spec.expectedCyclesThatMakeChanges(1),
            java(
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    public static void action() { }\n" +
                "}",
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    private static final Service INSTANCE = new Service();\n" +
                "\n" +
                "    public void action() {\n" +
                "    }\n" +
                "\n" +
                "    public static Service instance() {\n" +
                "        return INSTANCE;\n" +
                "    }\n" +
                "}"
            ),
            java(
                "package com.example;\n" +
                "\n" +
                "class ServiceConsumer {\n" +
                "    public static void staticMethod() {\n" +
                "        Runnable r = () -> Service.action();\n" +
                "    }\n" +
                "}"
            )
        );
    }

    @Test
    void targetVisibilitiesAll() {
        rewriteRun(
            spec -> spec.recipe(new StaticServiceToSingleton("com.example.Service", null, null, true, null, null, null,
                                    Arrays.asList("ALL")))
                        .expectedCyclesThatMakeChanges(1),
            java(
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    public static void publicAction() { }\n" +
                "    protected static void protectedAction() { }\n" +
                "    static void packageAction() { }\n" +
                "    private static void privateAction() { }\n" +
                "}",
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    private static final Service INSTANCE = new Service();\n" +
                "\n" +
                "    public void publicAction() {\n" +
                "    }\n" +
                "\n" +
                "    protected void protectedAction() {\n" +
                "    }\n" +
                "\n" +
                "    void packageAction() {\n" +
                "    }\n" +
                "\n" +
                "    private void privateAction() {\n" +
                "    }\n" +
                "\n" +
                "    public static Service instance() {\n" +
                "        return INSTANCE;\n" +
                "    }\n" +
                "}"
            )
        );
    }

    @Test
    void targetVisibilitiesPublicOnly() {
        // Default behaviour: only public static methods are de-staticified
        rewriteRun(
            spec -> spec.recipe(new StaticServiceToSingleton("com.example.Service", null, null, true, null, null, null,
                                    Arrays.asList("PUBLIC")))
                        .expectedCyclesThatMakeChanges(1),
            java(
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    public static void publicAction() { }\n" +
                "    protected static void protectedAction() { }\n" +
                "    static void packageAction() { }\n" +
                "}",
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    private static final Service INSTANCE = new Service();\n" +
                "\n" +
                "    public void publicAction() {\n" +
                "    }\n" +
                "\n" +
                "    protected static void protectedAction() {\n" +
                "    }\n" +
                "\n" +
                "    static void packageAction() {\n" +
                "    }\n" +
                "\n" +
                "    public static Service instance() {\n" +
                "        return INSTANCE;\n" +
                "    }\n" +
                "}"
            )
        );
    }

    @Test
    void targetVisibilitiesMultiple() {
        rewriteRun(
            spec -> spec.recipe(new StaticServiceToSingleton("com.example.Service", null, null, true, null, null, null,
                                    Arrays.asList("PUBLIC", "PROTECTED")))
                        .expectedCyclesThatMakeChanges(1),
            java(
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    public static void publicAction() { }\n" +
                "    protected static void protectedAction() { }\n" +
                "    static void packageAction() { }\n" +
                "}",
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    private static final Service INSTANCE = new Service();\n" +
                "\n" +
                "    public void publicAction() {\n" +
                "    }\n" +
                "\n" +
                "    protected void protectedAction() {\n" +
                "    }\n" +
                "\n" +
                "    static void packageAction() {\n" +
                "    }\n" +
                "\n" +
                "    public static Service instance() {\n" +
                "        return INSTANCE;\n" +
                "    }\n" +
                "}"
            )
        );
    }
    @Test
    void selfInvokedStaticCall() {
        rewriteRun(
          spec -> spec.expectedCyclesThatMakeChanges(1),
          java(
              "package com.example;\n" +
              "\n" +
              "class Service {\n" +
              "  public static void execute1() {\n" +
              "    execute2();\n" +
              "  }\n" +
              "  public static void execute2() {\n" +
              "    Service.execute1();\n" +
              "  }\n" +
              "}",
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    private static final Service INSTANCE = new Service();\n" +
                "\n" +
                "    public void execute1() {\n" +
                "        execute2();\n" +
                "    }\n" +
                "\n" +
                "    public void execute2() {\n" +
                "        execute1();\n" +
                "    }\n" +
                "\n" +
                "    public static Service instance() {\n" +
                "        return INSTANCE;\n" +
                "    }\n" +
                "}"
          )
        );
    }

    @Test
    void updateFieldsRemovesStaticFromNonPublicFieldsAccessedByDeStaticifiedMethods() {
        rewriteRun(
            spec -> spec.recipe(new StaticServiceToSingleton("com.example.Service", null, null, true, null, null, null, null, true, null, null))
                    .expectedCyclesThatMakeChanges(1),
            java(
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    private static int counter = 0;\n" +               // accessed, non-public, non-final → remove static
                "    static String cache = null;\n" +                   // accessed, package-private, non-final → remove static
                "    private static final String CONSTANT = \"x\";\n" + // final + String (immutable) → skip
                "    private static final int MAX = 10;\n" +            // final + primitive → skip
                "    public static final String PUB = \"y\";\n" +       // public → not handled by updateFields
                "\n" +
                "    public static void action() {\n" +
                "        counter++;\n" +
                "        cache = \"x\";\n" +
                "    }\n" +
                "}",
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    private static final Service INSTANCE = new Service();\n" +
                "    private int counter = 0;\n" +
                "    String cache = null;\n" +
                "    private static final String CONSTANT = \"x\";\n" +
                "    private static final int MAX = 10;\n" +
                "    public static final String PUB = \"y\";\n" +
                "\n" +
                "    public void action() {\n" +
                "        counter++;\n" +
                "        cache = \"x\";\n" +
                "    }\n" +
                "\n" +
                "    public static Service instance() {\n" +
                "        return INSTANCE;\n" +
                "    }\n" +
                "}"
            )
        );
    }

    @Test
    void updateWrittenFieldsRemovesStaticFromPublicFieldsWrittenByDeStaticifiedMethods() {
        rewriteRun(
            spec -> spec.recipe(new StaticServiceToSingleton("com.example.Service", null, null, true, null, null, null, null, null, true, null))
                    .expectedCyclesThatMakeChanges(1),
            java(
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    public static int counter = 0;\n" +    // written → remove static
                "    public static final int MAX = 10;\n" + // not written → keep
                "    private static int hidden = 0;\n" +    // non-public → not handled by updateWrittenFields
                "\n" +
                "    public static void action() {\n" +
                "        counter++;\n" +
                "    }\n" +
                "}",
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    private static final Service INSTANCE = new Service();\n" +
                "    public int counter = 0;\n" +
                "    public static final int MAX = 10;\n" +
                "    private static int hidden = 0;\n" +
                "\n" +
                "    public void action() {\n" +
                "        counter++;\n" +
                "    }\n" +
                "\n" +
                "    public static Service instance() {\n" +
                "        return INSTANCE;\n" +
                "    }\n" +
                "}"
            )
        );
    }

    @Test
    void updateAccessedFieldsRemovesStaticFromPublicFieldsAccessedByDeStaticifiedMethods() {
        rewriteRun(
            spec -> spec.recipe(new StaticServiceToSingleton("com.example.Service", null, null, true, null, null, null, null, null, null, true))
                    .expectedCyclesThatMakeChanges(1),
            java(
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    public static int counter = 0;\n" +           // accessed, public → remove static
                "    public static final String NAME = \"x\";\n" + // final + String (immutable) → skip
                "    public static final int MAX_SIZE = 10;\n" +   // ALL_CAPS → skip
                "    private static int hidden = 0;\n" +           // non-public → not handled by updateAccessedFields
                "\n" +
                "    public static void action() {\n" +
                "        counter++;\n" +
                "    }\n" +
                "}",
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    private static final Service INSTANCE = new Service();\n" +
                "    public int counter = 0;\n" +
                "    public static final String NAME = \"x\";\n" +
                "    public static final int MAX_SIZE = 10;\n" +
                "    private static int hidden = 0;\n" +
                "\n" +
                "    public void action() {\n" +
                "        counter++;\n" +
                "    }\n" +
                "\n" +
                "    public static Service instance() {\n" +
                "        return INSTANCE;\n" +
                "    }\n" +
                "}"
            )
        );
    }
}
