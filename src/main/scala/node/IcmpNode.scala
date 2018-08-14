package node

import chisel3._
import chisel3.iotesters.{ChiselFlatSpec, Driver, OrderedDecoupledHWIOTester, PeekPokeTester}
import chisel3.Module
import chisel3.testers._
import chisel3.util._
import org.scalatest.{FlatSpec, Matchers}
import config._
import interfaces._
import muxes._
import util._
import utility.UniformPrintfs

class IcmpNodeIO(NumOuts: Int)
                (implicit p: Parameters)
  extends HandShakingIONPS(NumOuts)(new DataBundle) {
  // LeftIO: Left input data for computation
  val LeftIO = Flipped(Decoupled(new DataBundle))

  // RightIO: Right input data for computation
  val RightIO = Flipped(Decoupled(new DataBundle))
}

class IcmpNode(NumOuts: Int, ID: Int, opCode: String)
              (sign: Boolean)
              (implicit p: Parameters,
               name: sourcecode.Name,
               file: sourcecode.File)

  extends HandShakingNPS(NumOuts, ID)(new DataBundle)(p) {
  override lazy val io = IO(new ComputeNodeIO(NumOuts))
  // Printf debugging
  val node_name = name.value
  val module_name = file.value.split("/").tail.last.split("\\.").head.capitalize

  override val printfSigil = "[" + module_name + "] " + node_name + ": " + ID + " "
  val (cycleCount, _) = Counter(true.B, 32 * 1024)

  /*===========================================*
   *            Registers                      *
   *===========================================*/
  // Left Input
  val left_R = RegInit(DataBundle.default)
  val left_valid_R = RegInit(false.B)

  // Right Input
  val right_R = RegInit(DataBundle.default)
  val right_valid_R = RegInit(false.B)

  val task_ID_R = RegNext(next = enable_R.taskID)

  // Output register
  val out_data_R = RegInit(DataBundle.default)

  val s_IDLE :: s_COMPUTE :: Nil = Enum(2)
  val state = RegInit(s_IDLE)

  /*==========================================*
   *           Predicate Evaluation           *
   *==========================================*/

  val predicate = left_R.predicate & right_R.predicate //& IsEnable()

  /*===============================================*
   *            Latch inputs. Wire up output       *
   *===============================================*/

  val FU = Module(new UCMP(xlen, opCode))
  FU.io.in1 := left_R.data
  FU.io.in2 := right_R.data

  io.LeftIO.ready := ~left_valid_R
  when(io.LeftIO.fire()) {
    left_R <> io.LeftIO.bits
    left_valid_R := true.B
  }

  io.RightIO.ready := ~right_valid_R
  when(io.RightIO.fire()) {
    right_R <> io.RightIO.bits
    right_valid_R := true.B
  }

  // Wire up Outputs
  for (i <- 0 until NumOuts) {
    io.Out(i).bits := out_data_R
  }


  /*============================================*
   *            ACTIONS (possibly dangerous)    *
   *============================================*/

  switch(state) {
    is(s_IDLE) {
      when(enable_valid_R) {
        when(left_valid_R && right_valid_R) {
          ValidOut()
          when(enable_R.control) {
            out_data_R.data := FU.io.out
            out_data_R.predicate := predicate
            out_data_R.taskID := left_R.taskID | right_R.taskID
          }
          state := s_COMPUTE
        }
      }

    }
    is(s_COMPUTE) {
      when(IsOutReady()) {
        // Reset data
        left_R := DataBundle.default
        right_R := DataBundle.default
        left_valid_R := false.B
        right_valid_R := false.B
        //Reset state
        state := s_IDLE
        out_data_R.predicate := false.B
        //Reset output
        Reset()
        printf("[LOG] " + "[" + module_name + "] " + "[TID->%d] " + node_name + ": Output fired @ %d, Value: %d\n", task_ID_R, cycleCount, FU.io.out)
      }
    }
  }

}


