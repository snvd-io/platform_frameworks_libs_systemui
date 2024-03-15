/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.app.tracing.coroutines

import com.android.app.tracing.TraceState.openSectionsOnCurrentThread
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner

/**
 * Helper util for asserting that the open trace sections on the current thread equal the passed
 * list of strings.
 */
private fun assertTraceEquals(vararg openTraceSections: String) {
    assertArrayEquals(openTraceSections, openSectionsOnCurrentThread())
}

/** Helper util for asserting that there are no open trace sections on the current thread. */
private fun assertTraceIsEmpty() = assertEquals(0, openSectionsOnCurrentThread().size)

@RunWith(BlockJUnit4ClassRunner::class)
class CoroutineTracingTest {
    @Test
    fun nestedTraceSectionsOnSingleThread() {
        runTest(TraceContextElement()) {
            val fetchData: suspend () -> String = {
                delay(1L)
                traceCoroutine("span-for-fetchData") {
                    assertTraceEquals("span-for-launch", "span-for-fetchData")
                }
                "stuff"
            }
            launch("span-for-launch") {
                assertEquals("stuff", fetchData())
                assertTraceEquals("span-for-launch")
            }
            assertTraceIsEmpty()
        }
    }

    private fun testTraceSectionsMultiThreaded(
        testContext: CoroutineContext,
        thread1Context: CoroutineContext,
        thread2Context: CoroutineContext
    ) {
        runTest(testContext) {
            val fetchData1: suspend () -> String = {
                assertTraceEquals("span-for-launch-1")
                delay(1L)
                traceCoroutine("span-for-fetchData-1") {
                    assertTraceEquals("span-for-launch-1", "span-for-fetchData-1")
                }
                assertTraceEquals("span-for-launch-1")
                "stuff-1"
            }

            val fetchData2: suspend () -> String = {
                assertTraceEquals(
                    "span-for-launch-1",
                    "span-for-launch-2",
                )
                delay(1L)
                traceCoroutine("span-for-fetchData-2") {
                    assertTraceEquals(
                        "span-for-launch-1",
                        "span-for-launch-2",
                        "span-for-fetchData-2"
                    )
                }
                assertTraceEquals(
                    "span-for-launch-1",
                    "span-for-launch-2",
                )
                "stuff-2"
            }

            val thread1 = newSingleThreadContext("thread-#1") + thread1Context
            val thread2 = newSingleThreadContext("thread-#2") + thread2Context

            launch("span-for-launch-1", thread1) {
                assertEquals("stuff-1", fetchData1())
                assertTraceEquals("span-for-launch-1")
                launch("span-for-launch-2", thread2) {
                    assertEquals("stuff-2", fetchData2())
                    assertTraceEquals("span-for-launch-1", "span-for-launch-2")
                }
                assertTraceEquals("span-for-launch-1")
            }
            assertTraceIsEmpty()

            // Launching without the trace extension won't result in traces
            launch(thread1) { assertTraceIsEmpty() }
            launch(thread2) { assertTraceIsEmpty() }
        }
    }

    @Test
    fun nestedTraceSectionsMultiThreaded1() {
        // Thread-#1 and Thread-#2 inherit TraceContextElement from the test context:
        testTraceSectionsMultiThreaded(
            testContext = TraceContextElement(),
            thread1Context = EmptyCoroutineContext,
            thread2Context = EmptyCoroutineContext
        )
    }

    @Test
    fun nestedTraceSectionsMultiThreaded2() {
        // Thread-#2 inherits the TraceContextElement from Thread-#1. The test context does not need
        // the trace context
        testTraceSectionsMultiThreaded(
            testContext = EmptyCoroutineContext,
            thread1Context = TraceContextElement(),
            thread2Context = EmptyCoroutineContext
        )
    }

    @Test
    fun nestedTraceSectionsMultiThreaded3() {
        // Thread-#2 overrides the TraceContextElement from Thread-#1 - but the merging context
        // should be a no-op. The test context does not need the trace context.
        testTraceSectionsMultiThreaded(
            testContext = EmptyCoroutineContext,
            thread1Context = TraceContextElement(),
            thread2Context = TraceContextElement()
        )
    }

    @Test
    fun nestedTraceSectionsMultiThreaded4() {
        // TraceContextElement is merged on each context switch, which should have no effect on the
        // trace results.
        testTraceSectionsMultiThreaded(
            testContext = TraceContextElement(),
            thread1Context = TraceContextElement(),
            thread2Context = TraceContextElement()
        )
    }

    @Test
    fun missingTraceContextObjects() {
        // Thread-#1 is missing a TraceContextElement, so some of the trace sections get dropped.
        // The resulting trace sections will be different than the 4 tests above.
        runTest {
            val fetchData1: suspend () -> String = {
                assertTraceIsEmpty()
                delay(1L)
                traceCoroutine("span-for-fetchData-1") { assertTraceIsEmpty() }
                assertTraceIsEmpty()
                "stuff-1"
            }

            val fetchData2: suspend () -> String = {
                assertTraceEquals(
                    "span-for-launch-2",
                )
                delay(1L)
                traceCoroutine("span-for-fetchData-2") {
                    assertTraceEquals("span-for-launch-2", "span-for-fetchData-2")
                }
                assertTraceEquals(
                    "span-for-launch-2",
                )
                "stuff-2"
            }

            val thread1 = newSingleThreadContext("thread-#1")
            val thread2 = newSingleThreadContext("thread-#2") + TraceContextElement()

            launch("span-for-launch-1", thread1) {
                assertEquals("stuff-1", fetchData1())
                assertTraceIsEmpty()
                launch("span-for-launch-2", thread2) {
                    assertEquals("stuff-2", fetchData2())
                    assertTraceEquals("span-for-launch-2")
                }
                assertTraceIsEmpty()
            }
            assertTraceIsEmpty()

            // Launching without the trace extension won't result in traces
            launch(thread1) { assertTraceIsEmpty() }
            launch(thread2) { assertTraceIsEmpty() }
        }
    }
}
