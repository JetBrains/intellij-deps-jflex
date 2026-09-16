/*
 * Copyright (C) 2026
 * SPDX-License-Identifier: BSD-3-Clause
 */
package jflex.generator;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import jflex.core.OptionUtils;
import jflex.option.Options;
import jflex.option.OutputMode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Golden-file test for {@link KotlinEmitter}: the scanner emitted for a spec with a state-scoped
 * {@code <<EOF>>} action is compared in full against a committed expectation.
 *
 * <p>This is the regression guard for <a
 * href="https://github.com/JetBrains/intellij-deps-jflex/issues/15">issue #15</a>. {@link Emitter}
 * closes each EOF branch with a synthetic {@code case <n>: break;} to stop {@code switch}
 * fall-through; copied verbatim into Kotlin that became a bare {@code <n> -> break}, which binds to
 * the enclosing scan loop rather than to the {@code when}. The branch is unreachable -- lexical
 * state constants are {@code 2 * num} while the synthetic label starts at {@code dfa.numStates() +
 * 1} -- but it still makes the end of the scan function reachable, so under a skeleton whose scan
 * loop has no trailing {@code return} the output fails to compile with "missing return statement".
 * {@code idea-flex.skeleton}, the default in this fork, is such a skeleton.
 *
 * <p>To refresh the expectation after an intentional emitter change, run this test and copy the
 * scanner it writes under {@code target/} over {@link #GOLDEN}.
 *
 * <p>Note the golden is deliberately not compilable Kotlin: this test runs with the default (Java)
 * skeleton, so Kotlin-emitted tables interleave with Java skeleton bodies. It guards the emitted
 * fragments only. {@link KotlinSkeletonEmitterTest} covers the Kotlin skeleton.
 */
public class KotlinEmitterTest {

  private static final Path SPEC = moduleFile("src/test/resources/jflex/eof-kotlin-issue15.flex");
  private static final Path GOLDEN =
      moduleFile("src/test/resources/jflex/eof-kotlin-issue15.kt.golden");
  private static final Path OUT_DIR = outputDir("KotlinEmitterTest");

  /**
   * Resolves a path given relative to the {@code jflex} module. Maven runs surefire with the module
   * as the working directory, Bazel runs the test from the runfiles root, where the same file sits
   * under {@code jflex/}.
   */
  static Path moduleFile(String moduleRelative) {
    Path direct = Paths.get(moduleRelative);
    return Files.exists(direct) ? direct : Paths.get("jflex").resolve(moduleRelative);
  }

  /**
   * Picks a writable output directory. The Bazel runfiles tree is read-only, so use the sandbox
   * directory Bazel provides; under Maven fall back to {@code target/}.
   */
  static Path outputDir(String name) {
    String testTmpDir = System.getenv("TEST_TMPDIR");
    return testTmpDir != null
        ? Paths.get(testTmpDir).resolve(name)
        : Paths.get("target/test-output").resolve(name);
  }

  @Before
  public void setUp() throws IOException {
    OptionUtils.setDefaultOptions();
    Options.output_mode = OutputMode.KOTLIN;
    Files.createDirectories(OUT_DIR);
    OptionUtils.setDir(OUT_DIR.toFile());
  }

  @After
  public void tearDown() {
    OptionUtils.setDefaultOptions();
  }

  @Test
  public void emitsExpectedScannerForStateScopedEofAction() throws Exception {
    String scanner = read(Paths.get(new LexGenerator(SPEC.toFile()).generate()));

    // Asserted separately from the golden: refreshing the golden is a copy of whatever the
    // emitter produced, so on its own it cannot keep the issue #15 defect from coming back.
    assertThat(scanner).doesNotContainMatch("[0-9]+ -> break");
    assertThat(stripHeader(scanner)).isEqualTo(stripHeader(read(GOLDEN)));
  }

  private static String read(Path path) throws IOException {
    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
  }

  /**
   * Drops the two leading comment lines naming the JFlex version and the absolute path of the spec,
   * so the expectation is neither version- nor machine-specific.
   */
  private static String stripHeader(String scanner) {
    int start = 0;
    for (int i = 0; i < 2; i++) {
      int eol = scanner.indexOf('\n', start);
      assertThat(eol).isGreaterThan(-1);
      start = eol + 1;
    }
    return scanner.substring(start);
  }
}
