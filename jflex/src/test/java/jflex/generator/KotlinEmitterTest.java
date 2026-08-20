/*
 * Copyright (C) 2026
 * SPDX-License-Identifier: BSD-3-Clause
 */
package jflex.generator;

import static com.google.common.truth.Truth.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import jflex.core.OptionUtils;
import jflex.option.OutputMode;
import jflex.option.Options;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Regression test for https://github.com/JetBrains/intellij-deps-jflex/issues/15: a
 * state-scoped {@code <<EOF>>} action must not emit a bare {@code break} inside the Kotlin
 * {@code when (zzLexicalState)} block. A bare {@code break} there binds to the enclosing scan
 * loop rather than the {@code when}, leaving the generated {@code advance()} without a return
 * on that path.
 */
public class KotlinEmitterTest {

  private String generatedFile;

  @Before
  public void setUp() {
    OptionUtils.setDefaultOptions();
  }

  @After
  public void tearDown() {
    if (generatedFile != null) {
      new File(generatedFile).delete();
    }
  }

  @Test
  public void emitEOFVal_doesNotEmitBareBreakInsideWhen() throws Exception {
    Options.output_mode = OutputMode.KOTLIN;
    File specFile = new File("src/test/resources/jflex/eof-kotlin-issue15.flex");

    LexGenerator generator = new LexGenerator(specFile);
    generatedFile = generator.generate();
    String generated =
        new String(Files.readAllBytes(new File(generatedFile).toPath()), StandardCharsets.UTF_8);

    assertThat(generated).doesNotContainMatch("[0-9]+ -> break");
  }
}