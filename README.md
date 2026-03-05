# openrewrite-refactor-static-services

An [OpenRewrite](https://docs.openrewrite.org/) recipe that converts classes with static methods into singleton services and updates all consumers.

## What it does

Given a service class with static methods:

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

The recipe produces:

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

    public ServiceConsumer(Service service) {
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

## Parameters

| Parameter                        | Required | Description |
|----------------------------------|----------|-------------|
| `serviceClassName`               | yes      | Fully qualified name of the service class to refactor (e.g. `com.example.Service`). |
| `annotateMethods`                | no       | Fully qualified annotation name to add to the generated `instance()` method (e.g. `javax.inject.Singleton`). |
| `annotateConstructors`           | no       | Fully qualified annotation name to add to the new full constructor in consumer classes (e.g. `javax.inject.Inject`). |
| `addDefaultConstructorToConsumers` | no     | When `true`, adds a no-arg constructor to consumer classes that delegates to `Service.instance()`. Default: `false`. |
| `changeStaticCallsThroughInstance` | no     | When `true`, static method calls that appear inside `static` methods of consumer classes are rewritten as `Service.instance().method()` instead of being left unchanged. Default: `false`. |
| `extractServiceInterface`        | no       | When `true`, generates an `IService` interface with all public method signatures, makes `Service` implement it, and uses the interface type in consumer classes. Default: `false`. |
| `minimizeChanges`                | no       | When `true`, skips the auto-formatting pass so that only structural AST changes are written. Useful for keeping diffs free of whitespace-only noise. Default: `false`. |
| `targetVisibilities`             | no       | List of visibility scopes that select which static methods on the Service class are de-staticified. Accepted values: `ALL`, `PUBLIC`, `PROTECTED`, `PRIVATE`, `PACKAGE`. Multiple values may be combined. Regardless of this setting, the extracted interface only includes `PUBLIC` methods. Default: `PUBLIC`. |

## Usage

### 1. Build and install locally

```bash
sdk env          # select the required JDK via sdkman
mvn clean install
```

### 2. Apply to your project via the Maven plugin

Add the plugin to the project you want to migrate:

```xml
<plugin>
  <groupId>org.openrewrite.maven</groupId>
  <artifactId>rewrite-maven-plugin</artifactId>
  <version>5.49.0</version>
  <configuration>
    <activeRecipes>
      <recipe>io.resys.openrewrite.refactor.StaticServiceToSingleton</recipe>
    </activeRecipes>
    <recipeArtifactCoordinates>
      io.resys.openrewrite:openrewrite-refactor-static-services:1.0-SNAPSHOT
    </recipeArtifactCoordinates>
  </configuration>
</plugin>
```

Then create a `rewrite.yml` in the root of the project you want to migrate:

```yaml
---
type: specs.openrewrite.org/v1beta/recipe
name: io.resys.openrewrite.refactor.StaticServiceToSingleton
displayName: Convert Static Service to Singleton
recipeList:
  - io.resys.openrewrite.refactor.StaticServiceToSingleton:
      serviceClassName: com.example.MyStaticService
      addDefaultConstructorToConsumers: true
```

Run the migration:

```bash
mvn rewrite:run
```

### 3. Programmatic usage

```java
StaticServiceToSingleton recipe = new StaticServiceToSingleton(
    "com.example.MyStaticService",  // serviceClassName
    null,                           // annotateMethods
    "javax.inject.Inject",          // annotateConstructors
    true,                           // addDefaultConstructorToConsumers
    false,                          // changeStaticCallsThroughInstance
    false,                          // extractServiceInterface
    false,                          // minimizeChanges
    null                            // targetVisibilities (null = default PUBLIC)
);
```

## Examples

### With annotations

```yaml
- io.resys.openrewrite.refactor.StaticServiceToSingleton:
    serviceClassName: com.example.Service
    annotateMethods: javax.inject.Singleton
    annotateConstructors: javax.inject.Inject
    addDefaultConstructorToConsumers: true
```

```java
// Before
class Service {
    public static void action() { }
}

// After
class Service {
    private static final Service INSTANCE = new Service();

    @Singleton
    public static Service instance() {
        return INSTANCE;
    }

    public void action() { }
}

// Consumer before
class ServiceConsumer {
    public void doThing() {
        Service.action();
    }
}

// Consumer after
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
```

### With interface extraction

```yaml
- io.resys.openrewrite.refactor.StaticServiceToSingleton:
    serviceClassName: com.example.Service
    extractServiceInterface: true
```

Generates a separate `IService.java` interface and uses the interface type throughout:

```java
// Generated IService.java
public interface IService {
    void action();
}

// Service.java
class Service implements IService {
    private static final Service INSTANCE = new Service();

    public static IService instance() {
        return INSTANCE;
    }

    public void action() { }
}

// ServiceConsumer.java - uses IService, not Service
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
```

### With changeStaticCallsThroughInstance

When consumer classes have static methods that call the service, they cannot receive an injected field.
Set `changeStaticCallsThroughInstance: true` to route those calls through `Service.instance()`:

```yaml
- io.resys.openrewrite.refactor.StaticServiceToSingleton:
    serviceClassName: com.example.Service
    changeStaticCallsThroughInstance: true
```

```java
// Before
class ServiceConsumer {
    public void instanceMethod() {
        Service.action();          // will use injected field
    }

    public static void staticHelper() {
        Service.action();          // cannot use field
    }
}

// After
class ServiceConsumer {
    private final Service service;

    public ServiceConsumer(Service service) { this.service = service; }
    public ServiceConsumer() { this(Service.instance()); }

    public void instanceMethod() {
        service.action();           // injected field
    }

    public static void staticHelper() {
        Service.instance().action(); // routed through singleton
    }
}
```

### With targetVisibilities

By default only `public static` methods are converted. Use `targetVisibilities` to include other scopes:

```yaml
- io.resys.openrewrite.refactor.StaticServiceToSingleton:
    serviceClassName: com.example.Service
    targetVisibilities:
      - PUBLIC
      - PROTECTED
```

```java
// Before
class Service {
    public static void publicAction() { }
    protected static void protectedAction() { }
    static void packageAction() { }        // not targeted
}

// After
class Service {
    private static final Service INSTANCE = new Service();

    public static Service instance() {
        return INSTANCE;
    }

    public void publicAction() { }
    protected void protectedAction() { }   // de-staticified
    static void packageAction() { }        // left unchanged
}
```

Use `ALL` to target every static method regardless of visibility:

```yaml
targetVisibilities:
  - ALL
```

Note: the generated `IService` interface (when `extractServiceInterface: true`) always contains only `PUBLIC` methods, regardless of `targetVisibilities`.

## Behaviour notes

- Static method calls inside `static` methods of consumer classes are **not** changed by default (the calling site cannot hold an injected field). Enable `changeStaticCallsThroughInstance` to handle those.
- If a consumer class has no service calls from a non-static context, it is left untouched (unless `changeStaticCallsThroughInstance` applies).
- The recipe is idempotent: re-running it on an already-migrated codebase produces no further changes.
- `causesAnotherCycle` is `true`, so OpenRewrite will run the recipe a second time to catch consumers that were parsed before the service class was transformed.
