/*
 * Fixture covering `^` (beginning-of-line) handling in Kotlin output mode.
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * `^` sets scanner.bolUsed(), which makes KotlinEmitter emit the `when` that assigns zzAtBOL.
 * That branch used to be emitted as five empty `-> {}` arms plus one that set the flag, a
 * fall-through translated from Java that Kotlin's `when` does not honour, so a plain '\n' never
 * set zzAtBOL. %line/%column additionally pull in the line-counting `when`.
 */

%%

%class BolKotlinLexer
%type String
%line
%column

%%

^"start"    { return "bol"; }
"start"     { return "plain"; }
[^]         { }
