/*
 * Fixture for the %cup EOF value in both output modes.
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * %cup supplies a default EOF value that constructs a java_cup Symbol. It is built as a string in
 * LexScan.flex and emitted verbatim by both emitters, so whether it carries the `new` keyword has
 * to depend on the output mode. Nothing else here matters; the spec just has to set %cup and not
 * override the EOF value itself.
 *
 * Deliberately phrased without the emitted expression spelled out: this header is user code and is
 * copied into the generated scanner, where it would satisfy the tests' text assertions.
 */

%%

%class CupEofLexer
%cup

%%

[^] { }
