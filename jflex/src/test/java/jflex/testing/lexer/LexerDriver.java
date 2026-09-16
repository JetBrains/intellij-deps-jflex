/*
 * Copyright (C) 2026
 * SPDX-License-Identifier: BSD-3-Clause
 */
package jflex.testing.lexer;

import java.io.File;
import java.io.Reader;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;

/** Loads a freshly compiled scanner and runs it, the way IntelliJ drives its lexers. */
public final class LexerDriver {

  private LexerDriver() {}

  /**
   * Loads {@code className} from {@code classesDir} and lexes {@code input}, returning the tokens
   * as {@code name[start,end]} joined by spaces.
   *
   * <p>The class loader delegates to this class's own loader so that {@link FlexLexer} resolves to
   * the same interface the test compiled the scanner against, which is what makes the cast work.
   */
  public static String tokenize(File classesDir, String className, String input) throws Exception {
    ClassLoader parent = LexerDriver.class.getClassLoader();
    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {classesDir.toURI().toURL()}, parent)) {
      FlexLexer lexer = (FlexLexer) newInstance(loader.loadClass(className));
      // The IntelliJ incremental-lexer entry point: the whole buffer is handed over at once, which
      // is why the skeletons stub out zzRefill().
      lexer.reset(input, 0, input.length(), 0);

      StringBuilder tokens = new StringBuilder();
      String token;
      while ((token = lexer.advance()) != null) {
        if (tokens.length() > 0) {
          tokens.append(' ');
        }
        tokens.append(token).append('[').append(lexer.getTokenStart());
        tokens.append(',').append(lexer.getTokenEnd()).append(']');
      }
      return tokens.toString();
    }
  }

  /**
   * Instantiates a generated scanner, whichever constructor it got.
   *
   * <p>The two IntelliJ skeletons differ here: the Java one emits {@code Lexer(java.io.Reader)} and
   * IntelliJ passes it a null reader, because the buffer arrives later through {@code reset()} and
   * {@code zzRefill()} is a stub. The Kotlin one emits no constructor at all, so the scanner has
   * only the default one. Both constructors are package-private in the generated code.
   */
  private static Object newInstance(Class<?> scanner) throws Exception {
    for (Constructor<?> constructor : scanner.getDeclaredConstructors()) {
      Class<?>[] parameters = constructor.getParameterTypes();
      constructor.setAccessible(true);
      if (parameters.length == 0) {
        return constructor.newInstance();
      }
      if (parameters.length == 1 && Reader.class.isAssignableFrom(parameters[0])) {
        return constructor.newInstance((Reader) null);
      }
    }
    throw new AssertionError(
        "no usable constructor on "
            + scanner.getName()
            + ": "
            + Arrays.toString(scanner.getDeclaredConstructors()));
  }
}
