/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

import tensil.tools.GraphPrinter
import tensil.tools.data.Shape

abstract class Frontend {
  def traverse(outputNames: Seq[String]): Seq[String]
  def rewrite(program: Seq[String]): Seq[Emitter]

  def mkConstsDimensions(shape: Shape): MemoryDimensions

  val graphPrinter: Option[GraphPrinter]
}
