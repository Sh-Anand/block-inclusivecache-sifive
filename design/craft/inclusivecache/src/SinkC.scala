/*
 * Copyright 2019 SiFive, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You should have received a copy of LICENSE.Apache2 along with
 * this software. If not, you may obtain a copy at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sifive.blocks.inclusivecache

import Chisel._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import chisel3.dontTouch

class SinkCResponse(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val last   = Bool()
  val set    = UInt(width = params.setBits)
  val tag    = UInt(width = params.tagBits)
  val source = UInt(width = params.inner.bundle.sourceBits)
  val param  = UInt(width = 3)
  val data   = Bool()
}

class PutBufferCEntry(params: InclusiveCacheParameters) extends InclusiveCacheBundle(params)
{
  val data = UInt(width = params.inner.bundle.dataBits)
  val corrupt = Bool()
}

class SinkC(params: InclusiveCacheParameters) extends Module
{
  val io = new Bundle {
    val req = Decoupled(new FullRequest(params)) // Release
    val resp = Valid(new SinkCResponse(params)) // ProbeAck
    val c = Decoupled(new TLBundleC(params.inner.bundle)).flip
    // Find 'way' via MSHR CAM lookup
    val tag = UInt(width = params.tagBits)
    val set = UInt(width = params.setBits)
    val way = UInt(width = params.wayBits).flip
    val bs_set = UInt(width = params.setBits)
    val way_valid = Bool().flip
    val is_flush = Bool()
    val opcode = UInt(width = 3)
    // ProbeAck write-back
    val bs_adr = Decoupled(new BankedStoreInnerAddress(params))
    val bs_dat = new BankedStoreInnerPoison(params)
    // SourceD sideband
    val rel_pop  = Decoupled(new PutBufferPop(params)).flip
    val rel_beat = new PutBufferCEntry(params)
  }

  if (params.firstLevel) {
    // Tie off unused ports
    io.req.valid := Bool(false)
    io.resp.valid := Bool(false)
    io.c.ready := Bool(true)
    io.set := UInt(0)
    io.bs_adr.valid := Bool(false)
    io.rel_pop.ready := Bool(true)
  } else {
    // No restrictions on the type of buffer
    val c = params.micro.innerBuf.c(io.c)

    val (tag, set, offset) = params.parseAddress(c.bits.address)
    val (first, last, _, beat) = params.inner.count(c)
    val hasData = params.inner.hasData(c.bits)
    val raw_resp = c.bits.opcode === TLMessages.ProbeAck || c.bits.opcode === TLMessages.ProbeAckData
    val raw_isFlush = raw_resp && (c.bits.param === TLPermissions.FLUSH || c.bits.param === TLPermissions.FLUSH_WB)
    val raw_isFlushWB = raw_resp && (c.bits.param === TLPermissions.FLUSH_WB)
    val resp = Mux(c.valid, raw_resp, RegEnable(raw_resp, c.valid))
    val isFlush = Mux(c.valid, raw_isFlush, RegEnable(raw_isFlush, c.valid))
    val isFlushWB = Mux(c.valid, raw_isFlushWB, RegEnable(raw_isFlushWB, c.valid))
    val flushed = RegInit(Bool(false))
    val way_valid = Wire(Bool(false))

    // Handling of C is broken into two cases:
    //   ProbeAck
    //     if hasData, must be written to BankedStore
    //     if last beat, trigger resp
    //   Release
    //     if first beat, trigger req
    //     if hasData, go to putBuffer
    //     if hasData && first beat, must claim a list

    assert (!(c.valid && c.bits.corrupt), "Data poisoning unavailable")

    io.set := Mux(c.valid, set, RegEnable(set, c.valid)) // finds us the way
    io.tag := Mux(c.valid, tag, RegEnable(tag, c.valid))
    io.is_flush := Mux(c.valid, raw_isFlush && first && hasData, RegEnable(raw_isFlush && first && hasData, c.valid))

    // Cut path from inner C to the BankedStore SRAM setup
    //   ... this makes it easier to layout the L2 data banks far away
    val bs_adr = Wire(io.bs_adr)
    io.bs_adr <> Queue(bs_adr, 1, pipe=true)
    io.bs_dat.data   := RegEnable(c.bits.data,    bs_adr.fire())
    bs_adr.valid     := ((resp && !isFlush) || (isFlush && io.way_valid)) && (!first || (c.valid && hasData))
    dontTouch(resp)
    //bs_adr.valid     := (isFlush || resp) && (!first || (c.valid && hasData))
    bs_adr.bits.noop := !c.valid
    bs_adr.bits.way  := io.way
    bs_adr.bits.set  := io.set
    bs_adr.bits.beat := Mux(c.valid, beat, RegEnable(beat + bs_adr.ready.asUInt, c.valid))
    bs_adr.bits.mask := ~UInt(0, width = params.innerMaskBits)
    params.ccover(bs_adr.valid && !bs_adr.ready, "SINKC_SRAM_STALL", "Data SRAM busy")

    dontTouch(bs_adr)
    dontTouch(first)
    dontTouch(resp)

    //io.resp.valid := resp && !isFlush && c.valid && (first || last) && (!hasData || bs_adr.ready) //no dirty!
    io.resp.valid := resp && c.valid && (first || last) && (!hasData || bs_adr.ready)
    io.resp.bits.last   := last
    io.resp.bits.set    := set
    io.resp.bits.tag    := tag
    io.resp.bits.source := c.bits.source
    io.resp.bits.param  := c.bits.param
    io.resp.bits.data   := hasData

    val putbuffer = Module(new ListBuffer(ListBufferParameters(new PutBufferCEntry(params), params.relLists, params.relBeats, false)))
    val lists = RegInit(UInt(0, width = params.relLists))

    val lists_set = Wire(init = UInt(0, width = params.relLists))
    val lists_clr = Wire(init = UInt(0, width = params.relLists))
    lists := (lists | lists_set) & ~lists_clr

    val free = !lists.andR
    val freeOH = ~(leftOR(~lists) << 1) & ~lists
    val freeIdx = OHToUInt(freeOH)

    val req_block = first && !io.req.ready
    val buf_block = hasData && !putbuffer.io.push.ready
    val set_block = hasData && first && !free

    params.ccover(c.valid && !(raw_resp && !isFlush) && req_block, "SINKC_REQ_STALL", "No MSHR available to sink request")
    params.ccover(c.valid && !(raw_resp && !isFlush) && buf_block, "SINKC_BUF_STALL", "No space in putbuffer for beat")
    params.ccover(c.valid && !(raw_resp && !isFlush) && set_block, "SINKC_SET_STALL", "No space in putbuffer for request")

    c.ready := Mux(raw_resp, Mux(!raw_isFlush, !hasData || bs_adr.ready,
                   (!hasData && !req_block) || (hasData && bs_adr.ready && io.way_valid && !req_block)),
                   !req_block && !buf_block && !set_block)


    val debug_c = Wire(Decoupled(new TLBundleC(params.inner.bundle)))
    debug_c.valid := c.valid
    debug_c.ready := c.ready
    debug_c.bits := c.bits
    dontTouch(debug_c)
    //c.ready := Mux(raw_resp, !hasData || bs_adr.ready && io.way_valid, !req_block && !buf_block && !set_block)
    //c.ready := Mux(raw_resp, !hasData || bs_adr.ready, !req_block && !buf_block && !set_block)

    val req_valid = (!resp || isFlush && !flushed) && c.valid && first && (isFlush || (!buf_block && !set_block))
    putbuffer.io.push.valid := !resp && c.valid && hasData && !req_block && !set_block
    when (!resp && c.valid && first && hasData && !req_block && !buf_block) { lists_set := freeOH }

    io.req.valid := req_valid
    val put = Mux(first, freeIdx, RegEnable(freeIdx, first))

    io.req.bits.prio   := Mux(isFlush, Vec(UInt(1, width=3).asBools), Vec(UInt(4, width=3).asBools))
    io.req.bits.control:= isFlush
    io.req.bits.control1 := isFlushWB
    io.req.bits.opcode := c.bits.opcode
    io.req.bits.param  := c.bits.param
    io.req.bits.size   := c.bits.size
    io.req.bits.source := c.bits.source
    io.req.bits.offset := offset
    io.req.bits.set    := set
    io.req.bits.tag    := tag
    io.req.bits.put    := put

    io.bs_set := io.bs_adr.bits.set

    putbuffer.io.push.bits.index := put
    putbuffer.io.push.bits.data.data    := c.bits.data
    putbuffer.io.push.bits.data.corrupt := c.bits.corrupt

    when (io.req.fire() && isFlush) {
      flushed := Bool(true)
    } 
    
    when (isFlush && last) {
      flushed := Bool(false)
    }

    when (io.way_valid) {
      way_valid := Bool(true)
    }

    when (last) {
      way_valid := Bool(false)
    }

    // Grant access to pop the data
    putbuffer.io.pop.bits := io.rel_pop.bits.index
    putbuffer.io.pop.valid := io.rel_pop.fire()
    io.rel_pop.ready := putbuffer.io.valid(io.rel_pop.bits.index)
    io.rel_beat := putbuffer.io.data

    when (io.rel_pop.fire() && io.rel_pop.bits.last) {
      lists_clr := UIntToOH(io.rel_pop.bits.index, params.relLists)
    }
  }
}
