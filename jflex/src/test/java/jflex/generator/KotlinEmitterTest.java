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
import jflex.option.OutputMode;
import jflex.option.Options;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Golden-file test for {@link KotlinEmitter}: the scanner emitted for a spec with a state-scoped
 * {@code <<EOF>>} action is compared in full against a committed expectation.
 *
 * <p>This is the regression guard for
 * <a href="https://github.com/JetBrains/intellij-deps-jflex/issues/15">issue #15</a>: a bare {@code break} inside the
 * Kotlin {@code when (zzLexicalState)} block binds to the enclosing scan loop rather than to the
 * {@code when}, which makes the end of {@code yylex()} reachable and leaves the function without a
 * return on that path.
 *
 * <p>To refresh the expectation after an intentional emitter change, run this test and copy the
 * scanner it writes under {@code target/} over {@link #GOLDEN}.
 */
public class KotlinEmitterTest {

    private static final Path SPEC = Paths.get("src/test/resources/jflex/eof-kotlin-issue15.flex");
    private static final Path GOLDEN = Paths.get("src/test/resources/jflex/eof-kotlin-issue15.kt.golden");
    private static final Path OUT_DIR = Paths.get("target/test-output/KotlinEmitterTest");

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
        String generated = new LexGenerator(SPEC.toFile()).generate();

        assertThat(stripHeader(read(Paths.get(generated)))).isEqualTo(stripHeader(read(GOLDEN)));
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /**
     * Drops the two leading comment lines naming the JFlex version and the absolute path of the
     * spec, so the expectation is neither version- nor machine-specific.
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
