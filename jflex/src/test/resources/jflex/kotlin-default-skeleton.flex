/*
 * Fixture for skeleton_kotlin.default, the default Kotlin skeleton.
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Deliberately standalone: no %implements, so the generated scanner needs no supertype and the
 * skeleton's own constructor(kotlinx.io.Source) is the only entry point. The rules are chosen to
 * reach the emitter paths that depend on skeleton-provided helpers:
 *
 *   ^"start"  -> zzAtBOL block, which indexes the scan buffer directly
 *   "a" / "b" -> fixed lookahead, which calls offsetByCodePoints
 *   %line %column %char -> the counting blocks
 *   %eof{ }   -> zzDoEOF, so section 8 is exercised
 *   a state   -> ZZ_LEXSTATE and yybegin
 */
%%

%class KotlinDefaultLexer
%unicode
%int
%line
%column
%char
%state COMMENT

%{
  var commentCount: Int = 0
%}

%eof{
  commentCount = -1
%eof}

NL = \r|\n|\r\n
WS = [ \t]+

%%

<YYINITIAL> {
  "/*"      { yybegin(COMMENT); commentCount++; return 1; }
  ^"start"  { return 2; }
  "a" / "b" { return 3; }
  {WS}      { /* ignore */ }
  {NL}      { return 4; }
  [^]       { return 5; }
}

<COMMENT> {
  "*/"      { yybegin(YYINITIAL); return 6; }
  [^]       { /* ignore */ }
}
