//package com.carlom.klardrop.common.utils
//
//import io.ktor.utils.io.*
//import io.ktor.utils.io.core.*
//import okio.Sink
//import okio.Source
//import okio.buffer
//import okio.use
//
//// Okio likes to use 8kb:
//// https://github.com/square/okio/blob/a94c678de4e8a21e53126d42a1a3d897daa56a4a/recipes/index.html#L1322
//private const val OKIO_RECOMMENDED_BUFFER_SIZE: Int = 8192
//
////@Suppress("NAME_SHADOWING")
////suspend fun ByteReadChannel.readFully(sink: Sink) {
////  val channel = this
////  sink.buffer().use { sink ->
////    while (!channel.isClosedForRead) {
////      // TODO: Allocating a new packet on every copy isn't great. Find a faster way to move bytes.
////      log("readFully", "Reading from channel")
////      val packet = channel.readRemaining(OKIO_RECOMMENDED_BUFFER_SIZE.toLong())
////      log("readFully", "Read ${packet.remaining} bytes. isEmpty: ${packet.isEmpty}")
////      while (!packet.isEmpty) {
////        sink.write(packet.readBytes())
////      }
////    }
////  }
////}
//
//@Suppress("NAME_SHADOWING")
//suspend fun ByteReadChannel.readFully(sink: Sink) {
//  val channel = this
//  sink.buffer().use { sink ->
//    while (!channel.isClosedForRead) {
//      // TODO: Allocating a new packet on every copy isn't great. Find a faster way to move bytes.
//      log("readFully", "Reading from channel")
//      val packet = channel.read { source, start, endExclusive ->  }
//      log("readFully", "Read ${packet.remaining} bytes. isEmpty: ${packet.isEmpty}")
//      while (!packet.isEmpty) {
//        sink.write(packet.readBytes())
//      }
//    }
//  }
//}
//
//@Suppress("NAME_SHADOWING")
//suspend fun ByteWriteChannel.writeAll(source: Source) {
//  val channel = this
//  var bytesRead: Int
//  val buffer = ByteArray(OKIO_RECOMMENDED_BUFFER_SIZE)
//
//  source.buffer().use { source ->
//    while (source.read(buffer).also { bytesRead = it } != -1 && !channel.isClosedForWrite) {
//      channel.writeFully(buffer, offset = 0, length = bytesRead)
//      channel.flush()
//    }
//  }
//}