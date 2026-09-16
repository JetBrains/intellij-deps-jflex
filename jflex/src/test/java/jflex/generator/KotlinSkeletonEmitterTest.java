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
import jflex.skeleton.Skeleton;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Golden-file test for {@link KotlinEmitter} paired with the <em>Kotlin</em> skeleton.
 *
 * <p>{@link KotlinEmitterTest} runs with the default skeleton, which in this fork is the Java
 * {@code idea-flex.skeleton}, so its golden interleaves Kotlin-emitted tables with Java skeleton
 * bodies. Kotlin mode is only reachable as real Kotlin when both {@code --output-mode kotlin} and
 * {@code --skel src/main/jflex/kotlin_skeleton.nested} are passed, and nothing else in the build or
 * the test suite pairs the two. This test covers that pairing.
 *
 * <p>It is a text freeze, not proof that the output compiles. As of this writing the emitted
 * scanner does <strong>not</strong> compile: Kotlin 2.0.21 reports 18 errors, none of them in the
 * EOF {@code when (zzLexicalState)} block that issue #15 concerned. They are independent copy-paste
 * divergences between {@link Emitter} and {@link KotlinEmitter} and mis-splices in the skeleton --
 * for instance {@code zzScanError} is emitted as a local function, and the {@code zzForAction}
 * label does not denote a loop. Freezing the output here makes any change to those fragments
 * visible; see CLAUDE.md for the command that reproduces the compile.
 *
 * <p>To refresh the expectation after an intentional emitter change, run this test and copy the
 * scanner it writes under {@code target/} over {@link #GOLDEN}.
 */
public class KotlinSkeletonEmitterTest {

  private static final Path SPEC =
      KotlinEmitterTest.moduleFile("src/test/resources/jflex/eof-kotlin-issue15.flex");
  private static final Path GOLDEN =
      KotlinEmitterTest.moduleFile(
          "src/test/resources/jflex/eof-kotlin-issue15-kotlinskel.kt.golden");
  private static final Path BOL_SPEC =
      KotlinEmitterTest.moduleFile("src/test/resources/jflex/bol-kotlin.flex");
  private static final Path SKELETON =
      KotlinEmitterTest.moduleFile("src/main/jflex/kotlin_skeleton.nested");
  private static final Path OUT_DIR = KotlinEmitterTest.outputDir("KotlinSkeletonEmitterTest");

  @Before
  public void setUp() throws IOException {
    // setDefaultOptions() resets the skeleton via Skeleton.readDefault(), so it has to come first.
    // It also sets Options.encoding, which matters: LexGenerator's constructor calls
    // setDefaultOptions() again when encoding is null, and that would discard the skeleton below.
    OptionUtils.setDefaultOptions();
    Options.output_mode = OutputMode.KOTLIN;
    Skeleton.readSkelFile(SKELETON.toFile());
    Files.createDirectories(OUT_DIR);
    OptionUtils.setDir(OUT_DIR.toFile());
  }

  @After
  public void tearDown() {
    // Skeleton.line[] is static; restore it so the skeleton does not leak into other tests.
    OptionUtils.setDefaultOptions();
  }

  @Test
  public void emitsExpectedKotlinScannerForStateScopedEofAction() throws Exception {
    String scanner = read(Paths.get(new LexGenerator(SPEC.toFile()).generate()));

    assertThat(scanner).doesNotContainMatch("[0-9]+ -> break");
    assertThat(stripHeader(scanner)).isEqualTo(stripHeader(read(GOLDEN)));
  }

  /**
   * Every newline character has to share the one {@code when} branch that sets {@code zzAtBOL}.
   * {@link Emitter} gets this from {@code switch} fall-through; Kotlin's {@code when} has none, so
   * the characters must be listed in a single branch. They were emitted as separate empty arms
   * carrying the Java {@code // fall through} comments, which left {@code zzAtBOL} unset for a
   * plain {@code '\n'} and so broke every {@code ^} anchor in Kotlin mode.
   */
  @Test
  public void emitsAllNewlineCharsInOneBolBranch() throws Exception {
    String scanner = read(Paths.get(new LexGenerator(BOL_SPEC.toFile()).generate()));

    assertThat(scanner).doesNotContain("'\\n' -> {}");
    assertThat(scanner)
        .contains("'\\n', '\\u000B', '\\u000C', '\\u0085', '\\u2028', '\\u2029' -> {");
  }

  private static String read(Path path) throws IOException {
    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
  }

  /**
   * Drops the two leading comment lines naming the JFlex version and the path of the spec, so the
   * expectation is neither version- nor machine-specific.
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
