package io.resys.openrewrite.refactor;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

class StaticServiceToSingletonTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new StaticServiceToSingleton("com.example.Service", null, null, true, false, false))
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
    void refactorConsumerClass2() {
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
    void refactorWithAnnotations() {
        rewriteRun(
            spec -> spec.recipe(new StaticServiceToSingleton("com.example.Service", "javax.inject.Singleton", "javax.inject.Inject", true, false, false)),
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
    void refactorWithStaticDelegateMethods() {
        rewriteRun(
            spec -> spec.recipe(new StaticServiceToSingleton("com.example.Service", null, null, true, true, false))
              .expectedCyclesThatMakeChanges(1),
            java(
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    public static void action() { }\n" +
                "    public static String getSomething(int id) { return \"val\"; }\n" +
                "}",
                "package com.example;\n" +
                "\n" +
                "class Service {\n" +
                "    private static final Service INSTANCE = new Service();\n" +
                "\n" +
                "    @Deprecated\n" +
                "    public static void action() {\n" +
                "        instance().action();\n" +
                "    }\n" +
                "\n" +
                "    public void action() {\n" +
                "    }\n" +
                "\n" +
                "    @Deprecated\n" +
                "    public static String getSomething(int id) {\n" +
                "        return instance().getSomething(id);\n" +
                "    }\n" +
                "\n" +
                "    public String getSomething(int id) {\n" +
                "        return \"val\";\n" +
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
            spec -> spec.recipe(new StaticServiceToSingleton("com.example.Service", null, null, true, false, true)),
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
}
