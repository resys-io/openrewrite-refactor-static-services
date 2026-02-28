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
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void refactorServiceClass() {
        rewriteRun(
            java(
                """
                package com.example;
                
                class Service {
                    public static void action() { }
                }
                """,
                """
                package com.example;
                
                class Service {
                    private static final Service INSTANCE = new Service();
                
                    public void action() {
                    }
                
                    public static Service instance() {
                        return INSTANCE;
                    }
                }
                """
            )
        );
    }

    @Test
    void refactorConsumerClass() {
        rewriteRun(
            java(
                """
                package com.example;
                
                class Service {
                    public static void action() { }
                }
                """,
                """
                package com.example;
                
                class Service {
                    private static final Service INSTANCE = new Service();
                
                    public void action() {
                    }
                
                    public static Service instance() {
                        return INSTANCE;
                    }
                }
                """
            ),
            java(
                """
                package com.example;
                
                class ServiceConsumer {
                    public void doThing() {
                        Service.action();
                    }
                }
                """,
                """
                package com.example;
                
                class ServiceConsumer {
                    private final Service service;
                
                    public ServiceConsumer(Service service) {
                        this.service = service;
                    }
                
                    public ServiceConsumer() {
                        this(Service.instance());
                    }
                
                    public void doThing() {
                        service.action();
                    }
                }
                """
            )
        );
    }

    @Test
    void refactorWithAnnotations() {
        rewriteRun(
            spec -> spec.recipe(new StaticServiceToSingleton("com.example.Service", "javax.inject.Singleton", "javax.inject.Inject", true, false, false)),
            java(
                """
                package com.example;
                
                class Service {
                    public static void action() { }
                }
                """,
                """
                package com.example;
                
                class Service {
                    private static final Service INSTANCE = new Service();
                
                    public void action() {
                    }
                
                    @Singleton
                    public static Service instance() {
                        return INSTANCE;
                    }
                }
                """
            ),
            java(
                """
                package com.example;
                
                class ServiceConsumer {
                    public void doThing() {
                        Service.action();
                    }
                }
                """,
                """
                package com.example;
                
                class ServiceConsumer {
                    private final Service service;
                
                    @Inject
                    public ServiceConsumer(Service service) {
                        this.service = service;
                    }
                
                    public ServiceConsumer() {
                        this(Service.instance());
                    }
                
                    public void doThing() {
                        service.action();
                    }
                }
                """
            )
        );
    }

    @Test
    void refactorWithStaticDelegateMethods() {
        rewriteRun(
            spec -> spec.recipe(new StaticServiceToSingleton("com.example.Service", null, null, true, true, false)),
            java(
                """
                package com.example;
                
                class Service {
                    public static void action() { }
                    public static String getSomething(int id) { return "val"; }
                }
                """,
                """
                package com.example;
                
                class Service {
                    private static final Service INSTANCE = new Service();
                
                    @Deprecated
                    public static void action() {
                        instance().action();
                    }
                
                    public void action() {
                    }
                
                    @Deprecated
                    public static String getSomething(int id) {
                        return instance().getSomething(id);
                    }
                
                    public String getSomething(int id) {
                        return "val";
                    }
                
                    public static Service instance() {
                        return INSTANCE;
                    }
                }
                """
            )
        );
    }

    @Test
    void modifyExistingConstructor() {
        rewriteRun(
            java(
                """
                package com.example;
                
                class Service {
                    public static void action() { }
                }
                """,
                """
                package com.example;
                
                class Service {
                    private static final Service INSTANCE = new Service();
                
                    public void action() {
                    }
                
                    public static Service instance() {
                        return INSTANCE;
                    }
                }
                """
            ),
            java(
                """
                package com.example;
                
                class ServiceConsumer {

                    private String name;
                    public ServiceConsumer(String name) {
                        this.name = name;
                    }
                    public void doThing() {
                        Service.action();
                    }
                }
                """,
                """
                package com.example;
                
                class ServiceConsumer {
                
                    private final Service service;

                    private String name;
                
                    public ServiceConsumer(String name, Service service) {
                        this.name = name;
                        this.service = service;
                    }
                
                    public ServiceConsumer(String name) {
                        this(name, Service.instance());
                    }
                
                    public void doThing() {
                        service.action();
                    }
                }
                """
            )
        );
    }

    @Test
    void modifyExistingConstructorWithDelegation() {
        rewriteRun(
            java(
                """
                package com.example;
                
                class Service {
                    public static void action() { }
                }
                """,
                """
                package com.example;
                
                class Service {
                    private static final Service INSTANCE = new Service();
                
                    public void action() {
                    }
                
                    public static Service instance() {
                        return INSTANCE;
                    }
                }
                """
            ),
            java(
                """
                package com.example;
                
                class ServiceConsumer {
                    private String name;
                    public ServiceConsumer() {
                        this("default");
                    }
                    public ServiceConsumer(String name) {
                        this.name = name;
                    }
                    public void doThing() {
                        Service.action();
                    }
                }
                """,
                """
                package com.example;
                
                class ServiceConsumer {
                    private final Service service;
                    private String name;
                
                    public ServiceConsumer(Service service) {
                        this("default", service);
                    }
                
                    public ServiceConsumer() {
                        this(Service.instance());
                    }
                
                    public ServiceConsumer(String name, Service service) {
                        this.name = name;
                        this.service = service;
                    }
                
                    public ServiceConsumer(String name) {
                        this(name, Service.instance());
                    }
                
                    public void doThing() {
                        service.action();
                    }
                }
                """
            )
        );
    }

    @Test
    void extractServiceInterface() {
        rewriteRun(
            spec -> spec.recipe(new StaticServiceToSingleton("com.example.Service", null, null, true, false, true)),
            java(
                """
                package com.example;

                class Service {
                    public static void action() { }
                    public static String getData(int id) { return "data"; }
                }
                """,
                """
                package com.example;

                interface IService {
                    void action();
                    String getData(int id);
                }

                class Service implements IService {
                    private static final Service INSTANCE = new Service();

                    public void action() {
                    }

                    public String getData(int id) {
                        return "data";
                    }

                    public static IService instance() {
                        return INSTANCE;
                    }
                }
                """
            ),
            java(
                """
                package com.example;

                class ServiceConsumer {
                    public void doThing() {
                        Service.action();
                    }
                }
                """,
                """
                package com.example;

                class ServiceConsumer {
                    private final IService service;

                    public ServiceConsumer(IService service) {
                        this.service = service;
                    }

                    public ServiceConsumer() {
                        this(Service.instance());
                    }

                    public void doThing() {
                        service.action();
                    }
                }
                """
            )
        );
    }
}
