/*
 * Copyright (C) 2026
 * SPDX-License-Identifier: BSD-3-Clause
 */
package jflex.generator;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.TruthJUnit.assume;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import jflex.core.OptionUtils;
import jflex.testing.lexer.LexerDriver;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Compiles and runs the scanner generated from {@code idea-flex.skeleton}.
 *
 * <p>The golden in {@link IdeaSkeletonEmitterTest} only freezes text, so on its own it cannot tell
 * a deliberate change from a break -- refreshing it is a copy of whatever was emitted. This test is
 * the gate that says the output is a working lexer: it javac-compiles the scanner and lexes a
 * two-line input, which exercises the {@code %bol} block that assigns {@code zzAtBOL}.
 */
public class IdeaSkeletonCompileTest {

  private static final Path SPEC =
      KotlinEmitterTest.moduleFile("src/test/resources/jflex/idea-lexer.flex");
  private static final Path OUT_DIR = KotlinEmitterTest.outputDir("IdeaSkeletonCompileTest");

  @Before
  public void setUp() throws Exception {
    OptionUtils.setDefaultOptions();
    Files.createDirectories(OUT_DIR);
    OptionUtils.setDir(OUT_DIR.toFile());
  }

  @After
  public void tearDown() {
    OptionUtils.setDefaultOptions();
  }

  @Test
  public void generatedScannerCompilesAndLexes() throws Exception {
    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    // Absent on a JRE. Skip rather than fail: the golden test still covers the emitted text.
    assume().that(javac).isNotNull();

    Path scanner = Paths.get(new LexGenerator(SPEC.toFile()).generate());
    File classes = OUT_DIR.resolve("classes").toFile();
    assertThat(classes.mkdirs() || classes.isDirectory()).isTrue();

    int status =
        javac.run(
            null,
            null,
            null,
            "-classpath",
            System.getProperty("java.class.path"),
            "-d",
            classes.getPath(),
            scanner.toString());
    assertThat(status).isEqualTo(0);

    // "start" at offset 0 is at the beginning of a line, as is "start" after the newline; the plain
    // rule must never win. A broken zzAtBOL shows up here as "plain" for the second one.
    assertThat(LexerDriver.tokenize(classes, "IdeaLexer", "start\nstart"))
        .isEqualTo("bol[0,5] other[5,6] bol[6,11]");
  }
}
