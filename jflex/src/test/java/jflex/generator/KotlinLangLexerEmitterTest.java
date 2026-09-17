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
 * Golden-file test for {@link KotlinEmitter} paired with {@code idea-flex-kotlin.skeleton} over a
 * <em>real</em> spec: {@code kotlin-lang.flex} is a verbatim copy of the Kotlin compiler's own
 * multiplatform lexer spec, {@code
 * compiler/multiplatform-parsing/common/src/org/jetbrains/kotlin/kmp/lexer/Kotlin.flex} in
 * JetBrains/kotlin.
 *
 * <p>Value over {@link IdeaKotlinSkeletonEmitterTest}, which uses the small hand-written {@code
 * idea-lexer.flex}: this spec is a downstream consumer of exactly this generator and skeleton
 * pairing, and it is big enough (662 NFA states, 234 DFA states after minimization, eight lexical
 * states, {@code %unicode}, code-point-wide char classes, table compression well past the single
 * chunk) to exercise emitter paths the toy spec never reaches. If a {@link KotlinEmitter} change
 * would break the Kotlin compiler's lexer, this golden moves.
 *
 * <p>Generation-only, by design. The emitted scanner cannot be compiled here: its user code is
 * Kotlin against {@code com.intellij.platform.syntax.*} and {@code KtTokens}, none of which is a
 * dependency of this repo. The compile gate for this skeleton stays {@link
 * IdeaKotlinSkeletonCompileTest}. For the same reason there is no Java-output counterpart -- {@code
 * --output-mode java} would emit the Kotlin user code verbatim into a {@code .java} file.
 *
 * <p>Keep the spec byte-identical to upstream; refresh it and this golden together. To refresh the
 * golden after an intentional emitter change, run this test and copy the scanner it writes under
 * {@code target/test-output/} over {@link #GOLDEN}.
 */
public class KotlinLangLexerEmitterTest {

  /** Packaged under {@code src/main/resources/}, so it resolves via the classloader. */
  private static final String SKELETON = "jflex/idea-flex-kotlin.skeleton";

  private static final Path SPEC =
      KotlinEmitterTest.moduleFile("src/test/resources/jflex/kotlin-lang.flex");
  private static final Path GOLDEN =
      KotlinEmitterTest.moduleFile("src/test/resources/jflex/kotlin-lang.kt.golden");
  private static final Path OUT_DIR = KotlinEmitterTest.outputDir("KotlinLangLexerEmitterTest");

  @Before
  public void setUp() throws IOException {
    // Ordering is load-bearing; see IdeaKotlinSkeletonEmitterTest.setUp().
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

    // Asserted separately from the golden, which only records whatever the emitter produced:
    // a bare `<n> -> break` binds to the scan loop rather than the `when` (issue #15), and
    // Java's charAt does not resolve on the CharSequence buffer in Kotlin.
    assertThat(scanner).doesNotContainMatch("[0-9]+ -> break");
    assertThat(scanner).doesNotContain("zzBufferL.charAt(");
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
