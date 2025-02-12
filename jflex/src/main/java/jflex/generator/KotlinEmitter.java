/*
 * Copyright (C) 1998-2018  Gerwin Klein <lsf@jflex.de>
 * SPDX-License-Identifier: BSD-3-Clause
 */

package jflex.generator;

import java.io.File;
import java.io.PrintWriter;
import java.util.*;
import jflex.base.Build;
import jflex.base.Pair;
import jflex.core.*;
import jflex.core.unicode.CMapBlock;
import jflex.core.unicode.CharClasses;
import jflex.dfa.DFA;
import jflex.exceptions.GeneratorException;
import jflex.io.FileUtils;
import jflex.l10n.ErrorMessages;
import jflex.logging.Out;
import jflex.option.Options;
import jflex.skeleton.Skeleton;

/**
 * This class manages the actual code generation, putting the scanner together, filling in skeleton
 * sections etc.
 *
 * <p>Table compression, String packing etc. is also done here.
 *
 * @author Gerwin Klein
 * @version JFlex 1.10.0-SNAPSHOT
 */
public final class KotlinEmitter extends IEmitter {
  // bit masks for state attributes
  private static final int FINAL = 1;
  private static final int NOLOOK = 8;

  private final File inputFile;

  private final PrintWriter out;
  private final Skeleton skel;
  private final AbstractLexScan scanner;
  private final LexParse parser;
  private final DFA dfa;

  private boolean[] isTransition;

  // for row killing:
  private int[] rowMap;
  private boolean[] rowKilled;

  // for col killing:
  private int numCols;
  private int[] colMap;
  private boolean[] colKilled;

  /** maps actions to their switch label */
  private final Map<Action, Integer> actionTable = new LinkedHashMap<>();

  private final String visibility;
  private String eofCode;
  private String eofThrow;

  /**
   * Emits the java code.
   *
   * @param inputFile input grammar.
   * @param parser a {@link LexParse}.
   * @param dfa a {@link DFA}.
   * @param writer output file.
   */
  KotlinEmitter(
      String outputFileName, File inputFile, LexParse parser, DFA dfa, PrintWriter writer) {
    this.outputFileName = outputFileName;
    this.out = writer;
    this.parser = parser;
    this.scanner = parser.scanner;
    this.visibility = scanner.visibility();
    this.inputFile = inputFile;
    this.dfa = dfa;
    this.skel = new Skeleton(out);
  }

  /**
   * Computes base name of the class name. Needs to take into account generics.
   *
   * @param className Class name for which to construct the base name
   */
  static String getBaseName(String className) {
    int gen = className.indexOf('<');
    if (gen < 0) {
      return className;
    } else {
      return className.substring(0, gen);
    }
  }

  /**
   * Constructs a file in Options.getDir() or in the same directory as another file. Makes a backup
   * if the file already exists.
   *
   * @param name the name (without path) of the file
   * @param input fall back location if {@code path = null} (expected to be a file in the directory
   *     to write to)
   * @return The constructed File
   */
  public static File normalize(String name, File input) {
    File outputFile;

    if (Options.getDir() == null) {
      if (input == null || input.getParent() == null) {
        outputFile = new File(name);
      } else {
        outputFile = new File(input.getParent(), name);
      }
    } else {
      outputFile = new File(Options.getDir(), name);
    }

    if (outputFile.exists() && !Options.no_backup) {
      File backup = new File(outputFile.toString() + "~");

      if (backup.exists()) {
        //noinspection ResultOfMethodCallIgnored
        backup.delete();
      }

      if (outputFile.renameTo(backup)) {
        Out.println("Old file \"" + outputFile + "\" saved as \"" + backup + "\"");
      } else {
        Out.println("Couldn't save old file \"" + outputFile + "\", overwriting!");
      }
    }

    return outputFile;
  }

  private void println() {
    out.println();
  }

  private void println(String line) {
    out.println(line);
  }

  private void println(int i) {
    out.println(i);
  }

  private void print(String line) {
    out.print(line);
  }

  private void print(int i) {
    out.print(i);
  }

  private void print(int i, @SuppressWarnings("SameParameterValue") int tab) {
    int exp;

    if (i < 0) {
      exp = 1;
    } else {
      exp = 10;
    }

    while (tab-- > 1) {
      if (Math.abs(i) < exp) {
        print(" ");
      }
      exp *= 10;
    }

    print(i);
  }

  private boolean hasGenLookAhead() {
    return dfa.lookaheadUsed();
  }

  private void emitLookBuffer() {
    if (!hasGenLookAhead()) {
      return;
    }

    println("  /** For the backwards DFA of general lookahead statements */");
    // TODO! convert to bitset; add a non java STL implementation
    println(
        "  private var zzFin: BooleanArray = BooleanArray(Math.min(ZZ_BUFFERSIZE, zzMaxBufferLen())+1);");
    println();
  }

  private void emitScanError() {
    if (scanner.scanErrorException() != null) {
      println("  @Throws(" + scanner.scanErrorException() + "::class)");
    }
    print("  private fun zzScanError(errorCode: Int)");

    println(" {");

    skel.emitNext(); // 7

    if (scanner.scanErrorException() == null) {
      println("    throw Error(message);");
    } else {
      println("    throw " + scanner.scanErrorException() + "(message);");
    }
    println("  }");
  }

  private void emitPushback() {
    skel.emitNext(); // 8

    if (scanner.scanErrorException() != null) {
      println("  @Throws(" + scanner.scanErrorException + "::class)");
    }
    print("  " + visibility + " fun yypushback(number: Int) ");
    if (scanner.scanErrorException() == null) {
      println(" {");
    }

    skel.emitNext(); // 9
  }

