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

class PhiNodeIO(NumInputs: Int, NumOuts: Int)
               (implicit p: Parameters)
  extends HandShakingIONPS(NumOuts)(new DataBundle) {

  // Vector input
  val InData = Vec(NumInputs, Flipped(Decoupled(new DataBundle)))

  // Predicate mask comming from the basic block
  val Mask = Flipped(Decoupled(UInt(NumInputs.W)))
}

abstract class PhiFastNodeIO(val NumInputs: Int = 2, val NumOutputs: Int = 1, val ID: Int)
                            (implicit val p: Parameters)
  extends Module with CoreParams with UniformPrintfs {

  val io = IO(new Bundle {
    //Control signal
    val enable = Flipped(Decoupled(new ControlBundle))

    // Vector input
    val InData = Vec(NumInputs, Flipped(Decoupled(new DataBundle)))

    // Predicate mask comming from the basic block
    val Mask = Flipped(Decoupled(UInt(NumInputs.W)))

    //Output
    val Out = Vec(NumOutputs, Decoupled(new DataBundle))
  })
}


@deprecated("Use PhiFastNode2 instead", "1.0")
class PhiNode(NumInputs: Int,
              NumOuts: Int,
              ID: Int)
             (implicit p: Parameters,
              name: sourcecode.Name,
              file: sourcecode.File)
  extends HandShakingNPS(NumOuts, ID)(new DataBundle)(p) {
  override lazy val io = IO(new PhiNodeIO(NumInputs, NumOuts))
  // Printf debugging
  val node_name = name.value
  val module_name = file.value.split("/").tail.last.split("\\.").head.capitalize

  override val printfSigil = "[" + module_name + "] " + node_name + ": " + ID + " "
  val (cycleCount, _) = Counter(true.B, 32 * 1024)

  /*===========================================*
   *            Registers                      *
   *===========================================*/
  // Data Inputs
  val in_data_R = RegInit(VecInit(Seq.fill(NumInputs)(DataBundle.default)))
  val in_data_valid_R = RegInit(VecInit(Seq.fill(NumInputs)(false.B)))

  val out_data_R = RegInit(DataBundle.default)

  // Mask Input
  val mask_R = RegInit(0.U(NumInputs.W))
  val mask_valid_R = RegInit(false.B)


  // Output register
  //val data_R = RegInit(0.U(xlen.W))

  val s_IDLE :: s_COMPUTE :: Nil = Enum(2)
  val state = RegInit(s_IDLE)

  /*==========================================*
   *           Predicate Evaluation           *
   *==========================================*/


  /*===============================================*
   *            Latch inputs. Wire up output       *
   *===============================================*/

  //Instantiating a MUX
  val sel = OHToUInt(mask_R)

  //wire up mask
  io.Mask.ready := ~mask_valid_R
  when(io.Mask.fire()) {
    mask_R := io.Mask.bits
    mask_valid_R := true.B
  }

  //Wire up inputs
  for (i <- 0 until NumInputs) {
    io.InData(i).ready := ~in_data_valid_R(i)
    when(io.InData(i).fire()) {
      in_data_R(i) <> io.InData(i).bits
      in_data_valid_R(i) := true.B
    }
  }

  // Wire up Outputs
  for (i <- 0 until NumOuts) {
    io.Out(i).bits <> out_data_R
  }

  /*============================================*
   *            STATE MACHINE                   *
   *============================================*/
  switch(state) {
    is(s_IDLE) {
      when((enable_valid_R) && mask_valid_R && in_data_valid_R(sel)) {
        state := s_COMPUTE
        when(enable_R.control) {
          out_data_R := in_data_R(sel)
        }
        ValidOut()
      }
    }
    is(s_COMPUTE) {
      when(IsOutReady()) {
        mask_R := 0.U
        mask_valid_R := false.B

        in_data_valid_R := VecInit(Seq.fill(NumInputs)(false.B))
        out_data_R.predicate := false.B

        //Reset state
        state := s_IDLE
        //Reset output
        Reset()

        //Print output
        if (log) {
          printf("[LOG] " + "[" + module_name + "] [TID->%d] "
            + node_name + ": Output fired @ %d, Value: %d\n", out_data_R.taskID, cycleCount, out_data_R.data)
        }


      }
    }
  }

}


