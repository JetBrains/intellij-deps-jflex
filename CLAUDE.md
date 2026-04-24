# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

IntelliJ-branded distribution of **JFlex**, a lexical analyzer (scanner) generator. Takes `.flex` specification files (regular expressions + actions) and generates Java or Kotlin scanners based on deterministic finite automata (DFAs).

## Build Commands

```shell
# Build the JFlex jar (skipping tests and formatting)
./mvnw package -pl jflex -am -DskipTests -Dfmt.skip=true

# Run all unit tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=EmitterTest

# Run a specific test method
./mvnw test -Dtest=EmitterTest#testSourceFileString

# Full test suite (unit + regression + examples)
./scripts/test-unit.sh
./scripts/test-regression.sh
./scripts/test-examples.sh
```

Output jar: `jflex/target/jflex-1.10.17.jar`

Note: `-Dfmt.skip=true` is needed because `fmt-maven-plugin` 2.19 is incompatible with JDK 21+.

## Architecture

### Scanner Generation Pipeline

`LexGenerator` orchestrates: **parse .flex spec → build NFA → convert to DFA → minimize DFA → emit code**

Entry point: `jflex/src/main/java/jflex/Main.java` → `LexGenerator`

### Emitter Hierarchy

Code generation uses a parallel class hierarchy for Java and Kotlin:

- `IEmitter` (abstract base) → `Emitter` (Java) / `KotlinEmitter` (Kotlin)
- Table compression strategies: `PackEmitter`, `CountEmitter`, `HiLowEmitter`, `HiCountEmitter` — each has a `Kotlin*` counterpart
- `Emitters` factory creates the appropriate emitter based on `Options.output_mode`

### Skeleton Files

Skeletons are templates with section markers (`--- label L1`, `--- label L2`, etc.) where emitters insert generated tables and code:

- `jflex/src/main/jflex/skeleton.nested` — Java skeleton
- `jflex/src/main/jflex/kotlin_skeleton.nested` — Kotlin skeleton (includes KMP-compatible utility extensions like `codePoint`, `codePointBefore`)
- `jflex/src/main/resources/jflex/idea-flex.skeleton` — IntelliJ IDEA-specific skeleton

### Bootstrapping

JFlex uses itself to generate its own scanner. `LexScan.flex` is the JFlex specification that parses `.flex` files. The build uses `jflex-maven-plugin:1.9.0` (previous release) to bootstrap.

### Core Processing (`jflex/src/main/java/jflex/core/`)

- `NFA` — nondeterministic finite automaton (intermediate representation)
- `dfa/` — DFA construction and minimization
- `RegExp` classes — regular expression tree representation
- `CharClasses` — Unicode character classification
