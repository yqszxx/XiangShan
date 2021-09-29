/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package xiangshan.backend.dispatch

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import xiangshan._
import utils._
import xiangshan.backend.regfile.RfReadPort
import xiangshan.backend.rob.{RobPtr, RobEnqIO}
import xiangshan.backend.rename.{RenameBypassInfo, BusyTableReadIO}
import xiangshan.mem.LsqEnqIO

case class DispatchParameters
(
  IntDqSize: Int,
  FpDqSize: Int,
  LsDqSize: Int,
  IntDqDeqWidth: Int,
  FpDqDeqWidth: Int,
  LsDqDeqWidth: Int
)

class Dispatch(implicit p: Parameters) extends XSModule {
  val io = IO(new Bundle() {
    // flush or replay
    val redirect = Flipped(ValidIO(new Redirect))
    val flush = Input(Bool())
    // from rename
    val fromRename = Vec(RenameWidth, Flipped(DecoupledIO(new MicroOp)))
    val renameBypass = Input(new RenameBypassInfo)
    val preDpInfo = Input(new PreDispatchInfo)
    // to busytable: set pdest to busy (not ready) when they are dispatched
    val allocPregs = Vec(RenameWidth, Output(new ResetPregStateReq))
    // enq Rob
    val enqRob = Flipped(new RobEnqIO)
    // enq Lsq
    val enqLsq = Flipped(new LsqEnqIO)
    // to reservation stations
    // val enqIQCtrl = Vec(exuParameters.CriticalExuCnt, DecoupledIO(new MicroOp))
    val dispatch = Vec(3 * dpParams.IntDqDeqWidth, DecoupledIO(new MicroOp))
    // send reg file read port index to reservation stations
    val csrCtrl = Input(new CustomCSRCtrlIO)
    // LFST state sync
    val storeIssue = Vec(StorePipelineWidth, Flipped(Valid(new ExuInput)))
    val ctrlInfo = new Bundle {
      val robFull   = Output(Bool())
      val intdqFull = Output(Bool())
      val fpdqFull  = Output(Bool())
      val lsdqFull  = Output(Bool())
    }
    // From CSR: to control single step execution
    val singleStep = Input(Bool())
  })

  val dispatch1 = Module(new Dispatch1)
  val intDq = Module(new DispatchQueue(dpParams.IntDqSize, RenameWidth, dpParams.IntDqDeqWidth, "int"))
  val fpDq = Module(new DispatchQueue(dpParams.FpDqSize, RenameWidth, dpParams.FpDqDeqWidth, "fp"))
  val lsDq = Module(new DispatchQueue(dpParams.LsDqSize, RenameWidth, dpParams.LsDqDeqWidth, "ls"))

  // pipeline between rename and dispatch
  // accepts all at once
  val redirectValid = io.redirect.valid || io.flush
  for (i <- 0 until RenameWidth) {
    PipelineConnect(io.fromRename(i), dispatch1.io.fromRename(i), dispatch1.io.recv(i), redirectValid)
  }

  // dispatch 1: accept uops from rename and dispatch them to the three dispatch queues
  // dispatch1.io.redirect <> io.redirect
  dispatch1.io.renameBypass := RegEnable(io.renameBypass, io.fromRename(0).valid && dispatch1.io.fromRename(0).ready)
  dispatch1.io.preDpInfo := RegEnable(io.preDpInfo, io.fromRename(0).valid && dispatch1.io.fromRename(0).ready)
  dispatch1.io.enqRob <> io.enqRob
  dispatch1.io.enqLsq <> io.enqLsq
  dispatch1.io.toIntDq <> intDq.io.enq
  dispatch1.io.toFpDq <> fpDq.io.enq
  dispatch1.io.toLsDq <> lsDq.io.enq
  dispatch1.io.allocPregs <> io.allocPregs
  dispatch1.io.csrCtrl <> io.csrCtrl
  dispatch1.io.storeIssue <> io.storeIssue
  dispatch1.io.redirect <> io.redirect
  dispatch1.io.flush <> io.flush
  dispatch1.io.singleStep := io.singleStep

  // dispatch queue: queue uops and dispatch them to different reservation stations or issue queues
  // it may cancel the uops
  intDq.io.redirect <> io.redirect
  intDq.io.flush <> io.flush
  fpDq.io.redirect <> io.redirect
  fpDq.io.flush <> io.flush
  lsDq.io.redirect <> io.redirect
  lsDq.io.flush <> io.flush

  // Int dispatch queue to Int reservation stations
  io.dispatch <> intDq.io.deq ++ lsDq.io.deq ++ fpDq.io.deq
  io.ctrlInfo <> DontCare
  io.ctrlInfo.intdqFull := intDq.io.dqFull
  io.ctrlInfo.fpdqFull := fpDq.io.dqFull
  io.ctrlInfo.lsdqFull := lsDq.io.dqFull
}