/**
  * A fast version of phi node.
  * The ouput is fired as soon as all the inputs
  * are available.
  *
  * @note: These are design assumptions:
  *        1) At each instance, there is only one input signal which is predicated
  *        there is only one exception.
  *        2) The only exception is the case which one of the input is constant
  *        and because of our design constant is always fired as a first node
  *        and it has only one output. Therefore, whenever we want to restart
  *        the states, we reste all the registers, and by this way we make sure
  *        that nothing is latched.
  *        3) If Phi node itself is not predicated, we restart all the registers and
  *        fire the output with zero predication.
  * @param NumInputs
  * @param NumOuts
  * @param ID
  * @param p
  * @param name
  * @param file
  */


@deprecated("Use PhiFastNode2 instead", "1.0")
class PhiFastNode(NumInputs: Int = 2, NumOutputs: Int = 1, ID: Int)
                 (implicit p: Parameters,
                  name: sourcecode.Name,
                  file: sourcecode.File)
  extends PhiFastNodeIO(NumInputs, NumOutputs, ID)(p) {

  // Printf debugging
  override val printfSigil = "Node (PHIFast) ID: " + ID + " "
  val (cycleCount, _) = Counter(true.B, 32 * 1024)

  // Printf debugging
  val node_name = name.value
  val module_name = file.value.split("/").tail.last.split("\\.").head.capitalize

  // Data Inputs
  val in_data_R = RegInit(VecInit(Seq.fill(NumInputs)(DataBundle.default)))
  val in_data_valid_R = RegInit(VecInit(Seq.fill(NumInputs)(false.B)))

  // Enable Inputs
  val enable_R = RegInit(ControlBundle.default)
  val enable_valid_R = RegInit(false.B)

  // Mask Input
  val mask_R = RegInit(0.U(NumInputs.W))
  val mask_valid_R = RegInit(false.B)

  // Latching output data
  val out_data_R = RegInit(DataBundle.default)
  val out_valid_R = Seq.fill(NumOutputs)(RegInit(false.B))

  val fire_R = Seq.fill(NumOutputs)(RegInit(false.B))

  // Latching Mask value
  io.Mask.ready := ~mask_valid_R
  when(io.Mask.fire()) {
    mask_R := io.Mask.bits
    mask_valid_R := true.B
  }

  // Latching enable value
  io.enable.ready := ~enable_valid_R
  when(io.enable.fire()) {
    enable_R <> io.enable.bits
    enable_valid_R := true.B
  }


  for (i <- 0 until NumInputs) {
    io.InData(i).ready := ~in_data_valid_R(i)
    when(io.InData(i).fire) {
      when(io.InData(i).bits.predicate && io.InData(i).bits.predicate) {
        //Make sure that we only pick predicated values!
        in_data_R(i) <> io.InData(i).bits
        in_data_valid_R(i) := true.B
      }.otherwise {
        in_data_R(i) := DataBundle.default
        in_data_valid_R(i) := false.B
      }
    }
  }

  val mask_value =
    (io.Mask.bits & Fill(NumInputs, io.Mask.valid)) | (mask_R & Fill(NumInputs, mask_valid_R))

  //val sel = OHToUInt(Reverse(mask_value))
  val sel = OHToUInt(mask_value)


  val select_input = (io.InData(sel).bits.data & Fill(xlen, io.InData(sel).valid)) | (in_data_R(sel).data & Fill(xlen, in_data_valid_R(sel)))
  val select_predicate = (io.InData(sel).bits.predicate & Fill(xlen, io.InData(sel).valid)) | (in_data_R(sel).predicate & Fill(xlen, in_data_valid_R(sel)))

  val enable_input = (io.enable.bits.control & io.enable.valid) | (enable_R.control & enable_valid_R)

  val task_input = (io.enable.bits.taskID | enable_R.taskID)

  // Wireing outputs
  io.Out.map(_.bits) foreach (_.data := select_input)
  io.Out.map(_.bits) foreach (_.predicate := select_predicate)
  io.Out.map(_.bits) foreach (_.taskID := task_input)
  io.Out.map(_.valid) foreach (_ := false.B)


  for (i <- 0 until NumOutputs) {
    when(io.Out(i).fire) {
      fire_R(i) := true.B
    }
  }

  //Getting mask for fired nodes
  val fire_mask = (fire_R zip io.Out.map(_.fire)).map { case (a, b) => a | b }

  //Output register
  val s_idle :: s_fire :: s_not_predicated :: Nil = Enum(3)
  val state = RegInit(s_idle)

  switch(state) {
    is(s_idle) {

      when(enable_valid_R || io.enable.fire) {
        when(enable_input) {
          when(in_data_valid_R(sel) || io.InData(sel).fire) {
            io.Out.foreach(_.valid := true.B)
            when(io.Out.map(_.fire).reduce(_ & _)) {
              in_data_R.foreach(_ := DataBundle.default)
              in_data_valid_R.foreach(_ := false.B)

              mask_R := 0.U
              mask_valid_R := false.B

              enable_R := ControlBundle.default
              enable_valid_R := false.B

              out_valid_R.foreach(_ := false.B)

              fire_R.foreach(_ := false.B)

              state := s_idle

            }.otherwise {
              state := s_fire
            }

            //Print output
            if (log) {
              printf("[LOG] " + "[" + module_name + "] [TID->%d] "
                + node_name + ": Output fired @ %d, Value: %d\n",
                io.InData(sel).bits.taskID, cycleCount, select_input)
            }
          }
        }.otherwise {
          // Wireing outputs
          io.Out.map(_.bits) foreach (_.data := 0.U)
          io.Out.map(_.bits) foreach (_.predicate := enable_input)
          io.Out.map(_.bits) foreach (_.taskID := task_input)
          io.Out.map(_.valid) foreach (_ := false.B)
          io.Out.foreach(_.valid := true.B)

          when(fire_mask.reduce(_ & _)) {
            enable_R := ControlBundle.default
            enable_valid_R := false.B

            fire_R.foreach(_ := false.B)

            mask_R := 0.U

            state := s_idle
          }.otherwise {
            state := s_not_predicated
          }
        }
      }
    }

    is(s_fire) {

      io.Out.map(_.bits) foreach (_ := in_data_R(sel))
      io.Out.foreach(_.valid := true.B)

      when(fire_mask.reduce(_ & _)) {
        in_data_R.foreach(_ := DataBundle.default)
        in_data_valid_R.foreach(_ := false.B)

        mask_R := 0.U
        mask_valid_R := false.B

        enable_R := ControlBundle.default
        enable_valid_R := false.B

        out_data_R := DataBundle.default
        out_valid_R.foreach(_ := false.B)

        fire_R.foreach(_ := false.B)

        state := s_idle

      }

    }
    is(s_not_predicated) {
      io.Out.map(_.bits) foreach (_.data := 0.U)
      io.Out.map(_.bits) foreach (_.predicate := enable_input)
      io.Out.map(_.bits) foreach (_.taskID := task_input)
      io.Out.map(_.valid) foreach (_ := false.B)
      io.Out.foreach(_.valid := true.B)

      when(io.Out.map(_.fire).reduce(_ & _)) {
        enable_R := ControlBundle.default
        enable_valid_R := false.B

        fire_R.foreach(_ := false.B)

        mask_R := 0.U

        state := s_idle
      }
    }
  }

}


