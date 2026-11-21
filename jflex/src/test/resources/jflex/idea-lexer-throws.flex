/*
 * Fixture for IdeaKotlinSkeletonCompileTest: the exception directives.
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * %yylexthrow and %scanerror are what make KotlinEmitter emit @Throws on the scan function and on
 * yypushback. Both were once emitted as code that did not compile -- the exception list without
 * its "@Throws(...)" wrapper, and yypushback without its opening brace -- and idea-lexer.flex uses
 * neither directive, so this fixture exists to compile those paths. The "ab" rule calls
 * yypushback, so the test also runs it.
 */

%%

%class IdeaThrowsLexer
%implements jflex.testing.lexer.FlexLexer
%function advance
%type String
%unicode
%yylexthrow java.io.IOException
%scanerror java.lang.IllegalStateException

%%

"ab"  { yypushback(1); return "a"; }
[^]   { return "other"; }
