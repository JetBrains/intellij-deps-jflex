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
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import jflex.core.OptionUtils;
import jflex.option.Options;
import jflex.option.OutputMode;
import kotlinx.io.Buffer;
import kotlinx.io.Source;
import kotlinx.io.Utf8Kt;
import org.jetbrains.kotlin.cli.common.ExitCode;
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Compiles and runs the scanner generated from {@code skeleton_kotlin.default}, the default Kotlin
 * skeleton.
 *
 * <p>This is the gate that the other {@code kotlin_*} skeleton never had. {@code
 * kotlin_skeleton.nested} is ungated and does not compile, and the reason generalises: a golden
 * only freezes whatever was emitted, so it cannot tell working output from broken output. Both
 * defects found while writing this skeleton would have sailed past a golden -- a {@code ^} anchor
 * that never matched at offset 0, and a surrogate pair split across a buffer refill.
 *
 * <p>Unlike the two {@code Idea*} skeletons, this one is standalone: it declares no overrides, so
 * the generated scanner needs no supertype, and the skeleton supplies its own {@code
 * constructor(kotlinx.io.Source)} because {@link KotlinEmitter} emits no constructor at all. That
 * is why this test drives the scanner by reflection instead of reusing {@code LexerDriver}, which
 * is tied to IntelliJ's {@code FlexLexer}.
 *
 * <p>Maven-only, for the same reason as {@link IdeaKotlinSkeletonCompileTest}: it needs {@code
 * kotlin-compiler-embeddable}, and no Bazel target here provides a Kotlin toolchain.
 */
public class KotlinDefaultSkeletonCompileTest {

  private static final String SKELETON = "jflex/skeleton_kotlin.default";
  private static final String SCANNER_CLASS = "KotlinDefaultLexer";

  /** Matches {@code ZZ_BUFFERSIZE}, the initial scan-buffer length, in the generated scanner. */
  private static final int BUFFER_SIZE = 16384;

  private static final Path SPEC =
      KotlinEmitterTest.moduleFile("src/test/resources/jflex/kotlin-default-skeleton.flex");
  private static final Path OUT_DIR =
      KotlinEmitterTest.outputDir("KotlinDefaultSkeletonCompileTest");

  private File classes;

