# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

IntelliJ-branded fork of **JFlex**, a lexical analyzer (scanner) generator. It takes `.flex`
specification files (regular expressions + actions) and generates Java or Kotlin scanners based on
deterministic finite automata (DFAs).

Upstream is `jflex-de/jflex`; this fork is `JetBrains/intellij-deps-jflex`, published under groupId
`org.jetbrains.intellij.deps.jflex` to JetBrains Space. Release branches are named `intellij/<version>`.

Two things distinguish the fork from upstream, and nearly all local work touches one of them:

1. **Kotlin output.** `--output-mode kotlin` emits a Kotlin scanner via a parallel emitter hierarchy.
2. **The IntelliJ skeleton.** `Skeleton.DEFAULT_LOC` is `jflex/idea-flex.skeleton`, not upstream's
   `skeleton.default` — the generated scanner exposes the IntelliJ incremental-lexer API
   (`reset(CharSequence, int, int, int)`, `getTokenStart()`, `getTokenEnd()`) over a `CharSequence`
   buffer, and `zzRefill()` is a stub because input is handed over whole rather than streamed.

## Build and test commands

```shell
# Build the JFlex jar
./mvnw package -pl jflex -am -DskipTests -Dfmt.skip=true

# All unit tests (root reactor: cup-maven-plugin, jflex, jflex-maven-plugin, testsuite, benchmark)
./mvnw test -Dfmt.skip=true

# A single test class or method — the -pl is required, see below
./mvnw test -pl jflex -Dtest=KotlinEmitterTest -Dfmt.skip=true
./mvnw test -pl jflex -Dtest=EmitterTest#testSourceFileString -Dfmt.skip=true

# Faster full build (skips tests, javadoc, formatting)
./mvnw -Pfastbuild install
```

Output jar: `jflex/target/jflex-<version>.jar` (currently `1.10.17`, from the root pom `<version>`).
Compiles to Java 8 bytecode; requires Maven 3.5.2+ (the wrapper pins 3.5.4).

Three command details that are easy to get wrong:

- **`-Dtest=...` needs `-pl jflex`.** Without it the reactor reaches `cup-maven-plugin` first, finds
  no matching test, and fails the build with `No tests matching pattern`.
- **`-Dfmt.skip=true` is not just about JDK 21.** The `format-java` profile auto-activates on JDK 11+
  whenever `env.CI` is unset and runs `fmt:format`, which **rewrites sources in place**. Omitting the
  flag will reformat files in your working tree as a side effect of building. (The plugin, version
  2.19, is also incompatible with JDK 21+.) CI never reformats — `!env.CI` disables the profile — and
  policies formatting separately in `gjf.yml` via `scripts/test-java-format.sh` (google-java-format
  1.15.0, `--dry-run --set-exit-if-changed`).
- `jflex/src/test/java/jflex/generator/KotlinEmitterTest.java` is currently **not**
  google-java-format-clean (it is indented with 4 spaces). Any build without `-Dfmt.skip=true`
  rewrites it, and the `gjf.yml` job flags it as-is.

### Golden-file tests

`KotlinEmitterTest` compares the whole emitted scanner against a committed expectation, after
stripping the two leading comment lines (JFlex version + absolute spec path) so the golden is neither
version- nor machine-specific. To refresh after an intentional emitter change:

```shell
./mvnw test -pl jflex -Dtest=KotlinEmitterTest -Dfmt.skip=true
cp jflex/target/test-output/KotlinEmitterTest/Issue15EofLexer.kt \
   jflex/src/test/resources/jflex/eof-kotlin-issue15.kt.golden
```

Note this golden is **not compilable Kotlin**: the test runs with the default (Java) skeleton, so the
file interleaves Java skeleton bodies (`public static final int YYEOF = -1;`) with Kotlin-emitted
tables (`intArrayOf(...)`, `@JvmStatic`). It guards the emitted fragments only.

### Regression suite

```shell
# All regression cases — must run from its own directory, relative paths depend on it
cd testsuite/testcases && ../../mvnw test

# One case (a directory name under src/test/cases/)
cd testsuite/testcases && ../../mvnw test -Dtestcases=dot
```

**This suite does not currently run in this fork.** Two independent breakages, both confirmed:

1. `JFlexTestsuiteMojo.jflexUberJarFilename` defaults to `jflex/target/jflex-full-${version}.jar`,
   but the shade plugin was changed to *replace* the main artifact rather than produce a second one
   (IDEA-332177), so the build yields `jflex-<version>.jar` + `original-jflex-<version>.jar` and
   never a `-full` jar. The parameter has no `property=`, so `-DjflexUberJarFilename=...` does not
   override it — it has to be set in the pom.