  private void emitTokenDebug(String functionName) {
    println("  /**");
    println("   * Same as " + functionName + " but also prints the token to standard out");
    println("   * for debugging.");
    println("   */");

    if (!scanner.lexThrow().isEmpty() || scanner.scanErrorException() != null) {
      print("  @Throws(");

      for (String thrown : scanner.lexThrow()) {
        print(thrown);
        print("::class, ");
      }

      if (scanner.scanErrorException() != null) {
        print(scanner.scanErrorException());
        print("::class, ");
      }

      println(")");
    }

    if (scanner.cupCompatible() || scanner.cup2Compatible()) {
      // cup interface forces public method
      print("  public ");
    } else {
      print("  " + visibility + " ");
    }

    print("fun debug_");

    print(functionName);

    print("()");

    if (scanner.tokenType() == null) {
      if (scanner.isInteger()) {
        print(": Int?");
      } else if (scanner.isIntWrap()) {
        print(": Integer?");
      } else {
        print(": Yytoken?");
      }
    } else {
      print(": " + scanner.tokenType() + "?");
    }

    println(" {");

    println("    var s:" + scanner.tokenType() + "? = " + functionName + "();");
    println("    if (s == null) return null;");
    print("   println( ");
    if (scanner.lineCount()) {
      print("\"line:\" + (yyline+1) + ");
    }
    if (scanner.columnCount()) {
      print("\" col:\" + (yycolumn+1) + ");
    }
    if (scanner.charCount()) {
      print("\" char:\" + yychar + ");
    }
    println("\" --\"+ yytext() + \"--\" + getTokenName(s.sym) + \"--\");");
    println("    return s;");
    println("  }");
    println("");
  }

  private void emitMain(String functionName) {
    if (!(scanner.standalone() || scanner.debugOption() || scanner.cupDebug())) {
      return;
    }

    if (scanner.cupDebug()) {
      println("  /**");
      println("   * Converts an int token code into the name of the");
      println("   * token by reflection on the cup symbol class/interface " + scanner.cupSymbol());
      println("   */");
      println("  private fun getTokenName(token: Int): String {");
      println("    try {");
      println(
          "       var classFields: Array<java.lang.reflect.Field> = "
              + scanner.cupSymbol()
              + "::class.java.fields");
      println("      for (i in classFields.indices) {");
      println("        if (classFields[i].getInt(null) == token) {");
      println("          return classFields[i].name");
      println("        }");
      println("      }");
      println("    } catch (e: Exception) {");
      println("      e.printStackTrace(System.err);");
      println("    }");
      println("");
      println("    return \"UNKNOWN TOKEN\";");
      println("  }");
      println("");
    }

    if (scanner.standalone()) {
      println("  /**");
      println("   * Runs the scanner on input files.");
      println("   *");
      println("   * This is a standalone scanner, it will print any unmatched");
      println("   * text to System.out unchanged.");
      println("   *");
      println("   * @param argv   the command line, contains the filenames to run");
      println("   *               the scanner on.");
      println("   */");
    } else {
      println("  /**");
      println("   * Runs the scanner on input files.");
      println("   *");
      println("   * This main method is the debugging routine for the scanner.");
      println("   * It prints debugging information about each returned token to");
      println("   * System.out until the end of file is reached, or an error occured.");
      println("   *");
      println("   * @param argv   the command line, contains the filenames to run");
      println("   *               the scanner on.");
      println("   */");
    }

    String className = getBaseName(scanner.className());

    println("  public fun main(argv: Array<String>) {");
    println("    if (argv.isEmpty()) {");
    println(
        "      println(\"Usage : java " + className + " [ --encoding <name> ] <inputfile(s)>\");");
    println("    }");
    println("    else {");
    println("      var firstFilePos = 0;");
    println("      var encodingName = \"UTF-8\";");
    println("      if (argv[0].equals(\"--encoding\")) {");
    println("        firstFilePos = 2;");
    println("        encodingName = argv[1];");
    println("        try {");
    println("          // Side-effect: is encodingName valid?");
    println("          java.nio.charset.Charset.forName(encodingName);");
    println("        } catch (e: Exception) {");
    println("          println(\"Invalid encoding '\" + encodingName + \"'\");");
    println("          return;");
    println("        }");
    println("      }");
    println("      for (i in firstFilePos..<argv.size) {");
    println("        var scanner: " + className + "? = null");
    println("        var stream: kotlinx.io.Source? = null");
    println("        try {");
    println("          stream = SystemFileSystem.source(Path(argv[i])).buffered()");
    println("          scanner = " + className + "(stream);");
    if (scanner.standalone()) {
      println("          while ( !scanner.zzAtEOF ) scanner." + functionName + "();");
    } else if (scanner.cupDebug()) {
      println("          while ( !scanner.zzAtEOF ) scanner.debug_" + functionName + "();");
    } else {
      println("          do {");
      println("            println(scanner." + functionName + "());");
      println("          } while (!scanner.zzAtEOF);");
      println("");
    }

    println("        }");
    println("        catch (e: java.io.FileNotFoundException) {");
    println("          println(\"File not found : \\\"\"+argv[i]+\"\\\"\");");
    println("        }");
    println("        catch (e: java.io.IOException) {");
    println("          println(\"IO error scanning file \\\"\"+argv[i]+\"\\\"\");");
    println("          println(e);");
    println("        }");
    println("        catch (e: Exception) {");
    println("          println(\"Unexpected exception:\");");
    println("          e.printStackTrace();");
    println("        }");
    println("        finally {");
    println("          if (stream != null) {");
    println("            try {");
    println("              stream.close();");
    println("            }");
    println("            catch (e: java.io.IOException) {");
    println("              println(\"IO error closing file \\\"\"+argv[i]+\"\\\"\");");
    println("              println(e);");
    println("            }");
    println("          }");
    println("        }");
    println("      }");
    println("    }");
    println("  }");
    println("");
  }

