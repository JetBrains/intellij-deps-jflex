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
 * The {@code %cup} EOF value has to be a valid constructor call in whichever language is being
 * emitted.
 *
 * <p>{@code %cup} supplies a default {@code eofVal} of {@code return new
 * java_cup.runtime.Symbol(sym.EOF);}, assembled as a string in {@code LexScan.flex} and emitted
 * verbatim by both emitters. Kotlin constructor calls take no {@code new}, so the keyword was
 * simply dropped -- which fixed Kotlin and left every Java {@code %cup} spec emitting {@code return
 * java_cup.runtime.Symbol(sym.EOF);}, a call to a method that does not exist. It went unnoticed
 * because nothing tested {@code %cup} output in either mode, and because the bootstrap scanner is
 * regenerated from {@code LexScan.flex} only on a clean build.
 *
 * <p>Both directions are asserted, since this is a spot where fixing one mode breaks the other.
 */
public class CupEofValueTest {

  private static final Path SPEC =
      KotlinEmitterTest.moduleFile("src/test/resources/jflex/cup-eof.flex");
  private static final Path OUT_DIR = KotlinEmitterTest.outputDir("CupEofValueTest");

  @Before
  public void setUp() throws IOException {
    OptionUtils.setDefaultOptions();
    Files.createDirectories(OUT_DIR);
    OptionUtils.setDir(OUT_DIR.toFile());
  }

  @After
  public void tearDown() {
    OptionUtils.setDefaultOptions();
  }

  @Test
  public void javaModeConstructsTheEofSymbolWithNew() throws Exception {
    String scanner = generate();

    assertThat(scanner).contains("return new java_cup.runtime.Symbol(sym.EOF);");
  }

  @Test
  public void kotlinModeConstructsTheEofSymbolWithoutNew() throws Exception {
    Options.output_mode = OutputMode.KOTLIN;

    String scanner = generate();

    assertThat(scanner).contains("return java_cup.runtime.Symbol(sym.EOF);");
    assertThat(scanner).doesNotContain("return new java_cup.runtime.Symbol(sym.EOF);");
  }

  private static String generate() throws IOException {
    Path emitted = Paths.get(new LexGenerator(SPEC.toFile()).generate());
    return new String(Files.readAllBytes(emitted), StandardCharsets.UTF_8);
  }
}
