/*
 * Regression fixture for https://github.com/JetBrains/intellij-deps-jflex/issues/15
 * SPDX-License-Identifier: BSD-3-Clause
 */

%%

%class Issue15EofLexer
%type String
%state FOO

%%

<FOO> {
  <<EOF>> { return "eof"; }
  [^] { }
}

[^] { yybegin(FOO); }