  private void emitNoMatch() {
    println("            zzScanError(ZZ_NO_MATCH);");
  }

  private void emitNextInput() {
    println("          if (zzCurrentPosL < zzEndReadL) {");
    println(
        "            zzInput = Character.codePointAt(zzBufferL.toString().toCharArray(), zzCurrentPosL);");
    println("            zzCurrentPosL += Character.charCount(zzInput);");
    println("          }");
    println("          else if (zzAtEOF) {");
    println("            zzInput = YYEOF;");
    println("            return@zzForAction;");
    println("          }");
    println("          else {");
    println("            // store back cached positions");
    println("            zzCurrentPos  = zzCurrentPosL;");
    println("            zzMarkedPos   = zzMarkedPosL;");
    println("            var eof: Boolean = zzRefill();");
    println("            // get translated positions and possibly new buffer");
    println("            zzCurrentPosL  = zzCurrentPos;");
    println("            zzMarkedPosL   = zzMarkedPos;");
    println("            zzBufferL      = zzBuffer;");
    println("            zzEndReadL     = zzEndRead;");
    println("            if (eof) {");
    println("              zzInput = YYEOF;");
    println("              return@zzForAction;");
    println("            }");
    println("            else {");
    println(
        "              zzInput = Character.codePointAt(zzBufferL.toString().toCharArray(), zzCurrentPosL);");
    println("              zzCurrentPosL += Character.charCount(zzInput);");
    println("            }");
    println("          }");
  }

  public static String sourceFileString(File file) {
    String path = FileUtils.getRelativePath(Options.getRootDirectory(), file);
    if (File.separatorChar == '\\') {
      path = FileUtils.slashify(path);
    }
    // Character '\' can be use for Unicode representation, e.g. \\u000A is new line
    return path.replace("\\", "\\\\");
  }

  private void emitHeader() {
    println(
        "// Generated by JFlex "
            + Build.VERSION
            + " http://jflex.de/  (tweaked for IntelliJ platform)");
    println("// source: " + sourceFileString(inputFile));
    println("// skeleton: " + sourceFileString(Skeleton.getSkeletonFile()));
    println("");
  }

  private void emitUserCode() {
    println(scanner.userCode());

    List<String> additionalImports = new ArrayList<>();
    if (scanner.cup2Compatible()) {
      additionalImports.addAll(
          Arrays.asList(
              "/* CUP2 imports */",
              "import edu.tum.cup2.scanner.*;",
              "import edu.tum.cup2.grammar.*;"));
    }

    if (hasGenLookAhead()) {
      additionalImports.add("import java.util.BitSet;");
    }

    if (!additionalImports.isEmpty()) {
      println();
      additionalImports.forEach(this::println);
      println();
    }
  }

  private void emitClassName() {
    if (scanner.isAbstract()) {
      print("abstract ");
    }

    print("class ");
    print(scanner.className());

    if (scanner.isExtending() != null || scanner.isImplementing() != null) {
      print(" : ");
    }

    if (scanner.isExtending() != null) {
      print(scanner.isExtending());
      print(", ");
    }

    if (scanner.isImplementing() != null) {
      print(scanner.isImplementing());
    }

    println(" {");
  }

  private void emitLexicalStates() {
    for (String name : scanner.stateNames()) {
      int num = scanner.getStateNumber(name);

      println("  " + visibility + " var " + name + ": Int = " + 2 * num);
    }

    // can't quite get rid of the indirection, even for non-bol lex states:
    // their DFA states might be the same, but their EOF actions might be different
    // (see bug #1540228)
    println("");
    println("  /**");
    println("   * ZZ_LEXSTATE[l] is the state in the DFA for the lexical state l");
    println("   * ZZ_LEXSTATE[l+1] is the state in the DFA for the lexical state l");
    println("   *                  at the beginning of a line");
    println("   * l is of the form l = 2*k, k a non negative integer");
    println("   */");
    println("  private var ZZ_LEXSTATE: IntArray = intArrayOf(");

    int i, j = 0;
    print("    ");

    for (i = 0; i < 2 * dfa.numLexStates() - 1; i++) {
      print(dfa.entryState(i), 2);

      print(", ");

      if (++j >= 16) {
        println();
        print("    ");
        j = 0;
      }
    }

    println(dfa.entryState(i));
    println("  )");
  }

  private void emitDynamicInit() {
    int count = 0;
    int value = dfa.table(0, 0);

    println("  /**");
    println("   * The transition table of the DFA");
    println("   */");

    // allow values from -1 (translate +1), get emitter capacity based on number of states
    KotlinCountEmitter e = KotlinCountEmitter.emitter(dfa.numStates(), +1, "trans");
    e.emitInit();

    for (int i = 0; i < dfa.numStates(); i++) {
      if (!rowKilled[i]) {
        for (int c = 0; c < dfa.numInput(); c++) {
          if (!colKilled[c]) {
            if (dfa.table(i, c) == value) {
              count++;
            } else {
              e.emit(count, value);

              count = 1;
              value = dfa.table(i, c);
            }
          }
        }
      }
    }

    e.emit(count, value);
    e.emitUnpack();

    println(e.toString());
  }

