package spinal.lib.com.eth

import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc.BusSlaveFactory



case class MacEthParameter(phy: PhyParameter,
                           rxDataWidth : Int,
                           txDataWidth : Int,
                           rxBufferByteSize : Int,
                           txBufferByteSize : Int){
  val txAvailabilityWidth = log2Up((txBufferByteSize * 8 / 32) + 1)
}

case class MacMiiCtrl(p : MacEthParameter) extends Bundle{
  val rx = new Bundle {
    val stream = master(Stream(Bits(p.rxDataWidth bits)))
    val flush = in Bool()
  }
  val tx = new Bundle {
    val stream = slave(Stream(Bits(p.txDataWidth bits)))
    val availability = out UInt(p.txAvailabilityWidth bits)
    val flush = in Bool()
  }

  val interrupt = out Bool()

  def driveFrom(bus: BusSlaveFactory) = new Area{
    bus.drive(tx.flush,   0x00, 0) init(True)
    bus.read(tx.stream.ready, 0x00, 1)

    bus.drive(rx.flush,   0x00, 4) init(True)
    bus.read(rx.stream.valid, 0x00, 5)



    tx.stream << bus.createAndDriveFlow(Bits(p.txDataWidth bits), 0x10).toStream
    bus.read(tx.availability, 0x14)

    rx.stream.ready := False
    bus.onRead(0x20){rx.stream.ready := True}
    bus.read(rx.stream.payload, 0x20)

    val interruptCtrl = new Area{
      val pending = RegNext(interrupt) init(False)
    }
  }
}

case class PhyParameter(txDataWidth : Int,
                        rxDataWidth : Int)

case class PhyIo(p : PhyParameter) extends Bundle with IMasterSlave {
  val rx = Stream(Fragment(PhyRx(p.rxDataWidth)))
  val tx = Stream(Fragment(PhyTx(p.txDataWidth)))
  val colision = Bool()
  val busy = Bool()

  override def asMaster(): Unit = {
    master(tx)
    slave(rx)
    in(colision)
    in(busy)
  }
}

case class MacEth(p : MacEthParameter,
                  txCd : ClockDomain,
                  rxCd : ClockDomain) extends Component{
  val io = new Bundle {
    val phy = master(PhyIo(p.phy))
    val ctrl = MacMiiCtrl(p)
  }


  io.ctrl.interrupt := False

  val ctrlClockDomain = this.clockDomain

  val rxReset = ResetCtrl.asyncAssertSyncDeassert(
    input = ClockDomain.current.isResetActive || io.ctrl.rx.flush,
    clockDomain = rxCd
  )
  val rxClockDomain = rxCd.copy(reset = rxReset)


  val txReset = ResetCtrl.asyncAssertSyncDeassert(
    input = ClockDomain.current.isResetActive || io.ctrl.tx.flush,
    clockDomain = txCd
  )
  val txClockDomain = txCd.copy(reset = txReset)

  val rxFrontend = rxClockDomain on new Area{
    val preamble = MacRxPreamble(dataWidth = p.phy.rxDataWidth)
    preamble.io.input << io.phy.rx

    val checker = MacRxChecker(dataWidth = p.phy.rxDataWidth)
    checker.io.input << preamble.io.output

    val buffer = MacRxBuffer(
      pushCd = rxClockDomain,
      popCd = ctrlClockDomain.copy(softReset = io.ctrl.rx.flush),
      pushWidth = p.phy.rxDataWidth,
      popWidth = p.rxDataWidth,
      byteSize = p.rxBufferByteSize,
      lengthMax = 2000
    )
    buffer.io.push.stream << checker.io.output
  }

  val rxBackend = new Area{
    rxFrontend.buffer.io.pop.stream >> io.ctrl.rx.stream
  }


  val txFrontend = new Area{
    val buffer = MacTxBuffer(
      pushCd = ctrlClockDomain.copy(softReset = io.ctrl.tx.flush),
      popCd = txClockDomain,
      pushWidth = p.rxDataWidth,
      popWidth = p.phy.txDataWidth,
      byteSize = p.txBufferByteSize,
      lengthMax = 2000
    )
    buffer.io.push.stream << io.ctrl.tx.stream
    buffer.io.push.availability <> io.ctrl.tx.availability
  }

  val txBackend = txClockDomain on new Area{
    val padder = MacTxPadder(dataWidth = p.phy.txDataWidth)
    padder.io.input << txFrontend.buffer.io.pop.stream

    val crc = MacTxCrc(dataWidth = p.phy.txDataWidth)
    crc.io.input << padder.io.output

    val header = MacTxHeader(dataWidth = p.phy.txDataWidth)
    header.io.input << crc.io.output
    header.io.output >> io.phy.tx


    txFrontend.buffer.io.pop.redo := False
    txFrontend.buffer.io.pop.commit := RegNext(header.io.output.lastFire) init(False)
  }
}