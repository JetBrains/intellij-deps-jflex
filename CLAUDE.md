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

Output jar: `jflex/target/jflex-<version>.jar` (currently `1.10.18`, from the root pom `<version>`).
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
- Note `scripts/test-java-format.sh` globs with `find`, so it also picks up generated sources under
  any `target/` directory left in the tree. Run `./mvnw clean` first, or check individual files, or
  it fails on `cup-maven-plugin/sample-project/target/generated-sources/` before reaching `jflex`.

### Golden-file tests

Four golden tests differ only in the skeleton and the output mode:

| Test                            | Skeleton                                             | Mode   | Golden                                    |
|---------------------------------|------------------------------------------------------|--------|-------------------------------------------|
| `KotlinEmitterTest`             | the default, i.e. the **Java** `idea-flex.skeleton`  | kotlin | `eof-kotlin-issue15.kt.golden`            |
| `KotlinSkeletonEmitterTest`     | `src/main/jflex/kotlin_skeleton.nested`              | kotlin | `eof-kotlin-issue15-kotlinskel.kt.golden` |
| `IdeaSkeletonEmitterTest`       | the default `idea-flex.skeleton`                     | java   | `idea-lexer.java.golden`                  |
| `IdeaKotlinSkeletonEmitterTest` | `src/main/resources/jflex/idea-flex-kotlin.skeleton` | kotlin | `idea-lexer.kt.golden`                    |

All strip the two leading comment lines (JFlex version + spec path) so the goldens are neither
version- nor machine-specific. To refresh after an intentional emitter change:

```shell
./mvnw test -pl jflex -Dtest=KotlinEmitterTest -Dfmt.skip=true
cp jflex/target/test-output/KotlinEmitterTest/Issue15EofLexer.kt \
   jflex/src/test/resources/jflex/eof-kotlin-issue15.kt.golden
```

`KotlinEmitterTest`'s golden is **not compilable Kotlin**: with the Java skeleton the file
interleaves Java skeleton bodies (`public static final int YYEOF = -1;`, a `default:` label) with
Kotlin-emitted tables (`intArrayOf(...)`, `@JvmStatic`). Those Java-isms come from the skeleton, not
from `KotlinEmitter` — do not "fix" them.

`KotlinSkeletonEmitterTest`'s golden **is** real Kotlin, but it still does not compile: Kotlin 2.0.21
reports 18 errors, all pre-existing divergences unrelated to any one issue (`zzScanError` emitted as
a local function, `break@zzForAction` where the label does not denote a loop, `readCodePointValue`
and `charCount` unresolved, a mis-spliced `yypushback`/`zzScanError` pair). Verified 2026-09-16
against 1.10.18. To reproduce:

```shell
M2=$HOME/.m2/repository; K=2.0.21
java -jar jflex/target/jflex-1.10.18.jar --output-mode kotlin \
  --skel jflex/src/main/jflex/kotlin_skeleton.nested -d /tmp/out \
  jflex/src/test/resources/jflex/eof-kotlin-issue15.flex
java -cp "$M2/org/jetbrains/kotlin/kotlin-compiler-embeddable/$K/kotlin-compiler-embeddable-$K.jar:$M2/org/jetbrains/kotlin/kotlin-stdlib/$K/kotlin-stdlib-$K.jar:$M2/org/jetbrains/kotlin/kotlin-reflect/$K/kotlin-reflect-$K.jar:$M2/org/jetbrains/kotlin/kotlin-script-runtime/$K/kotlin-script-runtime-$K.jar:$M2/org/jetbrains/kotlin/kotlin-daemon-embeddable/$K/kotlin-daemon-embeddable-$K.jar:$M2/org/jetbrains/intellij/deps/trove4j/1.0.20221201/trove4j-1.0.20221201.jar:$M2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.1/kotlinx-coroutines-core-jvm-1.8.1.jar" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect \
  -classpath "$M2/org/jetbrains/kotlin/kotlin-stdlib/$K/kotlin-stdlib-$K.jar:$M2/org/jetbrains/kotlinx/kotlinx-io-core-jvm/0.6.0/kotlinx-io-core-jvm-0.6.0.jar:$M2/org/jetbrains/kotlinx/kotlinx-io-bytestring-jvm/0.6.0/kotlinx-io-bytestring-jvm-0.6.0.jar" \
  -d /tmp/out-classes /tmp/out/Issue15EofLexer.kt
```