//class IcmpFastNode(NumOuts: Int, ID: Int, opCode: String)
//                  (sign: Boolean)
//                  (implicit val p: Parameters,
//                   name: sourcecode.Name,
//                   file: sourcecode.File)
//  extends Module with CoreParams with UniformPrintfs {
//
//  val io = IO(new Bundle {
//    //Control Signal
//    val enable = Flipped(Decoupled(new ControlBundle))
//
//    //Input data
//    val LeftIO = Flipped(Decoupled(new DataBundle()))
//    val RightIO = Flipped(Decoupled(new DataBundle()))
//
//    val Out = Decoupled(new DataBundle)
//  })
//
//  // Printf debugging
//  val node_name = name.value
//  val module_name = file.value.split("/").tail.last.split("\\.").head.capitalize
//
//  override val printfSigil = "[" + module_name + "] " + node_name + ": " + ID + " "
//  val (cycleCount, _) = Counter(true.B, 32 * 1024)
//
//  /*===========================================*
//   *            Registers                      *
//   *===========================================*/
//  // Left Input
//  val left_R = RegInit(DataBundle.default)
//  val left_valid_R = RegInit(false.B)
//
//  // Right Input
//  val right_R = RegInit(DataBundle.default)
//  val right_valid_R = RegInit(false.B)
//
//  val enable_R = RegInit(ControlBundle.default)
//  val enable_valid_R = RegInit(false.B)
//
//  val task_ID_R = RegNext(next = enable_R.taskID)
//
//  val predicate = left_R.predicate & right_R.predicate & enable_R.control
//
//  /*===============================================*
//   *            Latch inputs. Wire up output       *
//   *===============================================*/
//
//
//  val FU = Module(new UCMP(xlen, opCode))
//
//  val left_input  = (io.LeftIO.bits.data  & Fill(xlen, io.LeftIO.valid)) | (left_R.data & Fill(xlen, left_valid_R))
//  val right_input = (io.RightIO.bits.data & Fill(xlen, io.RightIO.valid)) | (right_R.data & Fill(xlen, right_valid_R))
//  val enable_input = (io.enable.bits.control & Fill(xlen, io.enable.valid)) | (enable_R.control & Fill(xlen, enable_valid_R))
//
//  val output_valid_W = WireInit(false.B)
//
//  FU.io.in1 := left_input
//  FU.io.in2 := right_input
//
//  io.LeftIO.ready := ~left_valid_R
//  when(io.LeftIO.fire()) {
//    left_R <> io.LeftIO.bits
//    left_valid_R := true.B
//  }
//
//  io.RightIO.ready := ~right_valid_R
//  when(io.RightIO.fire()) {
//    right_R <> io.RightIO.bits
//    right_valid_R := true.B
//  }
//
//  io.enable.ready := ~enable_valid_R
//  when(io.enable.fire()) {
//    enable_R <> io.enable.bits
//    enable_valid_R := true.B
//  }
//
//  //Pipeline registers
//  val output_R = RegNext(FU.io.out)
//  val output_valid_R = RegNext(output_valid_W)
//
//
//  // Defalut values
//  io.Out.valid := output_valid_R
//  io.Out.bits.taskID := 0.U
//  io.Out.bits.data := output_R
//  io.Out.bits.predicate := enable_input
//
//
//  /*============================================*
//   *            ACTIONS (possibly dangerous)    *
//   *============================================*/
//  val s_idle :: s_fire :: Nil = Enum(2)
//  val state = RegInit(s_idle)
//
//  switch(state) {
//    is(s_idle) {
//      io.Out.bits.data := FU.io.out
//      io.Out.bits.taskID := task_ID_R
//      io.Out.bits.predicate := predicate
//
//      output_valid_W := false.B
//
//      when(enable_valid_R || io.enable.fire) {
//        when((left_valid_R || io.LeftIO.fire) && (right_valid_R || io.RightIO.fire)) {
//
//          output_valid_W := true.B
//
//          when(io.Out.fire) {
//
//            left_R := DataBundle.default
//            left_valid_R := false.B
//
//            right_R := DataBundle.default
//            right_valid_R := false.B
//
//            enable_R := ControlBundle.default
//            enable_valid_R := false.B
//
//
//            printf("[LOG] " + "[" + module_name + "] " + "[TID->%d] "
//              + node_name + ": Output fired @ %d, Value: %d\n",
//              task_ID_R, cycleCount, FU.io.out)
//
//            state := s_idle
//          }.otherwise {
//
//            printf("[LOG] " + "[" + module_name + "] " + "[TID->%d] "
//              + node_name + ": Output fired @ %d, Value: %d\n",
//              task_ID_R, cycleCount, FU.io.out)
//
//            state := s_fire
//          }
//
//
//        }
//      }
//    }
//
//    is(s_fire) {
//      output_valid_W := true.B
//
//      when(io.Out.fire) {
//        left_R := DataBundle.default
//        left_valid_R := false.B
//
//        right_R := DataBundle.default
//        right_valid_R := false.B
//
//        enable_R := ControlBundle.default
//        enable_valid_R := false.B
//
//        state := s_idle
//      }
//    }
//  }
//
//}

