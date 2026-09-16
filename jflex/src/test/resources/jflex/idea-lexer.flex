/*
 * Fixture for the IntelliJ skeletons (idea-flex.skeleton and idea-flex-kotlin.skeleton).
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Shaped like a real IntelliJ lexer spec, which is what makes it compilable: the skeletons declare
 * reset/getTokenStart/getTokenEnd/yystate/yybegin as overrides, so the scanner needs a supertype
 * that declares them (%implements), and IntelliJ names the scan function `advance`.
 *
 * The `^` rule is load-bearing. It sets scanner.bolUsed(), which makes the emitter produce the
 * block that assigns zzAtBOL -- the one place where Java switch fall-through has repeatedly been
 * mistranslated into Kotlin `when`, and which also indexes the buffer directly. Lexing "start" both
 * at offset 0 and after a newline distinguishes a working zzAtBOL from a broken one.
 */

%%

%class IdeaLexer
%implements jflex.testing.lexer.FlexLexer
%function advance
%type String
%unicode

%%

^"start"  { return "bol"; }
"start"   { return "plain"; }
[^]       { return "other"; }