Neither of those two goldens proves the output compiles; they are text freezes that make emitter
changes visible, and a compile gate for `kotlin_skeleton.nested` is still blocked on those 18 errors.

**The IntelliJ skeletons are different: their output does compile, and it is gated.** The two `Idea*`
goldens above are backed by two compile-and-run tests:

| Test                            | Compiles with               | Notes                                          |
|---------------------------------|-----------------------------|------------------------------------------------|
| `IdeaSkeletonCompileTest`       | `javax.tools.JavaCompiler`  | skips via `assume()` if run on a JRE           |
| `IdeaKotlinSkeletonCompileTest` | `K2JVMCompiler`, in-process | Maven-only; needs `kotlin-compiler-embeddable` |

Both then load the scanner and lex `"start\nstart"`, asserting `bol[0,5] other[5,6] bol[6,11]`.
Lexing `^"start"` at offset 0 *and* after a newline is the point: it is what distinguishes a working
`zzAtBOL` from a broken one, which no golden can do on its own (refreshing a golden just copies
whatever was emitted). This caught a real bug — see the `charAt` note under the emitter hierarchy.

`skeleton_kotlin.default` is gated the same way, by `KotlinDefaultSkeletonCompileTest` (also
Maven-only, also `K2JVMCompiler`). It uses its own fixture, `kotlin-default-skeleton.flex`, because
that skeleton is standalone — no `%implements`, so no `FlexLexer` and no `LexerDriver`; the test
drives the scanner by reflection over `constructor(kotlinx.io.Source)` and `yylex()`. Beyond `^` at
offset 0, it asserts fixed lookahead (`offsetByCodePoints`), lexical states, and — the cases a short
input never reaches — buffer refill and a supplementary code point landing on the buffer boundary.
Both bugs found while writing that skeleton would have passed a golden unnoticed:

- `zzAtBOL` was never initialised, so `^` never matched at offset 0. `KotlinEmitter` declared
  `zzAtBOL = false` where `Emitter` declares it `true` (`Emitter.java:1352`); it now declares `true`
  too. The `Idea*` skeletons had hidden this for as long as Kotlin mode has existed, because their
  `reset()` assigns `zzAtBOL = true` — a skeleton with no `reset()` does not.
- a surrogate pair was split when the high surrogate took the buffer's last free position, so the
  scanner matched each half as a character of its own. The skeleton now holds the whole code point
  in `zzPendingCodePoint` rather than writing half a pair.

`kotlin-compiler-embeddable` is a test-scope dependency of the `jflex` module for this, and the test
passes surefire's own classpath (`java.class.path`) through to `-classpath`, so `kotlin-stdlib` and
the `FlexLexer` the scanner implements both resolve without a hand-assembled classpath. Assembling
one by hand is a trap: it needs `kotlinx-coroutines-core-jvm` and `annotations` on the *compiler's*
classpath or it dies with `NoClassDefFoundError` far from the real problem.

The shared fixture is `src/test/resources/jflex/idea-lexer.flex`, shaped like a real IntelliJ spec
because that is what makes it compilable: the skeletons declare `reset`/`getTokenStart`/
`getTokenEnd`/`yystate`/`yybegin` as **overrides**, so the scanner needs a supertype that declares
them. `jflex/src/test/java/jflex/testing/lexer/FlexLexer.java` is a local stand-in for IntelliJ's
`com.intellij.lexer.FlexLexer` (IntelliJ is not a dependency here). Two details it has to get right:
`advance()` is declared `throws IOException` because `Emitter` emits that (`KotlinEmitter` does not —
Kotlin has no checked exceptions), and the generated constructors differ between the skeletons — the
Java one emits `Lexer(java.io.Reader)` and IntelliJ passes it a null reader, the Kotlin one emits no
constructor at all. `LexerDriver` handles both.

### Regression suite

```shell
# All regression cases — must run from its own directory, relative paths depend on it
cd testsuite/testcases && ../../mvnw test

# One case (a directory name under src/test/cases/)
cd testsuite/testcases && ../../mvnw test -Dtestcases=dot
```

**This suite does not currently run in this fork.** Two independent breakages, both confirmed:

1. `JFlexTestsuiteMojo.jflexUberJarFilename` defaults to `jflex/target/jflex-full-${version}.jar`,
   but the shade plugin was changed to *replace* the main artifact rather than produce a second one (IDEA-332177), so
   the build yields `jflex-<version>.jar` + `original-jflex-<version>.jar` and
   never a `-full` jar. The parameter has no `property=`, so `-DjflexUberJarFilename=...` does not
   override it — it has to be set in the pom.
