package node

/**
  * Created by nvedula on 15/5/17.
  */


import chisel3.iotesters.{ChiselFlatSpec, Driver, PeekPokeTester, OrderedDecoupledHWIOTester}
import org.scalatest.{Matchers, FlatSpec}

import config._


class LoadSimpleNodeTests(c: LoadSimpleNode) extends PeekPokeTester(c) {
    poke(c.io.GepAddr.valid,false)
    poke(c.io.PredMemOp(0).valid,true)
    poke(c.io.memReq.ready,false)
    poke(c.io.memResp.valid,false)
    poke(c.io.SuccMemOp(0).ready,true)
    poke(c.io.Out(0).ready,true)

    for (t <- 0 until 20) {

    

      //IF ready is set
      // send address
      if (peek(c.io.GepAddr.ready) == 1) {
        poke(c.io.GepAddr.valid, true)
        poke(c.io.GepAddr.bits, 12)
      }
     
       if((peek(c.io.memReq.valid) == 1) && (t > 4))
      {
     
        poke(c.io.memReq.ready,true)
      }

      if (t > 5 && peek(c.io.memReq.ready) == 1)
      {
        // poke(c.io.memReq.ready,false)
        poke(c.io.memResp.data,t)
        poke(c.io.memResp.valid,true)
      }
       step(1)
      // //Response is before request -- so that it is true in the next cycle
      // //NOte: Response should be received atleast after a cycle of memory request
      // // Otherwise it is undefined behaviour
      // if (is_k) {
      //   //since response is available atleast next cycle onwards
      //   poke(c.io.memResp.valid, true)
      //   poke(c.io.memResp.data, 34)

      //   printf(s"\n t: ${t} MemResponse received \n")
      //   printf(s"t: ${t}  io.Memresp_valid: ${peek(c.io.memResp.valid)} \n")
      //   is_k = false

      // }

      // //Memory is always ready to receive the memory requests
      // //TODO make them as single signal
      // poke(c.io.memReq.ready, true)
      // //When StoreNode requests the data print the contents

      // if (peek(c.io.memReq.valid) == 1) {
      //   if (!is_k) is_k = true

      //   printf(s"\n t: ${t} MemRequest Sent \n")
      //   printf(s"t: ${t}  io.Memreq_addr: ${peek(c.io.memReq.bits.address)} " +
      //     s"io.memReq.nodeid : ${peek(c.io.memReq.bits.node)} \n")


      // }



      // //at some clock - send src mem-op is done executing
      // if (t > 4) {
      //   if (peek(c.io.predMemOp(0).ready) == 1) {
      //     poke(c.io.predMemOp(0).valid, true)
      //     printf(s"\n t:${t} predMemOp(0) Fire \n")
      //   }
      //   else {
      //     poke(c.io.predMemOp(0).valid, false)
      //   }

      // }
      // else {
      //   poke(c.io.predMemOp(0).valid, false)
      // }

      // if (t > 5) {
      //   if (peek(c.io.predMemOp(1).ready) == 1) {
      //     poke(c.io.predMemOp(1).valid, true)
      //     printf(s"\n t:${t} predMemOp(1) Fire \n")
      //   }
      //   else {
      //     poke(c.io.predMemOp(1).valid, false)
      //   }

      // }
      // else {
      //   poke(c.io.predMemOp(1).valid, false)
      // }

   
    }

}

//class LoadNodeTester extends ChiselFlatSpec {
//behavior of "LoadNode"
//backends foreach {backend =>
//it should s"correctly find decoupled behaviour -  $backend" in {
//Driver(() => new LoadNode(32), backend)((c) => new LoadNodeTests(c)) should be (true)
//}
//}
//}

import Constants._

class LoadSimpleNodeTester extends  FlatSpec with Matchers {
  implicit val p = config.Parameters.root((new MiniConfig).toInstance)
  it should "Load Node tester" in {
    chisel3.iotesters.Driver(() => new LoadSimpleNode(NumPredMemOps=1,NumSuccMemOps=1,NumOuts=1,Typ=MT_W,ID=1)) { c =>
      new LoadSimpleNodeTests(c)
    } should be(true)
  }
}