2. Even given the right path, `JFlexTestsuiteMojo.java:53` hardcodes the **upstream** groupId
   (`PomUtils.getPomVersion("de.jflex", "jflex", jflexUberJar)`), while the fork's jar carries
   `META-INF/maven/org.jetbrains.intellij.deps.jflex/jflex/pom.properties`. It fails with
   `Missing POM property`.

The stale `jflex-full` name also appears in `scripts/mk-release.sh:11`, `jflex/pom.xml`'s
`copy-jar-to-lib` execution (which silently copies nothing), and `jflex/examples/common/include.xml`
(so the ant example builds are affected too).

A case directory holds `<name>.test` (directives parsed by `testsuite/jflex-testsuite-maven-plugin/src/main/jflex/TestLoader.flex`
— `jflex:`, `jflex-fail:`, `jflex-diff:`, `javac-fail:`, encodings, `jdk:`), the `.flex` grammar, and
golden `.output` files (scanner stdout) plus `<name>-flex.output` (generator stdout). Only 18 cases
remain here, nearly all Unicode; the rest moved to Bazel under `javatests/de/jflex/testcase/`.

### CI scripts

`scripts/test-unit.sh` runs `./mvnw install` (not `test`) — it installs jflex into the local repo
because downstream suites consume the installed artifact. `scripts/test-regression.sh` and
`scripts/test-examples.sh` cover the other two suites. `scripts/run-tests.sh` is a dev-only driver
dispatching on `$TEST_SUITE`; its `ant` branch references a missing `scripts/ant-build.sh` and fails
under `set -e`.

## Architecture

### Two parallel builds

Maven and Bazel are both live, and CI runs both. They cover **different** trees:

- **Maven** builds `jflex`, `jflex-maven-plugin`, `cup-maven-plugin`, `testsuite`, `benchmark`, and
  `jflex/examples/`. Source lives in `jflex/src/main/java/jflex/`.
- **Bazel** additionally builds `java/de/jflex/` and `javatests/de/jflex/` (183 `BUILD.bazel` files,
  `.bazelversion` 5.4.0, legacy `WORKSPACE`, no bzlmod). These trees are Bazel-only — no pom
  references them — and hold the Unicode data generators, the `de.jflex.testing.*` harnesses, and
  ~93 regression tests.

BUILD files are hand-maintained and drift: `jflex/src/test/java/jflex/generator/BUILD.bazel` lists
only `EmitterTest` and `PackEmitterTest`, so `bazel test //jflex/...` does not run `KotlinEmitterTest`.
A new Maven test needs a Bazel target too, or it silently won't run there. Build files must be named
`BUILD.bazel` (enforced by `scripts/test-bzl-format.sh`).

### Generation pipeline

`LexGenerator.generate()` (`jflex/src/main/java/jflex/generator/LexGenerator.java:54`) is the whole
orchestration:

`LexScan` → `LexParse` → **NFA** → `DfaFactory.createFromNfa` → `dfa.minimize()` → `Emitters.createFileEmitter` → `emitter.emit()`

The surprising part is step 2: the entire front end — macro expansion, char-class partitioning,
semantic checks, and NFA construction — happens inside the CUP action for the `specification`
production (`jflex/src/main/cup/LexParse.cup:242-311`), not in Java code. `parser.parse().value` *is*
the finished `NFA`. Most `%`-directives from the user's spec never reach the parser at all; the lexer
writes them straight into `AbstractLexScan`'s fields. `DFA.minimize()` is Hopcroft's algorithm
inlined over hand-managed `int[]` linked lists.

Entry point `jflex/src/main/java/jflex/Main.java` hand-rolls flag parsing into the all-static
`Options`. With zero file arguments it opens a legacy AWT GUI (`jflex/src/main/java/jflex/gui/`).

### Emitter hierarchy — the Java and Kotlin paths are forks, not a shared base

```
IEmitter (abstract)            PackEmitter (abstract)     KotlinPackEmitter (abstract)
├── Emitter        (final)     └── CountEmitter           └── KotlinCountEmitter
└── KotlinEmitter  (final)         └── HiCountEmitter         └── KotlinHiCountEmitter
                               └── HiLowEmitter           └── KotlinHiLowEmitter
        ▲
   Emitters (factory, switches on Options.output_mode)
```