2. Even given the right path, `JFlexTestsuiteMojo.java:53` hardcodes the **upstream** groupId
   (`PomUtils.getPomVersion("de.jflex", "jflex", jflexUberJar)`), while the fork's jar carries
   `META-INF/maven/org.jetbrains.intellij.deps.jflex/jflex/pom.properties`. It fails with
   `Missing POM property`.

The stale `jflex-full` name also appears in `scripts/mk-release.sh:11`, `jflex/pom.xml`'s
`copy-jar-to-lib` execution (which silently copies nothing), and `jflex/examples/common/include.xml`
(so the ant example builds are affected too).

A case directory holds `<name>.test` (directives parsed by
`testsuite/jflex-testsuite-maven-plugin/src/main/jflex/TestLoader.flex`
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

BUILD files are hand-maintained and drift, and the drift is silent. A new Maven test needs a Bazel
target too, or it simply won't run there. Build files must be named `BUILD.bazel` (enforced by
`scripts/test-bzl-format.sh`).

`bazel test //jflex/src/...` runs 16 tests, including `KotlinEmitterTest` and the four Bazel-side
`Idea*`/`CupEofValueTest` tests. Note `//jflex/...` (the whole tree) still does **not** build, so
scope to `//jflex/src/...` for a green run — and CI's `bazel test //jflex/...` step is red for the
same reason. The examples are the problem, and they matter more than "example rot" suggests: Bazel
generates them with `//jflex:jflex_bin`, i.e. the tree you are building, so they are the only place
the fork's own generator has to produce compilable Java. **Maven does not do this** — each example's
pom pins upstream `jflex-maven-plugin:1.9.0`, so `scripts/test-examples.sh` never exercises local
changes and passes regardless.

`//jflex/examples/cup-java-minijava` fails for three independent reasons, worth separating because
only two are inherent:

1. `yyclose()` is undefined — `%cup` sets `eofclose = true`, but `idea-flex.skeleton` has no
   `yyclose()` (there is no reader to close; input arrives whole). Inherent to the fork's default.
2. `yytext()` returns `CharSequence`, and the spec passes it to a `String` constructor. Inherent,
   and the same cause as `//jflex/examples/simple/src/test:YylexTest`.
3. The `%cup` EOF value was missing `new` — a genuine bug, now fixed; see below.

(1) and (2) go away only by pointing the examples at upstream's `skeleton.default`, which has not
been done: they are upstream artifacts that assume the upstream skeleton.

Getting the tests running at all required
fixing three instances of that drift, all worth knowing about because the same traps recur:

- **Error Prone is on for `java_library`, and Maven does not run it.** It rejected `Emitter` and
  `KotlinEmitter` for `WildcardImport`, `MissingOverride`, and (in `Emitter`) a `HidingField` on
  `outputFileName` shadowing `IEmitter`'s. That failed `//jflex/src/main/java/jflex/generator`, so *no* test in that
  package could build — `EmitterTest` and `PackEmitterTest` included. Code that
  compiles under Maven can still break Bazel.
- **`glob` does not descend into subpackages.** `//jflex:test_data` globs `src/test/resources/**`,
  but `jflex/src/test/resources/BUILD.bazel` makes that its own package, so the filegroup resolves
  to **nothing** — silently, with no error. Depend on `//jflex/src/test/resources:resources`
  instead. `//jflex:test_data` is still there and still empty.
- **Explicit `srcs` lists rot.** `jflex/src/main/java/jflex/option/BUILD.bazel` listed only
  `Options.java` and omitted the fork's `OutputMode.java`, which broke every Bazel build of the
  tree. It is a `glob` now.

A test that reads files needs two accommodations Maven does not: paths resolve from the runfiles
root (where module files sit under `jflex/`) rather than the module directory, and the runfiles tree
is read-only, so output goes to `$TEST_TMPDIR`. `KotlinEmitterTest.moduleFile` and
`KotlinEmitterTest.outputDir` handle both and are reused by every other emitter test. Two
consequences of Bazel's explicit `srcs`: those helpers live in a test class, so any target using them
must list `KotlinEmitterTest.java` in `srcs` alongside its own source (only the target's own class
runs — `java_test` infers `test_class` from the target name), and `jflex/testing/lexer/` needs its own
`BUILD.bazel` because the sibling `//jflex/src/test/java/jflex/testing` globs just `*.java`.