  private void emitCharMapArrayUnPacked() {

    CharClasses cl = parser.getCharClasses();

    println("");
    println("  /**");
    println("   * Translates characters to character classes");
    println("   */");
    println("  private var ZZ_CMAP = intArrayOf(");

    int n = 0; // numbers of entries in current line
    print("    ");

    int max = cl.getMaxCharCode();

    // not very efficient, but good enough for <= 255 characters
    for (char c = 0; c <= max; c++) {
      print(colMap[cl.getClassCode(c)], 2);

      if (c < max) {
        print(", ");
        if (++n >= 16) {
          println();
          print("    ");
          n = 0;
        }
      }
    }

    println();
    println("  );");
    println();
  }

  /**
   * Performs an in-place update to map the colMap translation over the char classes in the
   * second-level cmap table.
   */
  private void mapColMap(int[] blocks) {
    for (int i = 0; i < blocks.length; i++) {
      blocks[i] = colMap[blocks[i]];
    }
  }

  /**
   * Emits two-level character translation tables. The translation is from raw input codepoint to
   * the column in the generated DFA table.
   *
   * <p>For maxCharCode < 256, a single-level unpacked array is used instead.
   */
  private void emitCharMapTables() {
    CharClasses cl = parser.getCharClasses();

    if (cl.getMaxCharCode() < 256) {
      emitCharMapArrayUnPacked();
    } else {
      Pair<int[], int[]> tables = cl.getTables();
      mapColMap(tables.snd);

      println("");
      println("  /**");
      println("   * Top-level table for translating characters to character classes");
      println("   */");
      KotlinCountEmitter e = new KotlinCountEmitter("cmap_top");
      e.emitInit();
      e.emitCountValueString(tables.fst);
      e.emitUnpack();
      println(e.toString());

      println("");
      println("  /**");
      println("   * Second-level tables for translating characters to character classes");
      println("   */");
      e = new KotlinCountEmitter("cmap_blocks");
      e.emitInit();
      e.emitCountValueString(tables.snd);
      e.emitUnpack();
      println(e.toString());
    }
  }

  private void emitRowMapArray() {
    println("");
    println("  /**");
    println("   * Translates a state to a row index in the transition table");
    println("   */");

    KotlinHiLowEmitter e = new KotlinHiLowEmitter("RowMap");
    e.emitInit();
    for (int i = 0; i < dfa.numStates(); i++) {
      e.emit(rowMap[i] * numCols);
    }
    e.emitUnpack();
    println(e.toString());
  }

  private void emitAttributes() {
    // TODO(lsf): refactor to use KotlinCountEmitter.emitCountValueString
    println("  /**");
    println("   * ZZ_ATTRIBUTE[aState] contains the attributes of state {@code aState}");
    println("   */");

    KotlinCountEmitter e = new KotlinCountEmitter("Attribute");
    e.emitInit();

    int count = 1;
    int value = 0;
    if (dfa.isFinal(0)) {
      value = FINAL;
    }
    if (!isTransition[0]) {
      value |= NOLOOK;
    }

    for (int i = 1; i < dfa.numStates(); i++) {
      int attribute = 0;
      if (dfa.isFinal(i)) {
        attribute = FINAL;
      }
      if (!isTransition[i]) {
        attribute |= NOLOOK;
      }

      if (value == attribute) {
        count++;
      } else {
        e.emit(count, value);
        count = 1;
        value = attribute;
      }
    }

    e.emit(count, value);
    e.emitUnpack();

    println(e.toString());
  }

  private void emitClassCode() {
    if (scanner.classCode() != null) {
      println("  /* user code: */");
      println(scanner.classCode());
    }

    if (scanner.cup2Compatible()) {
      // convenience methods for CUP2
      println();
      println("  /* CUP2 code: */");
      println("  private fun <T> token(terminal: Terminal, value: T): ScannerToken<T> {");
      println("    return ScannerToken<T>(terminal, value, yyline, yycolumn);");
      println("  }");
      println();
      println("  private fun token(terminal: Terminal): ScannerToken<Object> {");
      println("    return ScannerToken<Object>(terminal, yyline, yycolumn);");
      println("  }");
      println();
    }
  }

  private void emitConstructorDecl() {
    emitConstructorDecl(true);

    if ((scanner.standalone() || scanner.debugOption()) && scanner.ctorArgsCount() > 0) {
      Out.warning(ErrorMessages.CTOR_DEBUG);
      println();
      emitConstructorDecl(false);
    }
  }

  private void emitConstructorDecl(boolean printCtorArgs) {
    println("  /**");
    println("   * Creates a new scanner");
    println("   *");
    println("   * @param   in  the java.io.Reader to read input from.");
    println("   */");

    String warn =
        "// WARNING: this is a default constructor for "
            + "debug/standalone only. Has no custom parameters or init code.";

    if (!printCtorArgs) {
      println(warn);
    }

    if (scanner.initThrow() != null && printCtorArgs) {
      println("  @Throws(" + scanner.initThrow() + "::class)");
    }

    print("  constructor (input: kotlinx.io.Source");
    if (printCtorArgs) {
      emitCtorArgs();
    }
    print(")");

    println(" {");

    if (scanner.initCode() != null && printCtorArgs) {
      print("  ");
      print(scanner.initCode());
    }

    println("    this.zzReader = input;");

    println("  }");
    println();
  }

  private void emitCtorArgs() {
    for (int i = 0; i < scanner.ctorArgsCount(); i++) {
      print(", " + scanner.ctorType(i));
      print(" " + scanner.ctorArg(i));
    }
  }

