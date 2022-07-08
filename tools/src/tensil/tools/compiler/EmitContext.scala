/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.tools.compiler

case class EmitContext(
    scheduler: Scheduler,
    mm: MemoryManager,
    outputNames: Seq[String]
)