Still red under Bazel, pre-existing and unrelated: `//jflex/examples/simple/src/test:YylexTest`,
because the example's spec expects upstream's `String yytext()` while the fork's default skeleton
returns `CharSequence`. `KotlinSkeletonEmitterTest` is deliberately Maven-only — it reads
`src/main/jflex/kotlin_skeleton.nested`, which no target exposes as data (unlike
`src/main/resources/**`, packaged by `//jflex:resources`); wiring it up needs a filegroup for
`src/main/jflex/` first.

The `Idea*` tests do run under Bazel, and the reason is worth copying: their skeletons live under
`src/main/resources/`, so they load through the classloader via `Skeleton.readSkel(BufferedReader)`
(see `KotlinEmitterTest.readSkeletonResource`) and need no `data` dependency at all. Prefer that over
`readSkelFile(File)` for any bundled skeleton. The exception is `IdeaKotlinSkeletonCompileTest`,
Maven-only because it needs `kotlin-compiler-embeddable` and no Bazel target provides a Kotlin
toolchain.

### Generation pipeline

`LexGenerator.generate()` (`jflex/src/main/java/jflex/generator/LexGenerator.java:54`) is the whole
orchestration:

`LexScan` → `LexParse` → **NFA** → `DfaFactory.createFromNfa` → `dfa.minimize()` → `Emitters.createFileEmitter` →
`emitter.emit()`

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
  `emitEOFVal`, the `%bol` fall-through below, `Action.Kind.GENERAL_LOOK` handling, stray `;`,
  `offsetByCodePoints`, and `zzBufferL.charAt(...)` in the `%bol` block, which does not resolve in
  Kotlin — `CharSequence` is indexed with `[...]` — so until `IdeaKotlinSkeletonCompileTest` was
  added, every Kotlin spec using `^` emitted a scanner that would not compile).
- **Java `switch` fall-through is the recurring trap in this fork.** `Emitter` leans on it in three
  places; Kotlin `when` has no fall-through, so each one needs a comma-separated branch instead. Two
  of the three were mistranslated. `#15` was one (a synthetic `case <n>: break;` became a bare
  `<n> -> break`). The `%bol` block was the other: six newline characters that share one `switch`
  arm in `Emitter` became six separate `-> {}` arms, five of them empty, so `zzAtBOL` was only ever
  set for `' '` and every `^` anchor was broken in Kotlin mode. The line-counting block was
  translated correctly and shows the right idiom. When touching either emitter, check whether a
  `// fall through` comment is doing real work — in Kotlin the comment survives the copy and the
  behaviour does not.
- Any `KotlinEmitter` change means refreshing both goldens above.
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

**The `L1..L20` suffixes on the Kotlin skeletons' markers are comments, and they lie.** Because
sections are positional, only the physical order matters, and the three skeletons disagree on it:
`idea-flex.skeleton` has "throws clause" 7th, `idea-flex-kotlin.skeleton` has it **9th** (after
"zzDoEOF"), and `kotlin_skeleton.nested` has it **4th**. `idea-flex-kotlin.skeleton`'s order is the
correct one for `KotlinEmitter` — verified by generating and compiling — so `KotlinEmitter`'s
`emitNext()` sequence genuinely differs from `Emitter`'s, and the marker text is simply stale. Do not
"fix" a Kotlin skeleton by reordering its sections to match the numbering or the Java skeleton; check
the emitter's `skel.emitNext()` call sites (they carry `// <n>` comments) and the generated output
instead.

| File                                                       | Role                                                                                                                                     |
|------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| `jflex/src/main/resources/jflex/idea-flex.skeleton`        | **the default** (`DEFAULT_LOC`), IntelliJ incremental-lexer API                                                                          |
| `jflex/src/main/resources/jflex/idea-flex-kotlin.skeleton` | the IntelliJ **Kotlin** skeleton; the `idea-flex.skeleton` API ported to Kotlin over a `CharSequence`                                    |
| `jflex/src/main/jflex/skeleton.nested`                     | source-tree file used for the bootstrap; adds `%include`/nested-stream support (`Deque<ZzFlexStreamInfo>`, `zzPushStream`/`zzPopStream`) |
| `jflex/src/main/jflex/kotlin_skeleton.nested`              | the Kotlin skeleton; KMP-oriented (`kotlinx.io.Source`, `CharSequence.codePoint`/`codePointBefore` extensions)                           |
| `jflex/src/main/resources/jflex/skeleton.default`          | upstream's default; currently unused                                                                                                     |
| `jflex/src/main/resources/jflex/skeleton_kotlin.default`   | the **default Kotlin** skeleton; standalone streaming scanner over `kotlinx.io.Source`, gated by `KotlinDefaultSkeletonCompileTest`      |