  private void emitDoEOF() {
    if (eofCode == null) {
      return;
    }

    println("  /**");
    println("   * Contains user EOF-code, which will be executed exactly once,");
    println("   * when the end of file is reached");
    println("   */");

    if (eofThrow != null) {
      println("  @Throws(" + eofThrow + "::class)");
    }
    println("  private fun zzDoEOF() {");

    println("    if (!zzEOFDone) {");
    println("      zzEOFDone = true;");
    println("    ");
    println(/*    */ eofCode);
    println("    }");
    println("  }");
    println("");
  }

  private void emitLexFunctHeader(String functionName) {
    print("  @Throws(IOException::class, ");

    if (!scanner.lexThrow().isEmpty() || scanner.scanErrorException() != null) {
      for (String thrown : scanner.lexThrow()) {
        print(thrown);
        print("::class, ");
      }

      if (scanner.scanErrorException() != null) {
        print(scanner.scanErrorException());
        print("::class, ");
      }
    }
    println(")");

    if (scanner.cupCompatible()
        || scanner.cup2Compatible()
        || Objects.equals(scanner.isImplementing(), "java_cup.runtime.Scanner")
        || functionName.equals("advance")) {
      print("  override ");
    } else {
      print("  " + visibility + " ");
    }

    print("fun ");

    print(functionName);

    print("()");

    if (scanner.tokenType() == null) {
      if (scanner.isInteger()) {
        print(": Int?");
      } else if (scanner.isIntWrap()) print(": Integer?");
      else print(": Yytoken?");
    } else print(": " + scanner.tokenType() + "?");

    println("\n  {");

    skel.emitNext(); // 11

    println("    var zzTransL: IntArray = ZZ_TRANS;");
    println("    var zzRowMapL: IntArray = ZZ_ROWMAP;");
    println("    var zzAttrL: IntArray = ZZ_ATTRIBUTE;");

    skel.emitNext(); // 12

    if (scanner.charCount()) {
      println("      yychar+= zzMarkedPosL-zzStartRead;");
      println("");
    }

    if (scanner.lineCount() || scanner.columnCount()) {
      println("      var zzR: Boolean = false;");
      println("      var zzCh: Int;");
      println("      var zzCharCount: Int = 0");
      println("      zzCurrentPosL = zzStartRead");
      println("      while (zzCurrentPosL + zzCharCount < zzMarkedPosL) {");
      println("        zzCurrentPosL += zzCharCount");
      println("        zzCh = Character.codePointAt(zzBufferL, zzCurrentPosL);");
      println("        zzCharCount = Character.charCount(zzCh);");
      println("        when (zzCh.toChar()) {");
      println("         '\\u000B', '\\u000C', '\\u0085', '\\u2028', '\\u2029' -> {");
      if (scanner.lineCount()) println("          yyline++;");
      if (scanner.columnCount()) println("          yycolumn = 0;");
      println("          zzR = false;");
      println("          }");
      println("         '\\r' -> {");
      if (scanner.lineCount()) println("          yyline++;");
      if (scanner.columnCount()) println("          yycolumn = 0;");
      println("          zzR = true;");
      println("          }");
      println("         '\\n' -> {");
      println("          if (zzR)");
      println("            zzR = false;");
      println("          else {");
      if (scanner.lineCount()) println("            yyline++;");
      if (scanner.columnCount()) println("            yycolumn = 0;");
      println("          }");
      println("          }");
      println("        else -> {");
      println("          zzR = false;");
      if (scanner.columnCount()) println("             yycolumn += zzCharCount;");
      println("          }");
      println("        }");
      println("      }");
      println();

      if (scanner.lineCount()) {
        println("      if (zzR) {");
        println("        // peek one character ahead if it is");
        println("        // (if we have counted one line too much)");
        println("        var zzPeek: Boolean;");
        println("        if (zzMarkedPosL < zzEndReadL)");
        println("          zzPeek = zzBufferL.charAt(zzMarkedPosL) == '\\n';");
        println("        else if (zzAtEOF)");
        println("          zzPeek = false;");
        println("        else {");
        println("          var eof: Boolean = zzRefill();");
        println("          zzEndReadL = zzEndRead;");
        println("          zzMarkedPosL = zzMarkedPos;");
        println("          zzBufferL = zzBuffer;");
        println("          if (eof)");
        println("            zzPeek = false;");
        println("          else");
        println("            zzPeek = zzBufferL.charAt(zzMarkedPosL) == '\\n';");
        println("        }");
        println("        if (zzPeek) yyline--;");
        println("      }");
      }
    }

    if (scanner.bolUsed()) {
      // zzMarkedPos > zzStartRead <=> last match was not empty
      // if match was empty, last value of zzAtBOL can be used
      // zzStartRead is always >= 0
      println("      if (zzMarkedPosL > zzStartRead) {");
      println("        when (zzBufferL.charAt(zzMarkedPosL-1)) {");
      println("         '\\n' -> {}");
      println("         '\\u000B' -> {}  // fall through");
      println("         '\\u000C' -> {}  // fall through");
      println("         '\\u0085' -> {}  // fall through");
      println("         '\\u2028' -> {}  // fall through");
      println("         '\\u2029' -> {  // fall through");
      println("          zzAtBOL = true;");
      println("          }");
      println("         '\\r' -> {");
      println("          if (zzMarkedPosL < zzEndReadL)");
      println("            zzAtBOL = zzBufferL.charAt(zzMarkedPosL) != '\\n';");
      println("          else if (zzAtEOF)");
      println("            zzAtBOL = false;");
      println("          else {");
      println("            var eof: Boolean = zzRefill();");
      println("            zzMarkedPosL = zzMarkedPos;");
      println("            zzEndReadL = zzEndRead;");
      println("            zzBufferL = zzBuffer;");
      println("            if (eof) ");
      println("              zzAtBOL = false;");
      println("            else ");
      println("              zzAtBOL = zzBufferL.charAt(zzMarkedPosL) != '\\n';");
      println("          }");
      println("          }");
      println("        else -> {");
      println("            zzAtBOL = false;");
      println("          }");
      println("        }");
      println("      }");
    }

    skel.emitNext(); // 13

    if (scanner.bolUsed()) {
      println("      if (zzAtBOL)");
      println("        zzState = ZZ_LEXSTATE[zzLexicalState+1];");
      println("      else");
      println("        zzState = ZZ_LEXSTATE[zzLexicalState];");
      println();
    } else {
      println("      zzState = ZZ_LEXSTATE[zzLexicalState];");
      println();
    }

    println("      // set up zzAction for empty match case:");
    println("      var zzAttributes: Int = zzAttrL[zzState];");
    println("      if ( (zzAttributes and 1) == 1 ) {");
    println("        zzAction = zzState;");
    println("      }");
    println();

    skel.emitNext(); // 14
  }

