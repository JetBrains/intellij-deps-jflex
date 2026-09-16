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
 * Golden-file test for {@link KotlinEmitter} paired with {@code idea-flex-kotlin.skeleton}, the
 * IntelliJ Kotlin skeleton.
 *
 * <p>This is the pairing the IntelliJ monorepo actually ships: its Ant {@code kflex} macro runs
 * {@code --output-mode kotlin} with this skeleton to generate the platform's Kotlin lexers. The
 * skeleton used to live only in the monorepo and was updated there by hand against each JFlex
 * release; it is now a packaged resource here so the jar carries the skeleton it was tested with.
 *
 * <p>Distinct from {@link KotlinSkeletonEmitterTest}, which covers {@code kotlin_skeleton.nested}
 * -- a different, KMP-oriented skeleton ({@code kotlinx.io}, nested streams) whose output does not
 * compile. This one's does, and {@link IdeaKotlinSkeletonCompileTest} proves it.
 *
 * <p>Note the skeleton's {@code L1..L20} marker suffixes are misleading: sections are positional
 * ({@code Skeleton.readSkel} discards the text after {@code ---}), and this skeleton's physical
 * order is <em>not</em> L1..L20 -- the "throws clause" section sits ninth, after "zzDoEOF". That
 * order is correct, because {@link KotlinEmitter}'s {@code emitNext()} sequence differs from {@link
 * Emitter}'s; it is the comments that are wrong. Do not "fix" them by reordering the sections.
 *
 * <p>To refresh after an intentional emitter change, run this test and copy the scanner it writes
 * under {@code target/test-output/} over {@link #GOLDEN}.
 */
public class IdeaKotlinSkeletonEmitterTest {

  /** Packaged under {@code src/main/resources/}, so it resolves via the classloader. */
  private static final String SKELETON = "jflex/idea-flex-kotlin.skeleton";

  private static final Path SPEC =
      KotlinEmitterTest.moduleFile("src/test/resources/jflex/idea-lexer.flex");
  private static final Path GOLDEN =
      KotlinEmitterTest.moduleFile("src/test/resources/jflex/idea-lexer.kt.golden");
  private static final Path EOF_SPEC =
      KotlinEmitterTest.moduleFile("src/test/resources/jflex/eof-kotlin-issue15.flex");
  private static final Path OUT_DIR = KotlinEmitterTest.outputDir("IdeaKotlinSkeletonEmitterTest");

  @Before
  public void setUp() throws IOException {
    // Ordering is load-bearing. setDefaultOptions() resets the skeleton via Skeleton.readDefault(),
    // so it has to come first; it also sets Options.encoding, without which LexGenerator's
    // constructor calls setDefaultOptions() again and discards the skeleton installed below.
    OptionUtils.setDefaultOptions();
    Options.output_mode = OutputMode.KOTLIN;
    KotlinEmitterTest.readSkeletonResource(SKELETON);
    Files.createDirectories(OUT_DIR);
    OptionUtils.setDir(OUT_DIR.toFile());
  }

  @After
  public void tearDown() {
    // Skeleton.line[] is static; restore it so the skeleton does not leak into other tests.
    OptionUtils.setDefaultOptions();
  }

  @Test
  public void emitsExpectedKotlinScanner() throws Exception {
    String scanner = read(Paths.get(new LexGenerator(SPEC.toFile()).generate()));

    assertThat(stripHeader(scanner)).isEqualTo(stripHeader(read(GOLDEN)));
  }

  /**
   * Every newline character has to share the one {@code when} branch that sets {@code zzAtBOL}.
   * {@link Emitter} gets this from {@code switch} fall-through, which Kotlin's {@code when} does
   * not have, so the characters must be listed in a single comma-separated branch. They were once
   * emitted as separate empty arms carrying the Java {@code // fall through} comments, which left
   * {@code zzAtBOL} unset for a plain {@code '\n'} and so broke every {@code ^} anchor.
   *
   * <p>Asserted separately from the golden because refreshing the golden is a copy of whatever the
   * emitter produced, so the golden alone cannot keep the defect from coming back.
   */
  @Test
  public void emitsAllNewlineCharsInOneBolBranch() throws Exception {
    String scanner = read(Paths.get(new LexGenerator(SPEC.toFile()).generate()));

    assertThat(scanner).doesNotContain("'\\n' -> {}");
    assertThat(scanner)
        .contains("'\\n', '\\u000B', '\\u000C', '\\u0085', '\\u2028', '\\u2029' -> {");
  }

  /**
   * The buffer is a {@code CharSequence} and must be read with Kotlin indexing. The {@code %bol}
   * block was copied from {@link Emitter} still calling Java's {@code charAt}, which does not
   * resolve in Kotlin, so any spec using {@code ^} emitted a scanner that would not compile.
   */
  @Test
  public void indexesTheBufferInsteadOfCallingCharAt() throws Exception {
    String scanner = read(Paths.get(new LexGenerator(SPEC.toFile()).generate()));

    assertThat(scanner).doesNotContain("zzBufferL.charAt(");
    assertThat(scanner).contains("zzBufferL[zzMarkedPosL-1]");
  }

  /**
   * Regression guard for <a href="https://github.com/JetBrains/intellij-deps-jflex/issues/15">issue
   * #15</a>: a synthetic {@code case <n>: break;} that {@link Emitter} uses to stop {@code switch}
   * fall-through became a bare {@code <n> -> break} in Kotlin, binding to the enclosing scan loop
   * rather than the {@code when}.
   */
  @Test
  public void emitsNoBareBreakInEofWhen() throws Exception {
    String scanner = read(Paths.get(new LexGenerator(EOF_SPEC.toFile()).generate()));

    assertThat(scanner).doesNotContainMatch("[0-9]+ -> break");
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