`Skeleton.line[]` is a **static** array loaded by a static initializer. Consequences worth knowing:
`makePrivate()` (for `%apiprivate`) mutates it in place and leaks across generations in one JVM, and
`OptionUtils.setDefaultOptions()` calls `Skeleton.readDefault()` — so resetting options discards a
previously set `--skel`, including via `new LexGenerator(file)` when `Options.encoding == null`.

The two `idea-flex*` skeletons are **also maintained in the IntelliJ monorepo**, at
`community/tools/lexer/`, where `tools/lexer/build.xml`'s `flex` and `kflex` Ant macros pass them to
a downloaded JFlex jar. Both copies must stay `diff`-clean; `idea-flex.skeleton` currently is. That
is the whole reason `idea-flex-kotlin.skeleton` was brought here — the jar now ships the skeleton it
was tested against, rather than the monorepo hand-updating its copy per release.

Files under `src/main/resources/` are packaged into the jar and found by classloader lookup; the
`.nested` files are plain source-tree files referenced by path (`jflex/pom.xml`'s `<skeleton>`, and
`jflex/src/main/java/jflex/core/BUILD.bazel`). Neither is copied or filtered by a build step.

### Kotlin output mode

`--output-mode java|kotlin` (lowercase; `OptionUtils.setOutputMode` logs an error but does **not**
throw on an unknown value, silently keeping the previous mode) is the only switch between `Emitter`
and `KotlinEmitter` and between the `.java`/`.kt` extension. The Maven plugin exposes it as
`<outputMode>`; `JFlexTask` does not, so **Kotlin output is unreachable from Ant**. There is no
`%`-directive for it, so it cannot be selected per-spec.

Kotlin mode does **not** imply a Kotlin skeleton — the default stays `idea-flex.skeleton`, which is
Java — so to get real Kotlin you must pass both. For an IntelliJ lexer (the usual case), that is:

```shell
java -jar jflex/target/jflex-1.10.18.jar --output-mode kotlin \
  --skel jflex/src/main/resources/jflex/idea-flex-kotlin.skeleton -d /tmp/out spec.flex
```

`idea-flex-kotlin.skeleton` is a jar resource, so from a released jar there is no path to pass to
`--skel`; extract it first (`unzip -o -j jflex/target/jflex-1.10.18.jar
jflex/idea-flex-kotlin.skeleton -d /tmp`), or do what the tests do and load it through the
classloader with
`Skeleton.readSkel(BufferedReader)`. Making `--output-mode kotlin` select it automatically was
considered and deliberately not done — it would change generator defaults.

For a **standalone** Kotlin scanner (not an IntelliJ lexer), pair it with `skeleton_kotlin.default`,
which is also packaged in the jar and whose output is compile-gated:

```shell
unzip -o -j jflex/target/jflex-1.10.18.jar jflex/skeleton_kotlin.default -d /tmp
java -jar jflex/target/jflex-1.10.18.jar --output-mode kotlin \
  --skel /tmp/skeleton_kotlin.default -d /tmp/out spec.flex
```

That skeleton is a streaming scanner: it declares no overrides (so the spec needs no `%implements`),
supplies its own `constructor(kotlinx.io.Source)` because `KotlinEmitter` emits none, and decodes
UTF-8 itself using only `Source` **interface members** (`readByte`, `exhausted`). The member-only
restriction is not stylistic — a skeleton cannot emit imports, because its first section begins
after `emitClassName()` has already printed `class Foo {`, and Kotlin cannot reach a top-level
extension such as `kotlinx.io.readCodePointValue` without one (nor the `Utf8Kt` facade, which is
invisible to Kotlin source). The generated scanner needs `kotlinx-io-core` at **runtime**; the
generator itself does not.

The `kotlin_skeleton.nested` pairing is the third option, and its output does not compile:

```shell
java -jar jflex/target/jflex-1.10.18.jar --output-mode kotlin \
  --skel jflex/src/main/jflex/kotlin_skeleton.nested -d /tmp/out spec.flex
```