  private void emitCMapAccess() {
    println("  /**");
    println("   * Translates raw input code points to DFA table row");
    println("   */");
    println("  private fun zzCMap(input: Int): Int {");
    if (parser.getCharClasses().getMaxCharCode() <= 0xFF) {
      println("    return ZZ_CMAP[input];");
    } else {
      println("    var offset: Int = input and " + (CMapBlock.BLOCK_SIZE - 1) + ";");
      println(
          "    return if(offset == input)"
              + " ZZ_CMAP_BLOCKS[offset]"
              + " else"
              + " ZZ_CMAP_BLOCKS[ZZ_CMAP_TOP[input shr "
              + CMapBlock.BLOCK_BITS
              + "] or offset];");
    }
    println("  }");
    println("");
  }

  private void emitGetRowMapNext() {
    println("          var zzNext: Int = zzTransL[ zzRowMapL[zzState] + zzCMap(zzInput) ];");
    println("          if (zzNext == " + DFA.NO_TARGET + ") return@zzForAction;");
    println("          zzState = zzNext;");
    println();

    println("          zzAttributes = zzAttrL[zzState];");

    println("          if ( (zzAttributes and " + FINAL + ") == " + FINAL + " ) {");

    skel.emitNext(); // 15

    println(
        "            if ( (zzAttributes and "
            + NOLOOK
            + ") == "
            + NOLOOK
            + " ) return@zzForAction;");

    skel.emitNext(); // 16
  }

