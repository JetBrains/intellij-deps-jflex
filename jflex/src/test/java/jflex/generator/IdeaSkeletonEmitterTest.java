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
import jflex.skeleton.Skeleton;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Golden-file test for {@link Emitter} paired with {@code idea-flex.skeleton}, the IntelliJ Java
 * skeleton and this fork's default ({@code Skeleton.DEFAULT_LOC}).
 *
 * <p>This is the skeleton every IntelliJ Java lexer is generated from -- the monorepo's Ant {@code
 * flex} macro passes it explicitly -- and until now it had no golden coverage here at all: {@link
 * EmitterTest} only exercises {@code Emitter.sourceFileString}. So every {@code Emitter} change
 * shipped unverified against the skeleton that matters most to this fork's consumers.
 *
 * <p>Unlike the Kotlin goldens, this one is real, compilable Java, and {@link
 * IdeaSkeletonCompileTest} compiles it. Here the golden's job is to make any change to the emitted
 * fragments visible in review.
 *
 * <p>To refresh after an intentional emitter change, run this test and copy the scanner it writes
 * under {@code target/test-output/} over {@link #GOLDEN}.
 */
public class IdeaSkeletonEmitterTest {

  private static final Path SPEC =
      KotlinEmitterTest.moduleFile("src/test/resources/jflex/idea-lexer.flex");
  private static final Path GOLDEN =
      KotlinEmitterTest.moduleFile("src/test/resources/jflex/idea-lexer.java.golden");
  private static final Path OUT_DIR = KotlinEmitterTest.outputDir("IdeaSkeletonEmitterTest");

  @Before
  public void setUp() throws IOException {
    // Also pins the default: DEFAULT_LOC is idea-flex.skeleton, so setDefaultOptions() loads
    // exactly the skeleton under test and no explicit skeleton load is needed. Output mode is left
    // at its default, OutputMode.JAVA.
    OptionUtils.setDefaultOptions();
    Files.createDirectories(OUT_DIR);
    OptionUtils.setDir(OUT_DIR.toFile());
  }

  @After
  public void tearDown() {
    // Skeleton.line[] is static, so restore it rather than relying on test ordering.
    OptionUtils.setDefaultOptions();
  }

  @Test
  public void defaultSkeletonIsTheIdeaJavaSkeleton() {
    assertThat(Skeleton.getSkeletonFile().getPath()).endsWith("jflex/idea-flex.skeleton");
  }

  @Test
  public void emitsExpectedJavaScanner() throws Exception {
    String scanner = read(Paths.get(new LexGenerator(SPEC.toFile()).generate()));

    // Guards that survive a golden refresh, since refreshing is a copy of whatever was emitted.
    assertThat(scanner).contains("implements jflex.testing.lexer.FlexLexer");
    assertThat(scanner).contains("public String advance()");
    // The %bol block indexes the buffer; `^` is broken if it is missing entirely.
    assertThat(scanner).contains("zzAtBOL");

    assertThat(stripHeader(scanner)).isEqualTo(stripHeader(read(GOLDEN)));
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