/**
  * A fast version of phi node.
  * The ouput is fired as soon as all the inputs
  * are available.
  *
  * @note: These are design assumptions:
  *        1) At each instance, there is only one input signal which is predicated
  *        there is only one exception.
  *        2) The only exception is the case which one of the input is constant
  *        and because of our design constant is always fired as a first node
  *        and it has only one output. Therefore, whenever we want to restart
  *        the states, we reste all the registers, and by this way we make sure
  *        that nothing is latched.
  *        3) If Phi node itself is not predicated, we restart all the registers and
  *        fire the output with zero predication.
  * @param NumInputs
  * @param NumOuts
  * @param ID
  * @param p
  * @param name
  * @param file
  */
class PhiFastNode2(NumInputs: Int = 2, NumOutputs: Int = 1, ID: Int)
                  (implicit p: Parameters,
                   name: sourcecode.Name,
                   file: sourcecode.File)
  extends PhiFastNodeIO(NumInputs, NumOutputs, ID)(p) {

  // Printf debugging
  override val printfSigil = "Node (PHIFast) ID: " + ID + " "
  val (cycleCount, _) = Counter(true.B, 32 * 1024)

  // Printf debugging
  val node_name = name.value
  val module_name = file.value.split("/").tail.last.split("\\.").head.capitalize

  // Data Inputs
  val in_data_R = RegInit(VecInit(Seq.fill(NumInputs)(DataBundle.default)))
  val in_data_valid_R = RegInit(VecInit(Seq.fill(NumInputs)(false.B)))

  // Enable Inputs
  val enable_R = RegInit(ControlBundle.default)
  val enable_valid_R = RegInit(false.B)

  // Mask Input
  val mask_R = RegInit(0.U(NumInputs.W))
  val mask_valid_R = RegInit(false.B)

  // Latching output data
  val out_valid_R = Seq.fill(NumOutputs)(RegInit(false.B))

  val fire_R = Seq.fill(NumOutputs)(RegInit(false.B))

  // Latching Mask value
  io.Mask.ready := ~mask_valid_R
  when(io.Mask.fire()) {
    mask_R := io.Mask.bits
    mask_valid_R := true.B
  }

  // Latching enable value
  io.enable.ready := ~enable_valid_R
  when(io.enable.fire()) {
    enable_R <> io.enable.bits
    enable_valid_R := true.B
  }


  for (i <- 0 until NumInputs) {
    io.InData(i).ready := ~in_data_valid_R(i)
    when(io.InData(i).fire) {
      when(io.InData(i).bits.predicate) {
        //Make sure that we only pick predicated values!
        in_data_R(i) <> io.InData(i).bits
        in_data_valid_R(i) := true.B
      }.otherwise {
        in_data_R(i) := DataBundle.default
        in_data_valid_R(i) := false.B
      }
    }
  }

  //val sel = OHToUInt(Reverse(mask_value))
  val sel = OHToUInt(mask_R)

  val select_input = in_data_R(sel).data
  val select_predicate = in_data_R(sel).predicate

  val enable_input = enable_R.control

  val task_input = (io.enable.bits.taskID | enable_R.taskID)

  // Wiring outputs default value
  io.Out.map(_.bits) foreach (_.data := 0.U)
  io.Out.map(_.bits) foreach (_.predicate := false.B)
  io.Out.map(_.bits) foreach (_.taskID := 0.U)
  io.Out.map(_.valid) foreach (_ := false.B)


  for (i <- 0 until NumOutputs) {
    when(io.Out(i).fire) {
      fire_R(i) := true.B
      out_valid_R(i) := false.B
    }
  }

  //Getting mask for fired nodes
  val fire_mask = (fire_R zip io.Out.map(_.fire)).map { case (a, b) => a | b }

  //Output register
  val s_idle :: s_fire :: s_not_predicated :: Nil = Enum(3)
  val state = RegInit(s_idle)

  switch(state) {
    is(s_idle) {
      when(enable_valid_R) {
        when(enable_R.control) {
          when(in_data_valid_R(sel)) {
            out_valid_R foreach {
              _ := true.B
            }
            state := s_fire

            //Print output
            if (log) {
              printf("[LOG] " + "[" + module_name + "] [TID->%d] "
                + node_name + ": Output fired @ %d, Value: %d\n",
                io.InData(sel).bits.taskID, cycleCount, select_input)
            }
          }
        }.otherwise {
          out_valid_R foreach {
            _ := true.B
          }
          state := s_not_predicated
          //Print output
          if (log) {
            printf("[LOG] " + "[" + module_name + "] [TID->%d] "
              + node_name + ": Output flushed @ %d, Value: %d\n",
              io.InData(sel).bits.taskID, cycleCount, select_input)
          }
        }
      }
    }

    is(s_fire) {
      io.Out.map(_.bits) foreach (_ := in_data_R(sel))
      (io.Out.map(_.valid) zip out_valid_R).map { case (a, b) => a := b }

      when(fire_mask.reduce(_ & _)) {

        /** @note: In this case whenever all the GEP is fired we
          *       restart all the latched values. But it may be cases
          *       that because of pipelining we have latched an interation ahead
          *       and if we may reset the latches values we lost the value.
          *       I'm not sure when this case can happen!
         */
        // in_data_R(sel) := DataBundle.default
        // in_data_valid_R(sel) := false.B
        in_data_R foreach {
          _ := DataBundle.default
        }
        in_data_valid_R foreach {
          _ := false.B
        }

        mask_R := 0.U
        mask_valid_R := false.B

        enable_R := ControlBundle.default
        enable_valid_R := false.B

        out_valid_R foreach {
          _ := false.B
        }

        fire_R foreach {
          _ := false.B
        }

        state := s_idle

      }

    }
    is(s_not_predicated) {
      io.Out.map(_.bits) foreach (_.data := 0.U)
      io.Out.map(_.bits) foreach (_.predicate := enable_input)
      io.Out.map(_.bits) foreach (_.taskID := task_input)
      io.Out.map(_.valid) foreach (_ := false.B)
      (io.Out.map(_.valid) zip out_valid_R).map { case (a, b) => a := b }

      when(io.Out.map(_.fire).reduce(_ & _)) {

        //        in_data_R(sel) := DataBundle.default
        //        in_data_valid_R(sel) := false.B
        in_data_R foreach {
          _ := DataBundle.default
        }
        in_data_valid_R foreach {
          _ := false.B
        }

        enable_R := ControlBundle.default
        enable_valid_R := false.B

        fire_R.foreach(_ := false.B)

        mask_R := 0.U
        mask_valid_R := false.B

        out_valid_R foreach {
          _ := true.B
        }

        state := s_idle
      }
    }
  }

}