**This is the single most important thing to know about the codebase.** `Emitter` (1466 lines) and
`KotlinEmitter` (1450 lines) are copy-paste forks that share nothing but `IEmitter` (one field, one
abstract method, and two static helpers both subclasses shadow) — `diff` reports ~950 differing
lines out of ~1460. They have identical method sets and identical
`skel.emitNext()` sequencing; the differences are purely emitted syntax (`when` vs `switch`,
`intArrayOf`, `@JvmStatic`, `@Throws(X::class)`). The compression emitters are forked the same way,
with no common superclass. So:

- **A fix in `Emitter` almost always needs mirroring in `KotlinEmitter`, and vice versa.** The recent
  history is largely Kotlin-only fixes for divergences introduced by the copy (`#15` bare `break` in
  `emitEOFVal`, `Action.Kind.GENERAL_LOOK` handling, stray `;`, `offsetByCodePoints`).
- Any `KotlinEmitter` change means refreshing the golden above.
- `KotlinEmitter.java:31` still claims `@version JFlex 1.10.0`; version tags are hand-maintained
  (`scripts/prepare-release.pl` only strips `-SNAPSHOT`).

Table compression: all generated `int[]` tables are encoded as string constants and unpacked at
class-init. `PackEmitter` handles the two hard limits — a line break every 16 entries and a chunk
break at `0xFFFF - 6` UTF-8 bytes, because a class-file `CONSTANT_Utf8` is 2-byte length-prefixed.
`CountEmitter` is run-length `(count, value)`; `HiCountEmitter` is the same with 32-bit values (used
when `states > 0xFFFF`); `HiLowEmitter` is plain 2-char high/low, used for the row map where values
don't repeat. `--pack` is a documented no-op — it is the only generation method.

### Skeletons

Skeletons are templates split into **exactly 21 sections** by lines starting with `---`; emitters
insert generated tables and code between them. The text after `---` is a human comment and is
discarded, so **sections are positional**. A skeleton with any other count is rejected with
`WRONG_SKELETON` (`jflex/src/main/java/jflex/skeleton/Skeleton.java:43`, `:135`). Adding or removing a
marker means updating every skeleton plus the emitters' `emitNext()` sequence.

| File | Role |
| --- | --- |
| `jflex/src/main/resources/jflex/idea-flex.skeleton` | **the default** (`DEFAULT_LOC`), IntelliJ incremental-lexer API |
| `jflex/src/main/jflex/skeleton.nested` | source-tree file used for the bootstrap; adds `%include`/nested-stream support (`Deque<ZzFlexStreamInfo>`, `zzPushStream`/`zzPopStream`) |
| `jflex/src/main/jflex/kotlin_skeleton.nested` | the Kotlin skeleton; KMP-oriented (`kotlinx.io.Source`, `CharSequence.codePoint`/`codePointBefore` extensions) |
| `jflex/src/main/resources/jflex/skeleton.default` | upstream's default; currently unused |
| `jflex/src/main/resources/jflex/skeleton_kotlin.default` | **broken and unreferenced** — 41 sections (two skeletons concatenated), so `readSkel` would reject it |

`Skeleton.line[]` is a **static** array loaded by a static initializer. Consequences worth knowing:
`makePrivate()` (for `%apiprivate`) mutates it in place and leaks across generations in one JVM, and
`OptionUtils.setDefaultOptions()` calls `Skeleton.readDefault()` — so resetting options discards a
previously set `--skel`, including via `new LexGenerator(file)` when `Options.encoding == null`.

Files under `src/main/resources/` are packaged into the jar and found by classloader lookup; the
`.nested` files are plain source-tree files referenced by path (`jflex/pom.xml`'s `<skeleton>`, and
`jflex/src/main/java/jflex/core/BUILD.bazel`). Neither is copied or filtered by a build step.

### Kotlin output mode

`--output-mode java|kotlin` (lowercase; `OptionUtils.setOutputMode` logs an error but does **not**
throw on an unknown value, silently keeping the previous mode) is the only switch between `Emitter`
and `KotlinEmitter` and between the `.java`/`.kt` extension. The Maven plugin exposes it as
`<outputMode>`; `JFlexTask` does not, so **Kotlin output is unreachable from Ant**. There is no
`%`-directive for it, so it cannot be selected per-spec.

Nothing in the build or tests pairs Kotlin mode with the Kotlin skeleton. To get real Kotlin you must
pass both:

```shell
java -jar jflex/target/jflex-1.10.17.jar --output-mode kotlin \
  --skel jflex/src/main/jflex/kotlin_skeleton.nested -d /tmp/out spec.flex
```