class IcmpFastNode(NumOuts: Int, ID: Int, opCode: String)
                  (sign: Boolean)
                  (implicit val p: Parameters,
                   name: sourcecode.Name,
                   file: sourcecode.File)
  extends Module with CoreParams with UniformPrintfs {

  val io = IO(new Bundle {
    //Control Signal
    val enable = Flipped(Decoupled(new ControlBundle))

    //Input data
    val LeftIO = Flipped(Decoupled(new DataBundle()))
    val RightIO = Flipped(Decoupled(new DataBundle()))

    val Out = Vec(NumOuts, Decoupled(new DataBundle))
  })

  // Printf debugging
  val node_name = name.value
  val module_name = file.value.split("/").tail.last.split("\\.").head.capitalize

  override val printfSigil = "[" + module_name + "] " + node_name + ": " + ID + " "
  val (cycleCount, _) = Counter(true.B, 32 * 1024)

  /*===========================================*
   *            Registers                      *
   *===========================================*/
  // Left Input
  val left_R = RegInit(DataBundle.default)
  val left_valid_R = RegInit(false.B)

  // Right Input
  val right_R = RegInit(DataBundle.default)
  val right_valid_R = RegInit(false.B)

  val enable_R = RegInit(ControlBundle.default)
  val enable_valid_R = RegInit(false.B)

  val output_R = RegInit(DataBundle.default)
  val output_valid_R = Seq.fill(NumOuts)(RegInit(false.B))

  val fire_R = Seq.fill(NumOuts)(RegInit(false.B))

  val task_input = (io.enable.bits.taskID | enable_R.taskID)

  /*===============================================*
   *            Latch inputs. Wire up output       *
   *===============================================*/


  val left_input = (io.LeftIO.bits.data & Fill(xlen, io.LeftIO.valid)) | (left_R.data & Fill(xlen, left_valid_R))
  val left_predicate = (io.LeftIO.bits.predicate & Fill(xlen, io.LeftIO.valid)) | (left_R.predicate & Fill(xlen, left_valid_R))

  val right_input = (io.RightIO.bits.data & Fill(xlen, io.RightIO.valid)) | (right_R.data & Fill(xlen, right_valid_R))
  val right_predicate = (io.RightIO.bits.predicate & Fill(xlen, io.RightIO.valid)) | (right_R.predicate & Fill(xlen, right_valid_R))

  val enable_input = (io.enable.bits.control & Fill(xlen, io.enable.valid)) | (enable_R.control & Fill(xlen, enable_valid_R))

  //  val output_valid_W = WireInit(false.B)

  val FU = Module(new UCMP(xlen, opCode))
  FU.io.in1 := left_input
  FU.io.in2 := right_input

  io.LeftIO.ready := ~left_valid_R
  when(io.LeftIO.fire() && io.LeftIO.bits.predicate) {
    left_R <> io.LeftIO.bits
    left_valid_R := true.B
  }

  io.RightIO.ready := ~right_valid_R
  when(io.RightIO.fire() && io.RightIO.bits.predicate) {
    right_R <> io.RightIO.bits
    right_valid_R := true.B
  }

  io.enable.ready := ~enable_valid_R
  when(io.enable.fire()) {
    enable_R <> io.enable.bits
    enable_valid_R := true.B
  }

  // Defalut values for output

  val predicate = enable_input & right_predicate & left_predicate

  output_R.data := FU.io.out
  output_R.predicate := predicate
  output_R.taskID := task_input

  for (i <- 0 until NumOuts) {
    io.Out(i).bits <> output_R
    io.Out(i).valid <> output_valid_R(i)
  }

  for (i <- 0 until NumOuts) {
    when(io.Out(i).fire) {
      fire_R(i) := true.B
    }
  }

  val fire_mask = (fire_R zip io.Out.map(_.fire)).map { case (a, b) => a | b }


  /*============================================*
   *            ACTIONS (possibly dangerous)    *
   *============================================*/
  val s_idle :: s_fire :: Nil = Enum(2)
  val state = RegInit(s_idle)

  switch(state) {
    is(s_idle) {

      when((enable_valid_R || io.enable.fire)
        && (left_valid_R || io.LeftIO.fire)
        && (right_valid_R || io.RightIO.fire)) {

        output_valid_R.foreach(_ := true.B)

        state := s_fire

        printf("[LOG] " + "[" + module_name + "] " + "[TID->%d] "
          + node_name + ": Output fired @ %d, Value: %d (%d + %d)\n",
          task_input, cycleCount, FU.io.out, FU.io.in1, FU.io.in2)
      }
    }

    is(s_fire) {

      when(fire_mask.reduce(_ & _)) {

        left_R := DataBundle.default
        left_valid_R := false.B

        right_R := DataBundle.default
        right_valid_R := false.B

        enable_R := ControlBundle.default
        enable_valid_R := false.B

        output_R := DataBundle.default
        output_valid_R.foreach(_ := false.B)

        fire_R.foreach(_ := false.B)

        state := s_idle
      }
    }
  }

}


