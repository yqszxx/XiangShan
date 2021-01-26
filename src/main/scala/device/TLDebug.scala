package device

import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink._
import chipsalliance.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper.RegField
import utils.{HasTLDump, XSDebug}
import xiangshan.HasXSLog

trait DebugConstants {
  val abits = // TODO
}

class dmiBundle with DebugConstants{
  val req_valid   = Input(Bool())
  val req_ready   = Input(Bool())
  val req_address = Input(UInt(abits.W))
  val req_data    = Input(UInt(32.W))
  val req_op      = Input(UInt(2.W))

  val rsp_valid   = Output(Bool())
  val rsp_ready   = Output(Bool())
  val rsp_data    = Output(UInt(32.W))
  val rsp_op      = Output(UInt(2.W))
}

class TLDebug(address: Seq[AddressSet], sim: Boolean)(implicit p: Parameters) extends LazyModule {
  val device = new SimpleDevice("debugModule", Seq("XiangShan", "clint"))
  val node = TLRegisterNode(address, device, beatBytes = 8)
  val NumCores = top.Parameters.get.socParameters.NumCores

  lazy val module = new LazyModuleImp(this) with HasXSLog with HasTLDump{
    val io = IO(new Bundle() {
      val debugInt = Output(Vec(NumCores, Bool()))
      val dmi = new dmiBundle
    })

    //---------
    // MMIO Regs for abstract commands and program buffer
    //---------

    // by writing 0s to these locations to indicate hart status
    val mmio_halted       = Reg(Vec(NumCores, UInt(8.W)))
    val mmio_resumeack    = Reg(Vec(NumCores, UInt(8.W)))
    val mmio_running      = Reg(Vec(NumCores, UInt(8.W)))
    val mmio_havereset    = Reg(Vec(NumCores, UInt(8.W)))

    // Program buffer
    // as described in manual
    val program_buffer     = Reg(Vec(16, UInt(32.W)))

    // Abstarct command
    // For controlling halt and resume
    val abstract_command   = Reg(Vec(16, UInt(32.W)))

    // Abstract data
    // as described in manual, and 16 more for other uses
    val abstract_data      = Reg(Vec(12, UInt(32.W)))

    //

    //-------------
    // DM registers
    // They are visible to DTM
    // Their fields are in dm_registers.scala
    //-------------
    val dmstatus          = RegInit(0.U(32.W))
    val dmcontrol         = RegInit(0.U(32.W)) 
    val hartinfo          = RegInit(0.U(32.W)) 
    val haltsum           = RegInit(0.U(32.W)) 
    val dmabstractcs      = RegInit(0.U(32.W)) 
    val dmcommand         = RegInit(0.U(32.W)) 
    val abstractauto      = RegInit(0.U(32.W)) 
    val authdata          = RegInit(0.U(32.W)) 
    val haltsum1          = RegInit(0.U(32.W)) 
    val dmcs2             = RegInit(0.U(32.W))

    //-----------
    // system bus access
    // TODO: init values
    //-----------
    val sbaddress         = RegInit(0.U(64.W))
    val sbdata            = RegInit(0.U(64.W)) 

    // general info about harts
    val havereset = Wire(Vec(NumCores, Bool())
    val resumeack = Wire(Vec(NumCores, Bool())
    val nonexistent = Wire(Vec(NumCores, Bool())
    val unavail = Wire(Vec(NumCores, Bool())
    val running = Wire(Vec(NumCores, Bool())
    val halted = Wire(Vec(NumCores, Bool())

    // general commands. Note that these are commands
    val halt = Wire(Bool())
    val resume = Wire(Bool())
    val reset = Wire(Bool())
    val ackhavereset = Wire(Bool())
    val ackunavail = Wire(Bool())

    val abstractBusy := Wire(Bool())
    val cmderr := Wire(UInt(3.W)) //TODO: special 


    // dmstatus
    // TODO: use mask, use loop to shorten code
    //
    val authenticated = WireInit(false.B)

    val dmstatusIn = Wire(new DMSTATUSFields)
    dmstatusIn.reserved0 := 0.U
    dmstatusIn.reserved1 := 0.U
    dmstatusIn.version := 15.U
    dmstatusIn.hasresethaltreq:= true.B // TODO
    dmstatusIn.confstrptrvalid := false.B // TODO

    dmstatusIn.anyhavereset := havereset.reduce(_ | _).asBools
    dmstatusIn.allhavereset := havereset.reduce(_ & _).asBools
    dmstatusIn.anyresumeack := resumeack.reduce(_ | _).asBools
    dmstatusIn.allresumeack := resumeack.reduce(_ & _).asBools
    dmstatusIn.anynonexistent := nonexistent.reduce(_ | _).asBools
    dmstatusIn.allnonexistent := nonexistent.reduce(_ & _).asBools
    dmstatusIn.anyunavail := unavail.reduce(_ | _).asBools
    dmstatusIn.allunavail := unavail.reduce(_ & _).asBools
    dmstatusIn.anyrunning := running.reduce(_ | _).asBools
    dmstatusIn.allrunning := running.reduce(_ & _).asBools
    dmstatusIn.anyhalted := halted.reduce(_ | _).asBools
    dmstatusIn.allhalted := halted.reduce(_ & _).asBools
    dmstatusIn.authenticated := authenticated.asBools

    dmstatus := dmstatusIn.asTypeOf(UInt(32.W))

    // dmcontrol
    // 
    val dmcontrolIn = Wire(UInt(32.W))
    val dmcontrolMask = 0x5800003C.U(32.W)
    dmcontrol := dmcontrolIn & dmcontrolMask

    // hartinfo
    // 
    val hartinfoIn = Wire(new HARTINFOFields)
    hartinfoIn.reserved0 := 0.U
    hartinfoIn.reserved1 := 0.U
    hartinfoIn.nscratch := 0.U // TODO
    hartinfoIn.dataaccess := true.B
    hartinfoIn.datasize := 12.U
    hartinfoIn.dataaddr := 1024.U // TODO: for different cores????

    hartinfo := hartinfoIn.asTypeOf(UInt(32.W))

    // hawindowsel and hawindow are currently unimplemented

    // abstractcs 
    val abstractctsIn = Wire(new ABSTRACTCSFields)
    abstractctsIn.reserved0 := 0.U
    abstractctsIn.reserved1 := 0.U
    abstractctsIn.reserved2 := 0.U
    abstractctsIn.progbufsize := 16.U
    abstractctsIn.datacount := 12.U
    abstractctsIn.busy := abstractBusy
    abstractctsIn.cmderr := when(abstractsctsClr === 7.U(3.W)) {
      0.U(3.W) // TODO: what if clr and error comes at same time?
    }.otherwise {
      cmderr
    }
    abstractcts := abstractctsIn.asTypeOf(UInt(32.W))

    // command
    command := commandIn // TODO

    // abstractauto
    // TODO

    // data regs and prog buffer
    val errorBusy = (dataaccess & abstractBusy)

    // dmcs2
    val dmcs2In = Wire(new DMCS2Fields)
    dmcs2In.reserved0 := 0.U
    dmcs2In.exttrigger := 0.U
    // TODO halt group stuff

    // haltsum0 
    haltsum0 := halted.asUInt

    // sbcs
    sbbusyerror := sbbusy & sbaccess
    sberror := Mux() // TODO
    val sbcsIn = Wire(new sbcsFields)
    sbcsIn.sbversion := 1.U
    sbcsIn.reserved0 := 0.U
    sbcsIn.sbbusyerror := when(sbbusyerrorClr) {
      false.B
    }.otherwise {
      sbbusyerror
    }
    sbcsIn.sbbusy := sbbusy
    sbcsIn.sbreadonaddr := sbreadonaddr
    sbcsIn.sbaccess := sbaccess
    sbcsIn.sberror := when(sberrorClr === 7.U(3.W)) {
      0.U(3.W) // TODO: what if clr and error comes at same time?
    }.otherwise {
      sberror
    }
    sbcsIn.sbasize := 64.U // TODO understand wtf system bus access is
    (sbcsIn.sbaccess128, sbcsIn.sbaccess64, sbcsIn.sbaccess32, sbcsIn.sbaccess16, sbcsIn.sbaccess8) :=
    (false.B, true.B, true.B, true.B, true.B)


    // sbaddress

    // sbdata

    //






    val mtime = RegInit(0.U(64.W))  // unit: us
    val mtimecmp = Seq.fill(NumCores)(RegInit(0.U(64.W)))
    val msip = Seq.fill(NumCores)(RegInit(0.U(32.W)))

    val clk = (if (!sim) 40 /* 40MHz / 1000000 */ else 100)
    val freq = RegInit(clk.U(16.W))
    val inc = RegInit(1.U(16.W))

    val cnt = RegInit(0.U(16.W))
    val nextCnt = cnt + 1.U
    cnt := Mux(nextCnt < freq, nextCnt, 0.U)
    val tick = (nextCnt === freq)
    when (tick) { mtime := mtime + inc }

    // Below are mapping

    var clintMapping = Seq(
      0x8000 -> RegField.bytes(freq),
      0x8008 -> RegField.bytes(inc),
      0xbff8 -> RegField.bytes(mtime))

    for (i <- 0 until NumCores) {
      clintMapping = clintMapping ++ Seq(
        0x0000 + i*4 -> RegField.bytes(msip(i)),
        0x4000 + i*8 -> RegField.bytes(mtimecmp(i))
      )
    }

    node.regmap( mapping = clintMapping:_* )

    val in = node.in.head._1
    when(in.a.valid){
      XSDebug("[A] channel valid ready=%d ", in.a.ready)
      in.a.bits.dump
    }

//    val gtime = GTimer()
//    printf(p"[$gtime][Timer] mtime=$mtime cnt=$cnt freq=$freq\n")

    for (i <- 0 until NumCores) {
      io.mtip(i) := RegNext(mtime >= mtimecmp(i))
      io.msip(i) := RegNext(msip(i) =/= 0.U)
    }
  }
}
