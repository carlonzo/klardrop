package com.carlom.klardrop.common.features

import TestCoroutines
import app.cash.turbine.test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ClipboardManagerTest {

  private class FakeClipboardSource(
    var current: String = "",
    val signalFlow: Flow<Unit>? = null,
  ) : ClipboardSource {
    var readCalls: Int = 0
    var readForSyncCalls: Int = 0
    var written: String = ""

    override fun read(): String {
      readCalls++
      return current
    }

    override fun readForSync(): String {
      readForSyncCalls++
      return current
    }

    override fun write(text: String) {
      written = text
      current = text
    }

    override fun changeSignals(): Flow<Unit>? = signalFlow
  }

  @Test
  fun eventDrivenSignalPathEmitsOnSignalAndSuppressesDuplicates() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coroutines = TestCoroutines(dispatcher = dispatcher, ioDispatcher = dispatcher)
    val signals = MutableSharedFlow<Unit>()
    val fake = FakeClipboardSource(current = "hello", signalFlow = signals)
    val manager = ClipboardManager(coroutines, fake)

    manager.flow.test {
      // Initial emission on subscription
      assertEquals("hello", awaitItem())

      // Signal when value has not changed -> distinctUntilChanged suppresses duplicate
      signals.emit(Unit)
      runCurrent()
      expectNoEvents()

      // Signal after value changed -> emits new value
      fake.current = "world"
      signals.emit(Unit)
      assertEquals("world", awaitItem())

      // Signal with empty value -> ignored
      fake.current = ""
      signals.emit(Unit)
      runCurrent()
      expectNoEvents()

      // Signal with another non-empty value -> emitted
      fake.current = "final"
      signals.emit(Unit)
      assertEquals("final", awaitItem())

      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun pollingPathRunsWhenSignalsIsNull() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coroutines = TestCoroutines(dispatcher = dispatcher, ioDispatcher = dispatcher)
    val fake = FakeClipboardSource(current = "poll-init", signalFlow = null)
    val manager = ClipboardManager(coroutines, fake)

    manager.flow.test {
      // Initial polling emission
      assertEquals("poll-init", awaitItem())

      // Change value and advance virtual time by 500ms
      fake.current = "poll-update"
      advanceTimeBy(600)
      assertEquals("poll-update", awaitItem())

      // Value unchanged -> no duplicate emission
      advanceTimeBy(600)
      runCurrent()
      expectNoEvents()

      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun signalFailureFallsBackToPolling() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coroutines = TestCoroutines(dispatcher = dispatcher, ioDispatcher = dispatcher)
    val signals = kotlinx.coroutines.flow.flow<Unit> {
      throw IllegalStateException("Watch child crashed")
    }
    val fake = FakeClipboardSource(current = "init", signalFlow = signals)
    val manager = ClipboardManager(coroutines, fake)

    manager.flow.test {
      assertEquals("init", awaitItem())

      // Polling fallback picks up the first change
      fake.current = "polled-after-crash"
      advanceTimeBy(600)
      assertEquals("polled-after-crash", awaitItem())

      // A second change must also be picked up — guards against a 'poll once and stop' regression
      fake.current = "polled-second"
      advanceTimeBy(600)
      assertEquals("polled-second", awaitItem())

      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun downstreamExceptionIsNotSwallowed() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coroutines = TestCoroutines(dispatcher = dispatcher, ioDispatcher = dispatcher)
    val signals = MutableSharedFlow<Unit>()
    val fake = FakeClipboardSource(current = "test", signalFlow = signals)
    val manager = ClipboardManager(coroutines, fake)

    kotlin.test.assertFailsWith<CustomDownstreamException> {
      manager.flow.collect {
        throw CustomDownstreamException()
      }
    }
  }

  private class CustomDownstreamException : RuntimeException("downstream failed")
}
