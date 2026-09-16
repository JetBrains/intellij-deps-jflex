/*
 * Copyright (C) 2026
 * SPDX-License-Identifier: BSD-3-Clause
 */
package jflex.generator;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import jflex.core.OptionUtils;
import jflex.option.Options;
import jflex.option.OutputMode;
import jflex.testing.lexer.LexerDriver;
import org.jetbrains.kotlin.cli.common.ExitCode;
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Compiles and runs the scanner generated from {@code idea-flex-kotlin.skeleton} -- the real gate
 * on Kotlin output mode.
 *
 * <p>This is the first compile check on any Kotlin output in this repo. Until now Kotlin coverage
 * was text freezes only, and CLAUDE.md records why: the output of the other Kotlin skeleton, {@code
 * kotlin_skeleton.nested}, does not compile at all. That left {@link KotlinEmitter} free to emit
 * Java-isms indefinitely, and it did -- the {@code %bol} block called Java's {@code charAt} on the
 * buffer, so every spec using {@code ^} produced a scanner that would not compile. A golden cannot
 * catch that; this test does.
 *
 * <p>It compiles in-process via {@link K2JVMCompiler}, passing the surefire test classpath straight
 * through, so {@code kotlin-stdlib} and the {@code FlexLexer} the scanner implements both resolve
 * without a hand-assembled classpath. {@code kotlin-compiler-embeddable} is a test-scope dependency
 * of the {@code jflex} module for this.
 *
 * <p>Maven-only: Bazel builds no Kotlin here (there are zero {@code .kt} sources in the repo), so
 * the golden test carries the Bazel-side coverage.
 */
public class IdeaKotlinSkeletonCompileTest {

  private static final String SKELETON = "jflex/idea-flex-kotlin.skeleton";

  private static final Path SPEC =
      KotlinEmitterTest.moduleFile("src/test/resources/jflex/idea-lexer.flex");
  private static final Path OUT_DIR = KotlinEmitterTest.outputDir("IdeaKotlinSkeletonCompileTest");

  @Before
  public void setUp() throws Exception {
    // setDefaultOptions() first: it resets the skeleton and sets Options.encoding, without which
    // LexGenerator's constructor would call it again and discard the skeleton below.
    OptionUtils.setDefaultOptions();
    Options.output_mode = OutputMode.KOTLIN;
    KotlinEmitterTest.readSkeletonResource(SKELETON);
    Files.createDirectories(OUT_DIR);
    OptionUtils.setDir(OUT_DIR.toFile());
  }

  @After
  public void tearDown() {
    OptionUtils.setDefaultOptions();
  }

  @Test
  public void generatedScannerCompilesAndLexes() throws Exception {
    Path scanner = Paths.get(new LexGenerator(SPEC.toFile()).generate());
    File classes = OUT_DIR.resolve("classes").toFile();
    assertThat(classes.mkdirs() || classes.isDirectory()).isTrue();

    ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
    ExitCode exitCode;
    try (PrintStream err = new PrintStream(diagnostics, true, StandardCharsets.UTF_8.name())) {
      exitCode =
          new K2JVMCompiler()
              .exec(
                  err,
                  "-no-stdlib", // the stdlib comes off the test classpath below
                  "-no-reflect",
                  "-classpath",
                  System.getProperty("java.class.path"),
                  "-d",
                  classes.getPath(),
                  scanner.toString());
    }
    // Report the compiler diagnostics, or a failure here is just "expected OK but was
    // COMPILATION_ERROR" with no indication of what in the emitted Kotlin broke.
    assertWithMessage(
            "kotlinc failed for %s:\n%s",
            scanner, new String(diagnostics.toByteArray(), StandardCharsets.UTF_8))
        .that(exitCode)
        .isEqualTo(ExitCode.OK);

    // "start" at offset 0 is at the beginning of a line, as is "start" after the newline. If the
    // zzAtBOL branch loses its newline characters -- the Kotlin `when` has no fall-through -- the
    // second one lexes as "plain" instead.
    assertThat(LexerDriver.tokenize(classes, "IdeaLexer", "start\nstart"))
        .isEqualTo("bol[0,5] other[5,6] bol[6,11]");
  }
}
