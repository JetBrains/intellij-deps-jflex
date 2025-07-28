/*
 * Copyright (C) 1998-2023  Gerwin Klein <lsf@jflex.de>
 * SPDX-License-Identifier: BSD-3-Clause
 */

package jflex.generator;

/**
 * An emitter for an array encoded as count/value pairs in a string where values can be in [0,
 * 0xFFFF_FFFF].
 *
 * @author Gerwin Klein
 * @version JFlex 1.10.0
 */
public class KotlinHiCountEmitter extends KotlinCountEmitter {

  /**
   * Create a count/value emitter for a specific field.
   *
   * @param name name of the generated array
   */
  protected KotlinHiCountEmitter(String name, int translate) {
    super(name, translate);
  }

  /**
   * Emits count/value unpacking code for the generated array.
   *
   * @see KotlinPackEmitter#emitUnpack()
   */
  @Override
  public void emitUnpackChunk() {
    println("  @JvmStatic");
    println(
        "  private static int zzUnpack" + name + "(String packed, int offset, int [] result) {");
    println("    var i: Int = 0       /* index in packed string  */");
    println("    var j: Int = offset  /* index in unpacked array */");
    println("    val l: Int = packed.length() - 2 /* reading 3 chars per entry */");
    println("    while (i < l) {");
    println("      var count = packed[i++].code");
    println("      var high = packed[i++].code shl 16");
    println("      var value = high | packed[i++].code");
    if (translate == 1) {
      println("      value--");
    } else if (translate != 0) {
      println("      value-= " + translate);
    }
    println("      do { result[j++] = value } while (--count > 0)");
    println("    }");
    println("    return j");
    println("  }");
  }

  /**
   * Emits a single value to the current string chunk. Accepted range is [0, 0xFFFF_FFFF]
   *
   * @param val the integer value to emit
   */
  @Override
  protected void emitValue(int val) {
    emitUC(val >> 16);
    emitUC(val & 0xFFFF);
  }
}
