## Overview

This recipe refactors static Service Locator calls into class fields. It provides a `methodPattern` option to identify the specific service locator method.

The recipe handles the following scenarios:
* **Field Re-use**: If a field for the service already exists, the recipe reuses it and generates a warning.
* **Deduplication**: Multiple lookups of the same service within a class are consolidated into a single field.
* **Static vs. Instance**: Lookups in static methods result in `static` fields, while lookups in instance methods result in instance fields.
* **Constructor Injection**: Optionally, instance-level services can be injected via constructors instead of direct field initialization.

### Example

If our Service Locator and its consumer are
```java
class ServiceLocator {
    public static Object getService(Object serviceId) { ... }
}

class ServiceConsumer {
    public void doThing() {
        Service service = ServiceLocator.getService(Service.class);
        service.serveMe();
    }
    
    public static void doThingStatic() {
        Service2 service2 = ServiceLocator.getService(Service2.class);
        service2.doServing();
    }

}
```

will be converted into:

```java
class ServiceConsumer {

    private final Service service = ServiceLocator.getService(Service.class);
    
    private static final Service2 service2 = ServiceLocator.getService(Service2.class);

    public void doThing() {
        service.serveMe();
    }

    public static void doThingStatic() {
        service2.doServing();
    }
}
```

If a field for the service already exists and holds a reference to it, the recipe will reuse that field but generate a warning.
If the same service lookup occurs in multiple methods, the recipe will remove the duplicate lookups and use the single field instead.

An option is available to specify how the service instance is provided. If the service is requested from a non-static context, it can be injected as a constructor parameter.

Example:

```java
class ServiceConsumer {

    private final Service service;

    // The recipe modifies the original constructor by adding the service as a parameter,
    // which is then assigned to the field. Constructor visibility is preserved. In case there are multiple
    // constructors, the one that the most arguments is modified.
    ServiceConsumer(Service service, String previousArgument) {
        this.service = service;

        // Original constructor implementation here
    }

    // The original constructor signature is retained to maintain backward compatibility.
    // It delegates to the modified constructor, retrieving the service from the Service Locator. 
    ServiceConsumer(String previousArgument) {
        this(ServiceLocator.getService(Service.class), previousArgument);
    }

    public void doThing() {
        service.serveMe();
    }

}

## Recipe Parameters

| Parameter                        | Description                                                                                                         |
|----------------------------------|---------------------------------------------------------------------------------------------------------------------|
| methodPattern                    | The method pattern used to identify the service locator call (e.g. `com.example.ServiceLocator getService(..)`).     |
| useConstructorInjection          | When set to `true`, services used in non-static contexts are injected via constructor instead of field initialization.|
| annotateConstructors             | The fully qualified name of the annotation to be added to the modified constructor (e.g. `javax.inject.Inject`).    |
```