The Kotlin toolchain is wired up in the poms (`kotlin-maven-plugin` runs before `javac`, whose default
executions are bound to `phase none` and re-declared), but there are currently **zero `.kt` sources**
in the repo — all emitter code is Java that *emits* Kotlin.

### Bootstrapping

JFlex generates its own scanner and parser at build time using the **previous release**:

- `de.jflex:jflex-maven-plugin:1.9.0` (explicitly commented "bootstrap with previous version")
  compiles `jflex/src/main/jflex/LexScan.flex` with `<skeleton>src/main/jflex/skeleton.nested</skeleton>`
  into `target/generated-sources/jflex/jflex/core/LexScan.java`.
- `de.jflex:cup-maven-plugin:1.2` compiles `jflex/src/main/cup/LexParse.cup` into
  `target/generated-sources/cup/jflex/core/{LexParse,sym}.java`.

If you change `LexScan.flex`, keep in mind:

- It is processed by **1.9.0**, not by the tree you are building. Syntax introduced after 1.9.0 cannot
  be used until it ships in a release and the bootstrap version is bumped.
- It is built against `skeleton.nested`, not the default `idea-flex.skeleton`, because it needs
  `%include`/nested streams.
- New lexer actions resolve against `AbstractLexScan`'s fields (`%extends AbstractLexScan`); new
  fields must be added there in the same change (several carry
  `@SuppressWarnings("unused") // Used in generated LexScan`).
- New token kinds must be declared as `terminal`s in `LexParse.cup` — scanner and grammar are coupled.
- `jflex/src/test/resources/jflex/LexScan-test.flex` is a maintained near-copy that drifts unless
  updated in lockstep.
- Stale `target/generated-sources/` can mask changes; `./mvnw clean` or `scripts/clean.sh` when in doubt.

### Core packages (`jflex/src/main/java/jflex/`)

Load-bearing: `core/` (the front-end model — `AbstractLexScan` holding every `%`-directive as a field,
`RegExp`/`RegExp1`/`RegExp2` AST, `RegExps`, `Macros`, `Action`, `EOFActions`, `SemCheck`, `NFA`,
`OptionUtils`), `core/unicode/` (`CharClasses` partitioning, `IntCharSet` interval algebra,
`UnicodeProperties`), `core/unicode/data/` (22 generated `Unicode_X_Y.java` tables, regenerated from
Bazel — see that directory's `README.md`), `dfa/`, `generator/`, `skeleton/`, `option/`, `state/`,
`l10n/` (`ErrorMessages` keying into `resources/jflex/Messages.properties`), `logging/` (`Out`, which
all console output funnels through).

Peripheral: `base/`, `chars/`, `io/`, `performance/`, `exceptions/`, `scanner/`, `anttask/`, `gui/`.

Note `OptionUtils` lives in `core/`, not `option/`, because it depends on `Skeleton`.

## Known dead or broken code

Verified, so you don't spend time on it:

- `jflex/src/main/java/jflex/core/KotlinAbstractLexScan.java` (481 lines) — a fork of
  `AbstractLexScan` differing only in `lexPushStream(Path)` vs `(File)` and `kotlinx.io` imports.
  Referenced nowhere; presumably staged for a future self-hosted Kotlin `LexScan`.
- `jflex/src/main/java/jflex/dfa/StatePairList.java` — unreferenced.
- `jflex/src/main/java/jflex/dfa/DeprecatedDfa.java` — used only by `DfaTest`.
- `IEmitter.normalize` / `IEmitter.sourceFileString` — shadowed by identical copies in both `Emitter`
  and `KotlinEmitter`; callers use `Emitter.normalize`. `Emitter` also re-declares `outputFileName`,
  shadowing the base field.
- `Emitters`' three `switch (Options.output_mode)` blocks have no `default` and fall through to
  `return null`, so a third `OutputMode` would yield an NPE rather than a compile error.
- `--uniprops <ver>` is broken: it reflectively looks up `jflex.unicode.data.Unicode_X_Y`, but the
  data classes live in `jflex.core.unicode.data`, so every version reports
  `Unsupported Unicode version` — including versions its own error message lists as supported.
- `Main.printUsage()` does not document `--output-mode`.
- `scripts/clean.sh` still purges `~/.m2/repository/de/jflex`, not the fork's coordinates.
- Root `pluginManagement` pins `org.jetbrains.intellij.deps.jflex:cup-maven-plugin:1.2`, but that
  module builds as `1.3` and `jflex/pom.xml` consumes upstream `de.jflex:cup-maven-plugin:1.2` — the
  locally built cup plugin is not what the `jflex` module uses.
