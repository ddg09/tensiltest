/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright © 2019-2022 Tensil AI Company */

package tensil.zynq.tcu

import chisel3._
import chisel3.experimental.FixedPoint
import tensil.{PlatformConfig, axi}
import tensil.axi.{
  AXI4Stream,
  connectDownstreamInterface,
  connectUpstreamInterface
}
import tensil.mem.MemoryImplementation
import tensil.tcu.TCUOptions
import tensil.{
  ArchitectureDataType,
  Architecture,
  TablePrinter,
  InstructionLayout,
  ArtifactsLogger
}
import java.io.File

case class Args(
    archFile: File = new File("."),
    targetDir: File = new File("."),
    dramAxiConfig: axi.Config = axi.Config.Xilinx64,
    summary: Boolean = false,
    sampleBlockSize: Int = 0,
    decoderTimeout: Int = 100,
    validateInstructions: Boolean = false,
    enableStatus: Boolean = false,
    useXilinxUltraRAM: Boolean = false,
)

class Top(
    archName: String,
    arch: Architecture,
    options: AXIWrapperTCUOptions,
    printSummary: Boolean
) extends RawModule {
  override def desiredName: String = s"top_${archName}"

  val gen = arch.dataType match {
    case ArchitectureDataType.FP8BP4   => FixedPoint(8.W, 4.BP)
    case ArchitectureDataType.FP16BP8  => FixedPoint(16.W, 8.BP)
    case ArchitectureDataType.FP18BP10 => FixedPoint(18.W, 10.BP)
    case ArchitectureDataType.FP32BP16 => FixedPoint(32.W, 16.BP)
    case dataType =>
      throw new Exception(s"${dataType} not supported")
  }
  val layout = new InstructionLayout(arch)

  val clock       = IO(Input(Clock()))
  val reset       = IO(Input(Bool()))
  val instruction = IO(Flipped(new AXI4Stream(options.dramAxiConfig.dataWidth)))
  val m_axi_dram0 = IO(new axi.ExternalMaster(options.dramAxiConfig))
  val m_axi_dram1 = IO(new axi.ExternalMaster(options.dramAxiConfig))

  val sample =
    if (options.inner.enableSample) Some(IO(new AXI4Stream(64))) else None
  val status =
    if (options.inner.enableStatus)
      Some(IO(new AXI4Stream(layout.instructionSizeBytes * 8)))
    else None

  implicit val platformConfig =
    PlatformConfig(
      localMemImpl = options.localMemImpl,
      accumulatorMemImpl = options.accumulatorMemImpl,
      dramAxiConfig = options.dramAxiConfig
    )

  withClockAndReset(clock, if (options.resetActiveLow) !reset else reset) {
    val tcu = Module(
      new AXIWrapperTCU(
        gen,
        layout,
        options
      )
    )

    connectDownstreamInterface(instruction, tcu.instruction, tcu.error)

    if (options.inner.enableStatus) {
      connectUpstreamInterface(tcu.status, status.get, tcu.error)
    } else {
      tcu.status.tieOffFlipped()
    }

    if (options.inner.enableSample) {
      connectUpstreamInterface(tcu.sample, sample.get, tcu.error)
    } else {
      tcu.sample.tieOffFlipped()
    }

    m_axi_dram0.connectMaster(tcu.dram0)
    m_axi_dram1.connectMaster(tcu.dram1)
  }

  ArtifactsLogger.log(desiredName)

  if (printSummary) {
    val tb = new TablePrinter(Some("RTL SUMMARY"))
    layout.addTableLines(tb)

    print(tb)
  }
}

object Top extends App {
  val argParser = new scopt.OptionParser[Args]("rtl") {
    help("help").text("Prints this usage text")

    opt[File]('a', "arch")
      .required()
      .valueName("<file>")
      .action((x, c) => c.copy(archFile = x))
      .text("Tensil architecture descrition (.tarch) file")

    opt[File]('t', "target")
      .valueName("<dir>")
      .action((x, c) => c.copy(targetDir = x))
      .text("Optional target directory")

    opt[Int]('d', "dram-axi-width")
      .valueName("32|64|128|256")
      .validate(x =>
        if (Seq(32, 64, 128, 256, 512, 1024).contains(x)) success
        else failure("Value must be 32, 64, 128, 256, 512 or 1024")
      )
      .action((x, c) =>
        c.copy(dramAxiConfig = x match {
          case 32   => axi.Config.Xilinx
          case 64   => axi.Config.Xilinx64
          case 128  => axi.Config.Xilinx128
          case 256  => axi.Config.Xilinx256
          case 512  => axi.Config.Xilinx512
          case 1024 => axi.Config.Xilinx1024
        })
      )
      .text("Optional DRAM0 and DRAM1 AXI width, defaults to 64")

    opt[Boolean]('s', "summary")
      .valueName("true|false")
      .action((x, c) => c.copy(summary = x))

    opt[Int]("sample-block-size")
      .valueName("<integer>")
      .action((x, c) => c.copy(sampleBlockSize = x))
      .text("Performance sample block size, defaults to 0 (disabled)")

    opt[Int]("decoder-timeout")
      .valueName("<integer>")
      .action((x, c) => c.copy(decoderTimeout = x))
      .text("Decoder timeout, defaults to 100")

    opt[Boolean]("validate-instructions")
      .valueName("true|false")
      .action((x, c) => c.copy(validateInstructions = x))
      .text("Validate instructions, defaults to false")

    opt[Boolean]("enable-status")
      .valueName("true|false")
      .action((x, c) => c.copy(enableStatus = x))
      .text("Enable status port, defaults to false")

    opt[Boolean]("use-xilinx-ultra-ram")
      .valueName("true|false")
      .action((x, c) => c.copy(useXilinxUltraRAM = x))
      .text("Use Xilinx Ultra RAM for local memory and BRAM for accumulators")
  }

  argParser.parse(args, Args()) match {
    case Some(args) =>
      val arch     = Architecture.read(args.archFile)
      val archName = args.archFile.getName().split("\\.")(0)

      val options = AXIWrapperTCUOptions(
        inner = TCUOptions(
          sampleBlockSize = args.sampleBlockSize,
          decoderTimeout = args.decoderTimeout,
          validateInstructions = args.validateInstructions,
          enableStatus = args.enableStatus,
        ),
        accumulatorMemImpl =
          if (args.useXilinxUltraRAM) MemoryImplementation.XilinxBRAMMacro
          else MemoryImplementation.BlockRAM,
        localMemImpl =
          if (args.useXilinxUltraRAM) MemoryImplementation.XilinxURAMMacro
          else MemoryImplementation.BlockRAM,
        dramAxiConfig = args.dramAxiConfig
      )

      tensil.util.emitTo(
        new Top(archName, arch, options, args.summary),
        args.targetDir.getCanonicalPath()
      )

      val archParamsFileName =
        s"${args.targetDir.getCanonicalPath()}/architecture_params.h"
      arch.writeDriverArchitectureParams(archParamsFileName)

      val tb = new TablePrinter(Some("ARTIFACTS"))

      for (artifact <- ArtifactsLogger.artifacts)
        tb.addNamedLine(
          s"Verilog $artifact",
          new File(s"${args.targetDir.getCanonicalPath()}/${artifact}.v")
            .getCanonicalPath()
        )

      tb.addNamedLine(
        "Driver parameters C header",
        new File(archParamsFileName).getCanonicalPath()
      )

      print(tb)

    case _ =>
      sys.exit(1)
  }
}
