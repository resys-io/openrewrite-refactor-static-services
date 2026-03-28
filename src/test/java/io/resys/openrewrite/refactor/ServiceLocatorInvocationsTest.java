package io.resys.openrewrite.refactor;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

class ServiceLocatorInvocationsTest implements RewriteTest {

    private static final String SERVICE_LOCATOR =
            "package com.example;\n" +
            "\n" +
            "class ServiceLocator {\n" +
            "    public static <T> T getService(Class<T> serviceId) { return null; }\n" +
            "}\n";

    private static final String SERVICE =
            "package com.example;\n" +
            "interface Service { void serveMe(); }\n";

    private static final String SERVICE2 =
            "package com.example;\n" +
            "interface Service2 { void doServing(); }\n";

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new ServiceLocatorInvocations("com.example.ServiceLocator getService(..)", null, null, null));
    }

    @Test
    void instanceMethodLookupBecomesInstanceField() {
        rewriteRun(
                java(SERVICE_LOCATOR),
                java(SERVICE),
                java(
                        "package com.example;\n" +
                        "\n" +
                        "class ServiceConsumer {\n" +
                        "    public void doThing() {\n" +
                        "        Service service = ServiceLocator.getService(Service.class);\n" +
                        "        service.serveMe();\n" +
                        "    }\n" +
                        "}",
                        "package com.example;\n" +
                        "\n" +
                        "class ServiceConsumer {\n" +
                        "    private final Service service = ServiceLocator.getService(Service.class);\n" +
                        "\n" +
                        "    public void doThing() {\n" +
                        "        service.serveMe();\n" +
                        "    }\n" +
                        "}"
                )
        );
    }

    @Test
    void staticMethodLookupBecomesStaticField() {
        rewriteRun(
                java(SERVICE_LOCATOR),
                java(SERVICE2),
                java(
                        "package com.example;\n" +
                        "\n" +
                        "class ServiceConsumer {\n" +
                        "    public static void doThingStatic() {\n" +
                        "        Service2 service2 = ServiceLocator.getService(Service2.class);\n" +
                        "        service2.doServing();\n" +
                        "    }\n" +
                        "}",
                        "package com.example;\n" +
                        "\n" +
                        "class ServiceConsumer {\n" +
                        "    private static final Service2 service2 = ServiceLocator.getService(Service2.class);\n" +
                        "\n" +
                        "    public static void doThingStatic() {\n" +
                        "        service2.doServing();\n" +
                        "    }\n" +
                        "}"
                )
        );
    }

    @Test
    void mixedContextProducesInstanceAndStaticFields() {
        rewriteRun(
                java(SERVICE_LOCATOR),
                java(SERVICE),
                java(SERVICE2),
                java(
                        "package com.example;\n" +
                        "\n" +
                        "class ServiceConsumer {\n" +
                        "    public void doThing() {\n" +
                        "        Service service = ServiceLocator.getService(Service.class);\n" +
                        "        service.serveMe();\n" +
                        "    }\n" +
                        "\n" +
                        "    public static void doThingStatic() {\n" +
                        "        Service2 service2 = ServiceLocator.getService(Service2.class);\n" +
                        "        service2.doServing();\n" +
                        "    }\n" +
                        "}",
                        "package com.example;\n" +
                        "\n" +
                        "class ServiceConsumer {\n" +
                        "    private final Service service = ServiceLocator.getService(Service.class);\n" +
                        "    private static final Service2 service2 = ServiceLocator.getService(Service2.class);\n" +
                        "\n" +
                        "    public void doThing() {\n" +
                        "        service.serveMe();\n" +
                        "    }\n" +
                        "\n" +
                        "    public static void doThingStatic() {\n" +
                        "        service2.doServing();\n" +
                        "    }\n" +
                        "}"
                )
        );
    }

    @Test
    void deduplicatesMultipleLookupsOfSameService() {
        rewriteRun(
                java(SERVICE_LOCATOR),
                java(SERVICE),
                java(
                        "package com.example;\n" +
                        "\n" +
                        "class ServiceConsumer {\n" +
                        "    public void doThing() {\n" +
                        "        Service service = ServiceLocator.getService(Service.class);\n" +
                        "        service.serveMe();\n" +
                        "    }\n" +
                        "\n" +
                        "    public void doOtherThing() {\n" +
                        "        Service service = ServiceLocator.getService(Service.class);\n" +
                        "        service.serveMe();\n" +
                        "    }\n" +
                        "}",
                        "package com.example;\n" +
                        "\n" +
                        "class ServiceConsumer {\n" +
                        "    private final Service service = ServiceLocator.getService(Service.class);\n" +
                        "\n" +
                        "    public void doThing() {\n" +
                        "        service.serveMe();\n" +
                        "    }\n" +
                        "\n" +
                        "    public void doOtherThing() {\n" +
                        "        service.serveMe();\n" +
                        "    }\n" +
                        "}"
                )
        );
    }

    @Test
    void replaceAllDifferentServices() {
        rewriteRun(
          java(SERVICE_LOCATOR),
          java(SERVICE),
          java(SERVICE2),
          java(
            "package com.example;\n" +
              "\n" +
              "class ServiceConsumer {\n" +
              "    public void doThing() {\n" +
              "        Service service = ServiceLocator.getService(Service.class);\n" +
              "        service.serveMe();\n" +
              "    }\n" +
              "\n" +
              "    public void doOtherThing() {\n" +
              "        Service2 service = ServiceLocator.getService(Service2.class);\n" +
              "        service.doServing();\n" +
              "    }\n" +
              "}",
            "package com.example;\n" +
              "\n" +
              "class ServiceConsumer {\n" +
              "    private final Service service = ServiceLocator.getService(Service.class);\n" +
              "    private final Service2 service2 = ServiceLocator.getService(Service2.class);\n" +
              "\n" +
              "    public void doThing() {\n" +
              "        service.serveMe();\n" +
              "    }\n" +
              "\n" +
              "    public void doOtherThing() {\n" +
              "        service2.doServing();\n" +
              "    }\n" +
              "}"
          )
        );
    }

    @Test
    void replaceAllDifferentServiceInvocations() {
        rewriteRun(
          java(SERVICE_LOCATOR),
          java(SERVICE),
          java(
            "package com.example;\n" +
              "\n" +
              "class ServiceConsumer {\n" +
              "    public void doThing(boolean something) {\n" +
              "        Service service = ServiceLocator.getService(Service.class);\n" +
              "        service.serveMe();\n" +
              "        if (something) {\n" +
              "            Service s = ServiceLocator.getService(Service.class);\n" +
              "            s.serveMe();\n" +
              "        }\n" +
              "    }\n" +
              "}",
            "package com.example;\n" +
              "\n" +
              "class ServiceConsumer {\n" +
              "    private final Service service = ServiceLocator.getService(Service.class);\n" +
              "\n" +
              "    public void doThing(boolean something) {\n" +
              "        service.serveMe();\n" +
              "        if (something) {\n" +
              "            service.serveMe();\n" +
              "        }\n" +
              "    }\n" +
              "}"
          )
        );
    }

    @Test
    void reuseExistingFieldWithSameName() {
        // When a class field with the same name already exists, no new field is created
        rewriteRun(
                java(SERVICE_LOCATOR),
                java(SERVICE),
                java(
                        "package com.example;\n" +
                        "\n" +
                        "class ServiceConsumer {\n" +
                        "    private final Service service = ServiceLocator.getService(Service.class);\n" +
                        "\n" +
                        "    public void doThing() {\n" +
                        "        Service service = ServiceLocator.getService(Service.class);\n" +
                        "        service.serveMe();\n" +
                        "    }\n" +
                        "}",
                        "package com.example;\n" +
                        "\n" +
                        "class ServiceConsumer {\n" +
                        "    private final Service service = ServiceLocator.getService(Service.class);\n" +
                        "\n" +
                        "    public void doThing() {\n" +
                        "        service.serveMe();\n" +
                        "    }\n" +
                        "}"
                )
        );
    }

    @Test
    void constructorInjectionWithNoExistingConstructor() {
        rewriteRun(
                spec -> spec.recipe(new ServiceLocatorInvocations("com.example.ServiceLocator getService(..)", true, null, null))
                        .typeValidationOptions(TypeValidation.none()),
                java(SERVICE_LOCATOR),
                java(SERVICE),
                java(
                        "package com.example;\n" +
                        "\n" +
                        "class ServiceConsumer {\n" +
                        "    public void doThing() {\n" +
                        "        Service service = ServiceLocator.getService(Service.class);\n" +
                        "        service.serveMe();\n" +
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
                        "        this(ServiceLocator.getService(Service.class));\n" +
                        "    }\n" +
                        "\n" +
                        "    public void doThing() {\n" +
                        "        service.serveMe();\n" +
                        "    }\n" +
                        "}"
                )
        );
    }

    @Test
    void constructorInjectionWithExistingConstructor() {
        rewriteRun(
                spec -> spec.recipe(new ServiceLocatorInvocations("com.example.ServiceLocator getService(..)", true, null, null))
                        .typeValidationOptions(TypeValidation.none()),
                java(SERVICE_LOCATOR),
                java(SERVICE),
                java(
                        "package com.example;\n" +
                        "\n" +
                        "class ServiceConsumer {\n" +
                        "    private String name;\n" +
                        "\n" +
                        "    ServiceConsumer(String name) {\n" +
                        "        super();\n" +
                        "        this.name = name;\n" +
                        "    }\n" +
                        "\n" +
                        "    public void doThing() {\n" +
                        "        Service service = ServiceLocator.getService(Service.class);\n" +
                        "        service.serveMe();\n" +
                        "    }\n" +
                        "}",
                        "package com.example;\n" +
                        "\n" +
                        "class ServiceConsumer {\n" +
                        "    private final Service service;\n" +
                        "    private String name;\n" +
                        "\n" +
                        "    ServiceConsumer(Service service, String name) {\n" +
                        "        super();\n" +
                        "        this.service = service;\n" +
                        "        this.name = name;\n" +
                        "    }\n" +
                        "\n" +
                        "    ServiceConsumer(String name) {\n" +
                        "        this(ServiceLocator.getService(Service.class), name);\n" +
                        "    }\n" +
                        "\n" +
                        "    public void doThing() {\n" +
                        "        service.serveMe();\n" +
                        "    }\n" +
                        "}"
                )
        );
    }

    @Test
    void constructorInjectionWithAnnotation() {
        rewriteRun(
                spec -> spec.recipe(new ServiceLocatorInvocations("com.example.ServiceLocator getService(..)", true, "javax.inject.Inject", null))
                        .typeValidationOptions(TypeValidation.none()),
                java(SERVICE_LOCATOR),
                java(SERVICE),
                java(
                        "package com.example;\n" +
                        "\n" +
                        "class ServiceConsumer {\n" +
                        "    public void doThing() {\n" +
                        "        Service service = ServiceLocator.getService(Service.class);\n" +
                        "        service.serveMe();\n" +
                        "    }\n" +
                        "}",
                        "package com.example;\n" +
                        "\n" +
                        "import javax.inject.Inject;\n" +
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
                        "        this(ServiceLocator.getService(Service.class));\n" +
                        "    }\n" +
                        "\n" +
                        "    public void doThing() {\n" +
                        "        service.serveMe();\n" +
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
                        "description: Test.\n" +
                        "recipeList:\n" +
                        "  - io.resys.openrewrite.refactor.ServiceLocatorInvocations:\n" +
                        "      methodPattern: \"com.example.ServiceLocator getService(..)\"\n",
                        "com.example.TestRecipe"
                ),
                java(SERVICE_LOCATOR),
                java(SERVICE),
                java(
                        "package com.example;\n" +
                        "\n" +
                        "class ServiceConsumer {\n" +
                        "    public void doThing() {\n" +
                        "        Service service = ServiceLocator.getService(Service.class);\n" +
                        "        service.serveMe();\n" +
                        "    }\n" +
                        "}",
                        "package com.example;\n" +
                        "\n" +
                        "class ServiceConsumer {\n" +
                        "    private final Service service = ServiceLocator.getService(Service.class);\n" +
                        "\n" +
                        "    public void doThing() {\n" +
                        "        service.serveMe();\n" +
                        "    }\n" +
                        "}"
                )
        );
    }
}