Two of its 18 errors have identified root causes, both worth knowing before touching it: its
"throws clause" section sits at physical position **4** instead of 9 (see the section-order note
above), which mis-splices the `zzScanError` body into the companion object; and it stores input with
`zzReader.readCodePointValue().toChar()`, which truncates to the low 16 bits — U+1F600 silently
becomes U+F600 — and writes one code unit where a supplementary code point needs two.

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
- **Strings `LexScan` builds are emitted verbatim by both emitters, so anything language-specific in
  them must branch on `Options.output_mode`.** `%cup`'s default `eofVal` is the cautionary tale: it
  constructs a `java_cup.runtime.Symbol`, and commit `d97b1798` dropped the `new` keyword to make
  Kotlin output valid, which silently made *every* Java `%cup` spec emit `return
  java_cup.runtime.Symbol(sym.EOF);` — a call to a method that does not exist. It is conditional on
  the output mode now, and `CupEofValueTest` asserts both directions, since fixing one mode here
  breaks the other. `Options` is already in scope in `LexScan.flex` for exactly this kind of check.
- `jflex/src/test/resources/jflex/LexScan-test.flex` is a maintained near-copy that drifts unless
  updated in lockstep — but note it is **never generated or compiled**. Its only consumer is
  `JFlexTaskTest`, which reads it to test `%class`/package name sniffing, so its action code is dead
  text (it references `Options` without importing it). It cannot catch drift; it only records it. It
  had kept the correct `new` while `LexScan.flex` lost it, which is what hid the bug above.
- Changes to `LexScan.flex` only take effect after the bootstrap scanner is regenerated, and a stale
  `target/generated-sources/` masks them. `./mvnw clean` when a `LexScan.flex` edit seems inert.
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

## Releasing

The release process is **not** documented in this repo. It lives in the IntelliJ monorepo, at
`docs/IntelliJ-Platform/4_man/Lang/Releasing-JFlex-Versions.md`. In outline:

- `perl scripts/post-release.pl --release x.y.z` cuts the release: it checks for a clean checkout,
  creates the `intellij/x.y.z` branch, rewrites the JFlex version in every POM, and updates the
  version comments and `@version` tags. Its XSLT deliberately **skips the bootstrap version** in
  `jflex/pom.xml`, so the `jflex-maven-plugin` pin stays put — see Bootstrapping above. Publishing
  then happens on internal CI, followed by a GitHub tag and release.
- `scripts/prepare-release.pl` is *not* part of this path; it only converts `-SNAPSHOT` versions,
  which this fork does not use. (The monorepo doc calls it `pre-release.pl`, which is the wrong name.)
- To test an unpublished jar, repoint the monorepo's `community/tools/lexer/build.xml` — comment out
  the `<get>` that downloads the published jar and aim the `<java jar=...>` at
  `jflex/target/jflex-<version>.jar` — then regenerate lexers with the `flex`/`kflex` Ant targets and
  run the downstream IntelliJ tests. Snapshot versions cannot be published, so this is the only way
  to try a build without burning a version number.
- **The testsuite does not run during a release build**, nor from any Maven goal — consistent with
  the Regression suite section above. Downstream IntelliJ testing is the real gate.

## Known dead or broken code

Verified, so you don't spend time on it:

- `jflex/src/main/java/jflex/core/KotlinAbstractLexScan.java` (481 lines) — a fork of
  `AbstractLexScan` differing only in `lexPushStream(Path)` vs `(File)` and `kotlinx.io` imports.
  Referenced nowhere; presumably staged for a future self-hosted Kotlin `LexScan`. It is excluded
  from `jflex/src/main/java/jflex/core/BUILD.bazel`'s glob: its `kotlinx.io` imports have no Bazel
  dependency, so globbing it in breaks the build.
- `jflex/src/main/java/jflex/dfa/StatePairList.java` — unreferenced.
- `jflex/src/main/java/jflex/dfa/DeprecatedDfa.java` — used only by `DfaTest`.
- `IEmitter.normalize` / `IEmitter.sourceFileString` — shadowed by identical copies in both `Emitter`
  and `KotlinEmitter`; callers use `Emitter.normalize`. (`Emitter` used to re-declare
  `outputFileName` as well, shadowing the base field; removed, since Error Prone's `HidingField`
  rejected it under Bazel.)
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
