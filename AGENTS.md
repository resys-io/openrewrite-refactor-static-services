# Project Instructions

## Prerequisites

*   Java 21
*   Maven

## Build

To build the project, run:

```bash
mvn clean install
```

The code is located in the Java package `io.resys.openrewrite.refactor`.

## Overview

This is an OpenRewrite recipe project that converts static services into standard Java singleton services.

### Example

```java
class Service {
    public static Result action(Input input) { ... }
}

class ServiceConsumer {
    public void doThing() {
        Service.action(new Input());
    }
}
```

will be converted into:

```java
class Service {

    private static final Service INSTANCE = new Service();

    public static Service instance() {
        return INSTANCE;
    }

    public Result action(Input input) { ... }

}

class ServiceConsumer {

    private final Service service;

    public ServiceConsumer() {
        this(Service.instance());
    }

    public ServiceConsumer(Service service) {
        this.service = service;
    }

    public void doThing() {
        service.action(new Input());
    }
}
```

### Changes Made to the Service Class

1.  The `static` modifier is removed from all public static methods.
2.  A new `private static final Service INSTANCE` field is added if it does not already exist, initialized with a new class instance.
3.  A new `public static Service instance()` method is added to return the singleton `INSTANCE`.

### Changes Made to ServiceConsumer Classes

1.  A new `private final` field is added to hold a reference to the service.
2.  Existing constructors are modified to accept an instance of the service as a parameter.
3.  If no constructors exist, a new one is created that accepts the service.
4.  Optionally, a default constructor is added that initializes the service using `Service.instance()`.
5.  All static method calls are updated to use the new service field.

## Recipe Parameters

| Parameter                        | Description                                                                                                         |
|----------------------------------|---------------------------------------------------------------------------------------------------------------------|
| serviceClassName                 | The fully qualified name of the service class to refactor.                                                          |
| annotateMethods                  | The fully qualified name of the annotation to be added to new methods. If not defined, no annotation is added.      |
| annotateConstructors             | The fully qualified name of the annotation to be added to new constructors. If not defined, no annotation is added. |
| addDefaultConstructorToConsumers | Whether a default constructor should be added to consumer classes.                                                  |
| addStaticDelegateMethods         | If set to `true`, creates deprecated static methods that delegate invocations to the singleton instance.            |