  /**
   * Escapes all " ' \ tabs and newlines
   *
   * @param s The string to escape
   * @return The escaped string
   */
  private static String escapify(String s) {
    StringBuilder result = new StringBuilder(s.length() * 2);

    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\'':
          result.append("\\\'");
          break;
        case '\"':
          result.append("\\\"");
          break;
        case '\\':
          result.append("\\\\");
          break;
        case '\t':
          result.append("\\t");
          break;
        case '\r':
          if (i + 1 == s.length() || s.charAt(i + 1) != '\n') result.append("\"+ZZ_NL+\"");
          break;
        case '\n':
          result.append("\"+ZZ_NL+\"");
          break;
        default:
          result.append(c);
      }
    }

    return result.toString();
  }

  /** emitActionTable. */
  private void emitActionTable() {
    int lastAction = 1;
    int count = 0;
    int value = 0;

    println("  /**");
    println("   * Translates DFA states to action switch labels.");
    println("   */");
    KotlinCountEmitter e = new KotlinCountEmitter("Action");
    e.emitInit();

    for (int i = 0; i < dfa.numStates(); i++) {
      int newVal = 0;
      if (dfa.isFinal(i)) {
        Action action = dfa.action(i);
        if (action.isEmittable()) {
          Integer stored = actionTable.get(action);
          if (stored == null) {
            stored = lastAction++;
            actionTable.put(action, stored);
          }
          newVal = stored;
        }
      }

      if (value == newVal) {
        count++;
      } else {
        if (count > 0) {
          e.emit(count, value);
        }
        count = 1;
        value = newVal;
      }
    }

    if (count > 0) {
      e.emit(count, value);
    }

    e.emitUnpack();
    println(e.toString());
  }

  private void emitTokenSizeLimit(String limit) {
    println();
    println(
        "  /** Returns the maximum size of the scanner buffer, which limits the size of tokens."
            + " */");
    println("  private fun zzMaxBufferLen(): Int {");
    if (limit == null) {
      println("    return Integer.MAX_VALUE;");
    } else {
      println("    return " + limit + ";");
    }
    println("  }");
    println();
    println("  /**  Whether the scanner buffer can grow to accommodate a larger token. */");
    println("  private fun zzCanGrow(): Boolean {");
    if (limit == null) {
      println("    return true;");
    } else {
      println("    return zzBuffer.size < " + limit + ";");
    }
    println("  }");
    println();
  }

  private void emitActions() {
    println("        when (if (zzAction < 0) zzAction else ZZ_ACTION[zzAction]) {");

    int i = actionTable.size() + 1;

    for (Map.Entry<Action, Integer> entry : actionTable.entrySet()) {
      Action action = entry.getKey();
      int label = entry.getValue();

      println("          " + label + " -> {");

      if (action.lookAhead() == Action.Kind.FIXED_BASE) {
        println("            // lookahead expression with fixed base length");
        println("            zzMarkedPos = Character.offsetByCodePoints");
        println("                (zzBufferL, zzStartRead, " + action.getLookLength() + ");");
      }

      if (action.lookAhead() == Action.Kind.FIXED_LOOK
          || action.lookAhead() == Action.Kind.FINITE_CHOICE) {
        println("            // lookahead expression with fixed lookahead length");
        println("                (zzBufferL, zzMarkedPos, -" + action.getLookLength() + ");");
      }

      if (action.lookAhead() == Action.Kind.GENERAL_LOOK) {
        println("            // general lookahead, find correct zzMarkedPos");
        println("            { var zzFState = " + dfa.entryState(action.getEntryState()) + ";");
        println("              var zzFPos = zzStartRead;");
        println("              if (zzFin.size <= zzBufferL.size) {");
        println("                zzFin = BooleanArray(zzBufferL.size+1);");
        println("              }");
        println("              var zzFinL = zzFin;");
        println("              while (zzFState != -1 && zzFPos < zzMarkedPos) {");
        println("                zzFinL[zzFPos] = ((zzAttrL[zzFState] and 1) == 1);");
        println("                zzInput = Character.codePointAt(zzBufferL, zzFPos);");
        println("                zzFPos += Character.charCount(zzInput);");
        println("                zzFState = zzTransL[ zzRowMapL[zzFState] + zzCMap(zzInput) ];");
        println("              }");
        println("              if (zzFState != -1) {");
        println("                zzFinL[zzFPos++] = ((zzAttrL[zzFState] and 1) == 1);");
        println("              }");
        println("              while (zzFPos <= zzMarkedPos) {");
        println("                zzFinL[zzFPos++] = false;");
        println("              }");
        println();
        println("              zzFState = " + dfa.entryState(action.getEntryState() + 1) + ";");
        println("              zzFPos = zzMarkedPos;");
        println("              while (!zzFinL[zzFPos] || (zzAttrL[zzFState] and 1) != 1) {");
        println("                zzInput = Character.codePointBefore(zzBufferL, zzFPos);");
        println("                zzFPos -= Character.charCount(zzInput);");
        println("                zzFState = zzTransL[ zzRowMapL[zzFState] + zzCMap(zzInput) ];");
        println("              };");
        println("              zzMarkedPos = zzFPos;");
        println("            }");
      }

      if (scanner.debugOption()) {
        print("            println(");
        if (scanner.lineCount()) print("\"line: \"+(yyline+1)+\" \"+");
        if (scanner.columnCount()) print("\"col: \"+(yycolumn+1)+\" \"+");
        if (scanner.charCount()) print("\"char: \"+yychar+\" \"+");
        println("\"match: --\"+zzToPrintable(yytext())+\"--\");");
        print("            println(\"action [" + action.priority + "] { ");
        print(escapify(action.content));
        println(" }\");");
      }

      println("            " + action.content);
      println("            }");
      println("          // fall through");
      println("          " + (i++) + " -> break;");
    }
  }

  private void emitEOFVal() {
    EOFActions eofActions = parser.getEOFActions();

    if (eofCode != null) {
      println("            zzDoEOF();");
    }

    if (eofActions.numActions() > 0) {
      println("            when (zzLexicalState) {");

      // pick a start value for break case labels.
      // must be larger than any value of a lex state:
      int last = dfa.numStates();

      for (String name : scanner.stateNames()) {
        int num = scanner.getStateNumber(name);
        Action action = eofActions.getAction(num);

        if (action != null) {
          println("            " + name + " -> {");
          if (scanner.debugOption()) {
            print("              println(");
            if (scanner.lineCount()) print("\"line: \"+(yyline+1)+\" \"+");
            if (scanner.columnCount()) print("\"col: \"+(yycolumn+1)+\" \"+");
            if (scanner.charCount()) print("\"char: \"+yychar+\" \"+");
            println("\"match: <<EOF>>\");");
            print("              println(\"action [" + action.priority + "] { ");
            print(escapify(action.content));
            println(" }\");");
          }
          println("              " + action.content);
          println("            }  // fall though");
          println("            " + (++last) + " -> break;");
        }
      }

      println("            else -> {");
    }

    Action defaultAction = eofActions.getDefault();

    if (defaultAction != null) {
      if (scanner.debugOption()) {
        print("                println(");
        if (scanner.lineCount()) print("\"line: \"+(yyline+1)+\" \"+");
        if (scanner.columnCount()) print("\"col: \"+(yycolumn+1)+\" \"+");
        if (scanner.charCount()) print("\"char: \"+yychar+\" \"+");
        println("\"match: <<EOF>>\");");
        print("                println(\"action [" + defaultAction.priority + "] { ");
        print(escapify(defaultAction.content));
        println(" }\");");
      }
      println("                " + defaultAction.content);
    } else if (scanner.eofVal() != null) println(scanner.eofVal());
    else if (scanner.isInteger()) {
      if (scanner.tokenType() != null) {
        Out.error(ErrorMessages.INT_AND_TYPE);
        throw new GeneratorException();
      }
      println("        return YYEOF;");
    } else println("        return null;");

    if (eofActions.numActions() > 0) {
      println("              }");
      println("        }");
    }
  }

  private void findActionStates() {
    isTransition = new boolean[dfa.numStates()];

    for (int i = 0; i < dfa.numStates(); i++) {
      char j = 0;
      while (!isTransition[i] && j < dfa.numInput())
        isTransition[i] = dfa.table(i, j++) != DFA.NO_TARGET;
    }
  }

  private void reduceColumns() {
    colMap = new int[dfa.numInput()];
    colKilled = new boolean[dfa.numInput()];

    int i, j, k;
    int translate = 0;
    boolean equal;

    numCols = dfa.numInput();

    for (i = 0; i < dfa.numInput(); i++) {

      colMap[i] = i - translate;

      for (j = 0; j < i; j++) {

        // test for equality:
        k = -1;
        equal = true;
        while (equal && ++k < dfa.numStates()) equal = dfa.table(k, i) == dfa.table(k, j);

        if (equal) {
          translate++;
          colMap[i] = colMap[j];
          colKilled[i] = true;
          numCols--;
          break;
        } // if
      } // for j
    } // for i
  }

  private void reduceRows() {
    rowMap = new int[dfa.numStates()];
    rowKilled = new boolean[dfa.numStates()];

    int i, j, k;
    int translate = 0;
    boolean equal;

    // i is the state to add to the new table
    for (i = 0; i < dfa.numStates(); i++) {

      rowMap[i] = i - translate;

      // check if state i can be removed (i.e. already
      // exists in entries 0..i-1)
      for (j = 0; j < i; j++) {

        // test for equality:
        k = -1;
        equal = true;
        while (equal && ++k < dfa.numInput()) equal = dfa.table(i, k) == dfa.table(j, k);

        if (equal) {
          translate++;
          rowMap[i] = rowMap[j];
          rowKilled[i] = true;
          break;
        } // if
      } // for j
    } // for i
  }

  /** Set up EOF code section according to scanner.eofcode */
  private void setupEOFCode() {
    if (scanner.eofclose()) {
      eofCode = LexScan.conc(scanner.eofCode(), "  yyclose();");
      eofThrow = LexScan.concExc(scanner.eofThrow(), "java.io.IOException");
    } else {
      eofCode = scanner.eofCode();
      eofThrow = scanner.eofThrow();
    }
  }

  /**
   * Emit {@code yychar}, {@code yycolumn}, {@code zzAtBOL}, {@code zzEOFDone} with warning
   * suppression when needed.
   */
  private void emitVarDefs() {
    // We can't leave out these declarations completely, even if unused, because
    // the reset functions in the skeleton refer to them. They are written to,
    // but not read. Only other option would be to pull these out of the skeleton
    // as well.

    println("  /** Number of newlines encountered up to the start of the matched text. */");
    if (!scanner.lineCount()) {
      println("  @SuppressWarnings(\"unused\")");
    }
    println("  private var yyline: Int = 0;");
    println();
    println(
        "  /** Number of characters from the last newline up to the start of the matched text. */");
    if (!scanner.columnCount()) {
      println("  @SuppressWarnings(\"unused\")");
    }
    println("  protected var yycolumn: Int = 0;");
    println();
    println("  /** Number of characters up to the start of the matched text. */");
    if (!scanner.charCount()) {
      println("  @SuppressWarnings(\"unused\")");
    }
    println("  private var yychar: Long = 0;");
    println();
    println("  /** Whether the scanner is currently at the beginning of a line. */");
    if (!scanner.bolUsed()) {
      println("  @SuppressWarnings(\"unused\")");
    }
    println("  private var zzAtBOL: Boolean = false;");
    println();
    println("  /** Whether the user-EOF-code has already been executed. */");
    if (eofCode == null) {
      println("  @SuppressWarnings(\"unused\")");
    }
    println("  private var zzEOFDone: Boolean = false;");
    println();
  }

  /** Main Emitter method. */
  public void emit() {
    String functionName = (scanner.functionName() != null) ? scanner.functionName() : "yylex";

    setupEOFCode();

    reduceColumns();
    findActionStates();

    emitHeader();
    emitUserCode();
    emitClassName();

    // must be placed in companion object
    skel.emitNext(); // 1

    println("  private var ZZ_BUFFERSIZE: Int = " + scanner.bufferSize());

    if (scanner.debugOption()) {
      println("  private var ZZ_NL: String = System.getProperty(\"line.separator\")");
    }

    skel.emitNext(); // 2

    emitLexicalStates();

    emitCharMapTables();

    emitActionTable();

    reduceRows();

    emitRowMapArray();

    emitDynamicInit();

    skel.emitNext(); // 3

    emitAttributes();

    emitCMapAccess();

    emitScanError();

    emitMain(functionName);

    // end of companion object

    skel.emitNext(); // 4

    if (scanner.cupDebug()) {
      emitTokenDebug(functionName);
    }

    emitLookBuffer();

    emitVarDefs();

    emitClassCode();

    skel.emitNext(); // 5

    emitConstructorDecl();

    if (scanner.debugOption()) {
      println("");
      println("  private fun zzToPrintable(CharSequence str): String {");
      println("    val builder = StringBuilder();");
      println("    var n = 0");
      println("    while (n < str.length) {");
      println("      var ch: Int = Character.codePointAt(str, n);");
      println("      var charCount: Int = Character.charCount(ch);");
      println("      n += charCount;");
      println("      if (ch > 31 && ch < 127) {");
      println("        builder.append(ch.toChar());");
      println("      } else if (charCount == 1) {");
      println("        builder.append(String.format(\"\\\\u%04X\", ch));");
      println("      } else {");
      println("        builder.append(String.format(\"\\\\U%06X\", ch));");
      println("      }");
      println("    }");
      println("    return builder.toString();");
      println("  }");
    }

    emitTokenSizeLimit(scanner.getTokenSizeLimit());

    skel.emitNext(); // 6

    emitPushback();

    emitDoEOF();

    skel.emitNext(); // 10

    emitLexFunctHeader(functionName);

    emitNextInput();

    emitGetRowMapNext();

    skel.emitNext(); // 17

    emitEOFVal();

    skel.emitNext(); // 18

    emitActions();

    skel.emitNext(); // 19

    emitNoMatch();

    skel.emitNext(); // 20

    // closing
    skel.emitNext(); // 21

    out.close();
  }
}