  @Before
  public void setUp() throws Exception {
    // setDefaultOptions() first: it resets the skeleton and sets Options.encoding, without which
    // LexGenerator's constructor would call it again and discard the skeleton below.
    OptionUtils.setDefaultOptions();
    Options.output_mode = OutputMode.KOTLIN;
    KotlinEmitterTest.readSkeletonResource(SKELETON);
    Files.createDirectories(OUT_DIR);
    OptionUtils.setDir(OUT_DIR.toFile());

    Path scanner = Paths.get(new LexGenerator(SPEC.toFile()).generate());
    classes = OUT_DIR.resolve("classes").toFile();
    assertThat(classes.mkdirs() || classes.isDirectory()).isTrue();

    ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
    ExitCode exitCode;
    try (PrintStream err = new PrintStream(diagnostics, true, StandardCharsets.UTF_8.name())) {
      exitCode =
          new K2JVMCompiler()
              .exec(
                  err,
                  "-no-stdlib", // the stdlib and kotlinx-io come off the test classpath below
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
  }

  @After
  public void tearDown() {
    OptionUtils.setDefaultOptions();
  }

  /**
   * The token numbers come from {@code kotlin-default-skeleton.flex}: 1 is {@code "/*"}, 2 is
   * {@code ^"start"}, 3 is {@code "a"} with a {@code "b"} lookahead, 4 is a newline, 5 is any other
   * character, 6 is the comment terminator.
   */
  @Test
  public void lexesBeginningOfLineAnchor() throws Exception {
    // "start" is at the beginning of a line at offset 0 *and* after the newline. Getting only the
    // second one means zzAtBOL was never initialised -- KotlinEmitter declares it false where
    // Emitter declares it true, and this skeleton has no reset() to paper over that.
    assertThat(tokenize("start\nstart")).isEqualTo("2 4 2");
    // ... and it must not match mid-line.
    assertThat(tokenize("xstart")).isEqualTo("5 5 5 5 5 5");
  }

  @Test
  public void lexesFixedLookahead() throws Exception {
    // Exercises offsetByCodePoints, which the skeleton has to supply: it is a java.lang.String
    // method with no Kotlin equivalent.
    assertThat(tokenize("ab")).isEqualTo("3 5");
    assertThat(tokenize("ac")).isEqualTo("5 5");
  }

  @Test
  public void lexesLexicalStates() throws Exception {
    assertThat(tokenize("a/*xy*/b")).isEqualTo("5 1 6 5");
  }

  @Test
  public void lexesSupplementaryCodePointAsOneCharacter() throws Exception {
    // U+1F600 is two UTF-16 code units but one code point, so [^] must match it exactly once.
    // This covers the skeleton's UTF-8 decoding as well as its codePoint() helper.
    assertThat(tokenize("\uD83D\uDE00")).isEqualTo("5");
    assertThat(tokenize("")).isEmpty();
  }

  /**
   * Refilling is the part of a streaming skeleton that a short input never reaches, and the
   * surrogate-boundary case is the part of refilling that is easy to get wrong: a supplementary
   * code point whose high surrogate lands in the buffer's last free position must not be split, or
   * the scanner matches each half as a character of its own.
   */
  @Test
  public void refillsBufferWithoutSplittingSurrogatePairs() throws Exception {
    for (int length : new int[] {BUFFER_SIZE - 1, BUFFER_SIZE, BUFFER_SIZE + 1, 3 * BUFFER_SIZE}) {
      assertWithMessage("plain input of length %s", length)
          .that(countTokens(repeat("x", length)))
          .isEqualTo(length);
    }

    for (int pad = BUFFER_SIZE - 6; pad <= BUFFER_SIZE + 6; pad++) {
      assertWithMessage("supplementary code point at offset %s", pad)
          .that(countTokens(repeat("x", pad) + "\uD83D\uDE00" + "yyy"))
          .isEqualTo(pad + 1 + 3);
    }

    int emoji = 20000;
    assertWithMessage("%s back-to-back supplementary code points", emoji)
        .that(countTokens(repeat("\uD83D\uDE00", emoji)))
        .isEqualTo(emoji);
  }

  private static String repeat(String unit, int times) {
    StringBuilder builder = new StringBuilder(unit.length() * times);
    for (int i = 0; i < times; i++) {
      builder.append(unit);
    }
    return builder.toString();
  }

  /** Lexes {@code input} and returns the token numbers, joined by spaces. */
  private String tokenize(String input) throws Exception {
    StringBuilder tokens = new StringBuilder();
    for (int token : lex(input)) {
      if (tokens.length() > 0) {
        tokens.append(' ');
      }
      tokens.append(token);
    }
    return tokens.toString();
  }

  private int countTokens(String input) throws Exception {
    return lex(input).size();
  }

  /**
   * Loads the freshly compiled scanner and runs it to exhaustion.
   *
   * <p>The class loader delegates to this class's own loader so that {@code kotlinx.io.Source}
   * resolves to the same interface the scanner was compiled against, which is what makes the
   * constructor lookup work.
   */
  private List<Integer> lex(String input) throws Exception {
    Buffer buffer = new Buffer();
    Utf8Kt.writeString(buffer, input, 0, input.length());

    ClassLoader parent = getClass().getClassLoader();
    try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toURI().toURL()}, parent)) {
      Class<?> scanner = loader.loadClass(SCANNER_CLASS);
      Object lexer = scanner.getDeclaredConstructor(Source.class).newInstance(buffer);
      Method yylex = scanner.getMethod("yylex");

      List<Integer> tokens = new ArrayList<>();
      while (true) {
        int token = (Integer) yylex.invoke(lexer);
        if (token == -1) { // YYEOF, the %int default EOF value
          return tokens;
        }
        tokens.add(token);
      }
    }
  }
}
