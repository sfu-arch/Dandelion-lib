package node

/**
  * Created by nvedula on 15/5/17.
  */

import chisel3._
import chisel3.util._
import org.scalacheck.Prop.False

import config._
import interfaces._
import utility.Constants._
import utility.UniformPrintfs


// Design Doc
//////////
/// DRIVER ///
/// 1. Memory response only available atleast 1 cycle after request
//  2. Handshaking has to be done with registers.
// @todo : This node will only receive one word. To handle doubles. Change handshaking logic
//////////

class LoadIO(NumPredOps: Int,
             NumSuccOps: Int,
             NumOuts: Int)(implicit p: Parameters)
  extends HandShakingIOPS(NumPredOps, NumSuccOps, NumOuts)(new DataBundle) {
  // GepAddr: The calculated address comming from GEP node
  val GepAddr = Flipped(Decoupled(new DataBundle))
  // Memory request
  val memReq = Decoupled(new ReadReq())
  // Memory response.
  val memResp = Input(Flipped(new ReadResp()))
}

class UnTypLoad(NumPredOps: Int,
                NumSuccOps: Int,
                NumOuts: Int,
                Typ: UInt = MT_W, ID: Int, RouteID: Int)
               (implicit p: Parameters,
                name: sourcecode.Name,
                file: sourcecode.File)
  extends HandShaking(NumPredOps, NumSuccOps, NumOuts, ID)(new DataBundle)(p) {

  override lazy val io = IO(new LoadIO(NumPredOps, NumSuccOps, NumOuts))
  // Printf debugging
  val node_name = name.value
  val module_name = file.value.split("/").tail.last.split("\\.").head.capitalize
  val (cycleCount, _) = Counter(true.B, 32 * 1024)
  override val printfSigil = "[" + module_name + "] " + node_name + ": " + ID + " "


  /*=============================================
  =            Registers                        =
  =============================================*/
  // OP Inputs
  val addr_R = RegInit(DataBundle.default)
  val addr_valid_R = RegInit(false.B)

  // Memory Response
  val data_R = RegInit(DataBundle.default)
  val data_valid_R = RegInit(false.B)

  // State machine
  val s_idle :: s_RECEIVING :: s_Done :: Nil = Enum(3)
  val state = RegInit(s_idle)

  /*============================================
  =            Predicate Evaluation            =
  ============================================*/

  //  val start = addr_valid_R & IsPredValid & IsEnableValid()

  /*================================================
  =            Latch inputs. Wire up output            =
  ================================================*/

  io.GepAddr.ready := ~addr_valid_R
  when(io.GepAddr.fire()) {
    addr_R := io.GepAddr.bits
    //addr_R.valid := true.B
    addr_valid_R := true.B
  }

  // Wire up Outputs
  for (i <- 0 until NumOuts) {
    io.Out(i).bits := data_R
    io.Out(i).bits.taskID := addr_R.taskID | enable_R.taskID
  }

  io.memReq.valid := false.B
  io.memReq.bits.address := addr_R.data
  io.memReq.bits.Typ := Typ
  io.memReq.bits.RouteID := RouteID.U
  io.memReq.bits.taskID := addr_R.taskID | enable_R.taskID

  // Connect successors outputs to the enable status
  when(io.enable.fire()) {
    succ_bundle_R.foreach(_ := io.enable.bits)
  }
  /*=============================================
  =            ACTIONS (possibly dangerous)     =
  =============================================*/

  //val mem_req_fire = addr_valid_R & IsPredValid()
  val complete = IsSuccReady() & IsOutReady()

  switch(state) {
    is(s_idle) {
      when(enable_valid_R && addr_valid_R && IsPredValid()) {
        when(enable_R.control) {
          io.memReq.valid := true.B
          when(io.memReq.ready) {
            state := s_RECEIVING
          }
        }.otherwise {
          data_R.predicate :=false.B
          ValidSucc()
          ValidOut()
          data_R.predicate := false.B
          // Completion state.
          state := s_Done

        }
      }
    }
    is(s_RECEIVING) {
      when(io.memResp.valid) {

        // Set data output registers
        data_R.data := io.memResp.data
        data_R.predicate := true.B
        //data_R.valid := true.B
        ValidSucc()
        ValidOut()
        // Completion state.
        state := s_Done

      }
    }
    is(s_Done) {
      when(complete) {
        // Clear all the valid states.
        // Reset address
//        addr_R := DataBundle.default
        addr_valid_R := false.B
        // Reset data
//        data_R := DataBundle.default
        data_valid_R := false.B
        // Reset state.
        Reset()
        // Reset state.
        state := s_idle
        printf("[LOG] " + "[" + module_name + "] [TID->%d] " + node_name + ": Output fired @ %d, Address:%d\n",enable_R.taskID, cycleCount, addr_R.data)
        //printf("DEBUG " + node_name + ": $%d = %d\n", addr_R.data, data_R.data)
      }
    }
  }

  //  when(start & predicate) {
  //    // ACTION:  Memory request
  //    //  Check if address is valid and predecessors have completed.
  //
  //    // ACTION: Outgoing Address Req ->
  //    when((state === s_idle) && (mem_req_fire)) {
  //      io.memReq.valid := true.B
  //    }
  //
  //    //  ACTION: Arbitration ready
  //    //   <- Incoming memory arbitration
  //    when((state === s_idle) && (io.memReq.ready === true.B) && (io.memReq.valid === true.B)) {
  //      state := s_RECEIVING
  //    }
  //
  //    // Data detected only one cycle later.
  //    // Memory should supply only one cycle after arbitration.
  //    //  ACTION:  <- Incoming Data
  //    when(state === s_RECEIVING && io.memResp.valid) {
  //      // Set data output registers
  //      data_R.data := io.memResp.data
  //      data_R.predicate := predicate
  //      //data_R.valid := true.B
  //      ValidSucc()
  //      ValidOut()
  //      // Completion state.
  //      state := s_Done
  //    }
  //  }.elsewhen(start && !predicate && state =/= s_Done) {
  //    //    ValidSucc()
  //    //    ValidOut()
  //    //    state := s_Done
  //    addr_R := DataBundle.default
  //    addr_valid_R := false.B
  //    // Reset data
  //    data_R := DataBundle.default
  //    data_valid_R := false.B
  //    // Reset state.
  //    Reset()
  //    // Reset state.
  //    state := s_idle
  //    printf("[LOG] " + "[" + module_name + "] " + node_name + ": restarted @ %d\n", cycleCount)
  //  }
  //  /*===========================================
  //  =            Output Handshaking and Reset   =
  //  ===========================================*/
  //
  //  //  ACTION: <- Check Out READY and Successors READY
  //  when(state === s_Done) {
  //    // When successors are complete and outputs are ready you can reset.
  //    // data already valid and would be latched in this cycle.
  //    when(complete) {
  //      // Clear all the valid states.
  //      // Reset address
  //      addr_R := DataBundle.default
  //      addr_valid_R := false.B
  //      // Reset data
  //      data_R := DataBundle.default
  //      data_valid_R := false.B
  //      // Reset state.
  //      Reset()
  //      // Reset state.
  //      state := s_idle
  //      when(predicate) {
  //        printf("[LOG] " + "[" + module_name + "] " + node_name + ": Output fired @ %d\n", cycleCount)
  //      }
  //    }
  //  }

}
