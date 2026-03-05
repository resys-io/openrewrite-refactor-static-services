# Project Instructions

## Prerequisites

*   Java 21
*   Maven
*   sdkman

## Build

Choose active JDK
```
sdk env
```

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
2.  If class does not contain any service invocations from non-static context, do not change the class.
3.  Existing constructors are modified to accept an instance of the service as a parameter
    and a delegate constructor with original signature is added that delegates construction to new constructor and getting the service using `Service.instance()`.
4.  If no constructors exist, a new one is created that accepts the service and a default constructor is added that initializes the service using `Service.instance()`.
5.  Static method calls are updated to use the new service field, if invocation is from non-static method. If calling site is static method, then invocation should not be changed. Also calls from lambda expressions should follow previous rule.


### With extractServiceInterface Parameter

When `extractServiceInterface=true`, the refactoring also creates an interface:

```java
interface IService {
    Result action(Input input);
}

class Service implements IService {
    private static final Service INSTANCE = new Service();

    public static IService instance() {
        return INSTANCE;
    }

    public Result action(Input input) { ... }
}

class ServiceConsumer {
    private final IService service;

    public ServiceConsumer(IService service) {
        this.service = service;
    }

    public ServiceConsumer() {
        this(Service.instance());
    }

    public void doThing() {
        service.action(new Input());
    }
}
```

Note: The interface type is used instead of the concrete Service type in consumer classes and the `instance()` method return type.

## Recipe Parameters

| Parameter                        | Description                                                                                                         |
|----------------------------------|---------------------------------------------------------------------------------------------------------------------|
| serviceClassName                 | The fully qualified name of the service class to refactor.                                                          |
| annotateMethods                  | The fully qualified name of the annotation to be added to new methods. If not defined, no annotation is added.      |
| annotateConstructors             | The fully qualified name of the annotation to be added to new constructors. If not defined, no annotation is added. |
| addDefaultConstructorToConsumers | Whether a default constructor should be added to consumer classes.                                                  |
| changeStaticCallsThroughInstance | If set to `true`, changes static invocations with the instance indirection `Service.doThing()` becomes `Service.instance().doThing()`.                                  |
| extractServiceInterface          | When set to `true`, creates an interface (IService) with all public methods from the Service class. The Service class will implement this interface, and the `instance()` method will return the interface type. **Note:** The interface is currently added to the same file as the Service class (not a separate file). |
| minimizeChanges                  | When set to `true`, skips the auto-formatting pass so that only structural AST changes are applied. Useful for reducing whitespace-only diffs. |
| targetVisibilities               | Selects which static methods on the Service to modify based on their current visibility. Only methods matching the chosen scope(s) will be updated. Values: `ALL`, `PUBLIC`, `PROTECTED`, `PRIVATE` or `PACKAGE´. Note: Regardless of this setting, the extracted interface is defined only from `PUBLIC` methods. Default: `PUBLIC´ |
