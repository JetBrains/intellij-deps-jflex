/*
 * Copyright (C) 2026
 * SPDX-License-Identifier: BSD-3-Clause
 */
package jflex.testing.lexer;

import java.io.IOException;

/**
 * Stand-in for IntelliJ's {@code com.intellij.lexer.FlexLexer}, the interface every scanner
 * generated from {@code idea-flex.skeleton} / {@code idea-flex-kotlin.skeleton} implements.
 *
 * <p>The IntelliJ skeletons declare {@code reset}, {@code getTokenStart}, {@code getTokenEnd},
 * {@code yystate} and {@code yybegin} as {@code override} members, so a scanner generated from them
 * only compiles when a supertype declares them. IntelliJ is not a dependency of this repo, so the
 * tests supply their own copy; only the members the skeletons override are needed.
 *
 * <p>Deliberately a Java interface rather than Kotlin: there are no {@code .kt} sources in this
 * repo (all emitter code is Java that emits Kotlin), and the real IntelliJ interface is Java too,
 * so this exercises the same Java/Kotlin interop the generated scanners actually rely on.
 */
public interface FlexLexer {

  void yybegin(int state);

  int yystate();

  int getTokenStart();

  int getTokenEnd();

  /**
   * The scan function. Named {@code advance} because the IntelliJ specs use {@code %function
   * advance}, and declared {@code throws IOException} because {@code Emitter} emits that on the
   * scan function -- as does the real IntelliJ interface. ({@code KotlinEmitter} does not, Kotlin
   * having no checked exceptions, so the Kotlin scanners implement this either way.)
   */
  String advance() throws IOException;

  void reset(CharSequence buffer, int start, int end, int initialState);
}
