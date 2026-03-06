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

Folder 'specs' contains specification for receipes.

## Documentation

Whenever you add or change a recipe parameter, update both:
- `README.md` — the parameters table for the affected recipe
- `specs/<recipe-name>.md` — the specification for the affected recipe