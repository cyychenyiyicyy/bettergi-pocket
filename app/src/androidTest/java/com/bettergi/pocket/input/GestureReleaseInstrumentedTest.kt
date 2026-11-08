package com.bettergi.pocket.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 使用真实 Android 笔画及主线程，手动控制系统回调顺序；不向游戏注入触控。 */
@RunWith(AndroidJUnit4::class)
class GestureReleaseInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun <T> onMain(action: () -> T): T {
        val value = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        instrumentation.runOnMainSync {
            try { value.set(action()) } catch (error: Throwable) { failure.set(error) }
        }
        failure.get()?.let { throw it }
        return value.get()
    }

    private data class Dispatch(
        val gesture: GestureDescription,
        val callback: AccessibilityService.GestureResultCallback,
    )

    private inner class Harness {
        val pending = ArrayDeque<Dispatch>()
        var accept = true
        val composer = onMain {
            GestureComposer(Handler(Looper.getMainLooper())) { gesture, callback, _ ->
                if (accept) pending.addLast(Dispatch(gesture, callback!!))
                accept
            }
        }

        fun finish(cancel: Boolean = false) {
            onMain {
                val dispatch = pending.removeFirst()
                if (cancel) dispatch.callback.onCancelled(dispatch.gesture)
                else dispatch.callback.onCompleted(dispatch.gesture)
            }
            instrumentation.waitForIdleSync()
        }

        fun release(timeoutMs: Long = 2000L, interrupted: AtomicBoolean? = null): FutureTask<Boolean> {
            val task = FutureTask {
                if (interrupted != null) Thread.currentThread().interrupt()
                val ok = composer.releaseAllAndWait(timeoutMs)
                interrupted?.set(Thread.currentThread().isInterrupted)
                ok
            }
            val thread = Thread(task, "TouchReleaseTest")
            thread.start()
            // 等调用线程进入 latch 等待后，再处理主线程已入队的释放请求。
            val deadline = SystemClock.uptimeMillis() + 1000L
            while (!task.isDone && thread.state != Thread.State.TIMED_WAITING) {
                check(SystemClock.uptimeMillis() < deadline) { "释放线程未进入等待" }
                SystemClock.sleep(5)
            }
            instrumentation.waitForIdleSync()
            return task
        }
    }

    @Test
    fun releasingDuringPointerStreamResetDoesNotReplayHeldInputsOrQueuedClick() {
        val h = Harness()
        onMain { assertTrue(h.composer.padHold(100, 100)) }
        h.finish()
        onMain { assertTrue(h.composer.dragBegin(400, 400)) }
        h.finish()
        repeat(6) {
            onMain { assertTrue(h.composer.tap(700, 700, 30)) }
            h.finish()
        }
        onMain {
            assertTrue(h.composer.tap(700, 700, 30))
            val lift = h.pending.single().gesture
            assertEquals(2, lift.strokeCount)
            assertFalse(lift.getStroke(0).willContinue())
            assertFalse(lift.getStroke(1).willContinue())
        }
        val release = h.release()
        h.finish()
        assertTrue(release.get(1, TimeUnit.SECONDS))
        onMain {
            assertTrue(h.pending.isEmpty())
            assertFalse(h.composer.hasHold())
            assertTrue(h.composer.tap(800, 800, 30))
        }
        h.finish()
    }

    @Test
    fun repressedPadAlwaysStartsAfterContinuingViewStroke() {
        val h = Harness()
        onMain { assertTrue(h.composer.padHold(100, 100)) }
        h.finish()
        onMain { assertTrue(h.composer.dragBegin(400, 400)) }
        h.finish()
        onMain {
            assertTrue(h.composer.padRelease())
            // 旧 pad 尚未完成抬手，同号新 pad 在 LinkedHashMap 中仍位于 drag 前面。
            assertTrue(h.composer.padHold(200, 200))
        }
        h.finish()
        onMain {
            val next = h.pending.single().gesture
            assertEquals(2, next.strokeCount)
            assertEquals(0L, next.getStroke(0).startTime)
            assertEquals(1L, next.getStroke(1).startTime)
            val bounds = android.graphics.RectF()
            next.getStroke(0).path.computeBounds(bounds, true)
            assertEquals(400f, bounds.left)
            next.getStroke(1).path.computeBounds(bounds, true)
            assertEquals(200f, bounds.left)
        }
        h.finish()
        val release = h.release()
        h.finish()
        assertTrue(release.get(1, TimeUnit.SECONDS))
        onMain { assertFalse(h.composer.hasHold()) }
    }

    @Test
    fun pinchDispatchesTwoSynchronizedStrokesAndLiftsBoth() {
        val h = Harness()
        onMain {
            assertTrue(h.composer.pinch(1000, 700, 200, 600, 400))
            val gesture = h.pending.single().gesture
            assertEquals(2, gesture.strokeCount)
            for (index in 0..1) {
                val stroke = gesture.getStroke(index)
                assertEquals(0L, stroke.startTime)
                assertEquals(400L, stroke.duration)
                assertFalse(stroke.willContinue())
                val path = android.graphics.PathMeasure(stroke.path, false)
                val point = FloatArray(2)
                path.getPosTan(0f, point, null)
                assertEquals(if (index == 0) 900f else 1100f, point[0])
                path.getPosTan(path.length, point, null)
                assertEquals(if (index == 0) 700f else 1300f, point[0])
                assertEquals(700f, point[1])
            }
        }
        h.finish()
        onMain {
            assertTrue(h.pending.isEmpty())
            assertFalse(h.composer.hasHold())
        }
    }

    @Test
    fun queuedPinchKeepsBothFingersInOneGesture() {
        val h = Harness()
        onMain {
            assertTrue(h.composer.tap(100, 100, 50))
            assertTrue(h.composer.pinch(1000, 700, 600, 200, 400))
            assertEquals(1, h.pending.size)
        }
        h.finish()
        onMain {
            val gesture = h.pending.single().gesture
            assertEquals(2, gesture.strokeCount)
            assertEquals(gesture.getStroke(0).startTime, gesture.getStroke(1).startTime)
            assertEquals(gesture.getStroke(0).duration, gesture.getStroke(1).duration)
        }
        h.finish()
        onMain { assertTrue(h.pending.isEmpty()) }
    }

    @Test
    fun waitsForLiftCallbackAndDiscardsQueuedTap() {
        val h = Harness()
        onMain {
            assertTrue(h.composer.padHold(100, 100))
            assertTrue(h.composer.tap(200, 200, 50))
        }
        val release = h.release()
        assertFalse(release.isDone)
        onMain { assertFalse(h.composer.tap(300, 300, 50)) }
        h.finish() // 先完成旧按住段，才能续接抬手。
        onMain {
            assertEquals(1, h.pending.size)
            val lift = h.pending.first().gesture
            assertEquals(1, lift.strokeCount) // 已排队的点按没有混进释放笔画。
            assertFalse(lift.getStroke(0).willContinue())
            assertTrue(h.composer.hasHold())
        }
        assertFalse(release.isDone)
        h.finish()
        assertTrue(release.get(1, TimeUnit.SECONDS))
        onMain {
            assertFalse(h.composer.hasHold())
            assertTrue(h.pending.isEmpty())
            assertTrue(h.composer.tap(300, 300, 50))
        }
        h.finish()
    }

    @Test
    fun cancelledMultiTouchReleaseDoesNotRepressOrClaimSuccessOnRetry() {
        val h = Harness()
        onMain {
            assertTrue(h.composer.padHold(100, 100))
            assertTrue(h.composer.dragBegin(400, 400))
        }
        h.finish()
        // 两个触点的按住段尚在执行，再排一个视角移动，释放应丢弃这个移动。
        onMain { assertTrue(h.composer.dragStep(450, 450, 80)) }
        val release = h.release()
        h.finish()
        onMain {
            val gesture = h.pending.first().gesture
            assertEquals(2, gesture.strokeCount)
            assertFalse(gesture.getStroke(0).willContinue())
            assertFalse(gesture.getStroke(1).willContinue())
            val bounds = android.graphics.RectF()
            gesture.getStroke(1).path.computeBounds(bounds, true)
            assertEquals(400f, bounds.left)
            assertEquals(400f, bounds.top)
            assertEquals(400f, bounds.right)
            assertEquals(400f, bounds.bottom)
        }
        h.finish(cancel = true)
        assertFalse(release.get(1, TimeUnit.SECONDS))
        assertFalse(h.release().get(1, TimeUnit.SECONDS))
        onMain {
            assertTrue(h.pending.isEmpty())
            assertFalse(h.composer.tap(300, 300, 50))
        }
    }

    @Test
    fun rejectedLiftKeepsOldStrokeForRetry() {
        val h = Harness()
        onMain { assertTrue(h.composer.padHold(100, 100)) }
        h.finish()
        onMain { h.accept = false }
        assertFalse(h.release().get(1, TimeUnit.SECONDS))
        onMain {
            assertTrue(h.composer.hasHold())
            assertFalse(h.composer.padHold(200, 200))
            h.accept = true
        }
        val retry = h.release()
        assertFalse(retry.isDone)
        h.finish()
        assertTrue(retry.get(1, TimeUnit.SECONDS))
        onMain { assertFalse(h.composer.hasHold()) }
    }

    @Test
    fun timeoutBlocksNewInputUntilLateLiftCompletion() {
        val h = Harness()
        onMain { assertTrue(h.composer.padHold(100, 100)) }
        h.finish()
        assertFalse(h.release(100).get(1, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        onMain {
            assertTrue(h.composer.hasHold())
            assertFalse(h.composer.tap(300, 300, 50))
        }
        h.finish()
        assertTrue(h.release().get(1, TimeUnit.SECONDS))
        onMain { assertFalse(h.composer.hasHold()) }
    }

    @Test
    fun interruptedWorkerStillWaitsForInstantAndRestoresInterruptFlag() {
        val h = Harness()
        onMain { assertTrue(h.composer.tap(100, 100, 100)) }
        val interrupted = AtomicBoolean()
        val release = h.release(interrupted = interrupted)
        assertFalse(release.isDone)
        h.finish()
        assertTrue(release.get(1, TimeUnit.SECONDS))
        assertTrue(interrupted.get())
    }

    @Test
    fun cancelledInstantWithNoHoldDoesNotReportAnEmptyListAsReleased() {
        val h = Harness()
        onMain { assertTrue(h.composer.tap(100, 100, 100)) }
        val release = h.release()
        h.finish(cancel = true)
        assertFalse(release.get(1, TimeUnit.SECONDS))
        assertFalse(h.release().get(1, TimeUnit.SECONDS))
    }

    // ---- 外部取消（用户触屏）后的恢复能力 ----

    @Test
    fun externalCancelDuringReleaseIsRecoverableAfterReset() {
        val h = Harness()
        onMain { assertTrue(h.composer.padHold(100, 100)) }
        h.finish()
        val release = h.release()
        onMain { assertEquals(1, h.pending.size) }
        h.finish(cancel = true) // 抬手笔画被系统取消，例如用户手指触屏
        assertFalse(release.get(1, TimeUnit.SECONDS))
        onMain {
            // 未修复时这里会永远 false，只能重新开关无障碍服务；修复后 reset 即可恢复。
            assertFalse(h.composer.tap(300, 300, 50))
            assertTrue(h.composer.reset())
        }
        val retry = h.release()
        assertTrue(retry.get(1, TimeUnit.SECONDS))
        onMain { assertTrue(h.composer.tap(300, 300, 50)) }
        h.finish()
    }

    @Test
    fun externalCancelWhileHoldingRebuildsAfterDelay() {
        val h = Harness()
        onMain { assertTrue(h.composer.padHold(100, 100)) }
        h.finish()
        onMain { assertTrue(h.composer.dragBegin(400, 400)) }
        h.finish(cancel = true) // 用户触屏取消当前手势
        // 不能立刻重新 DOWN（会再次被取消并连锁），要等延迟重建。
        onMain { assertTrue(h.pending.isEmpty()) }
        SystemClock.sleep(REBUILD_WAIT_MS)
        instrumentation.waitForIdleSync()
        onMain {
            assertEquals(1, h.pending.size)
            assertEquals(2, h.pending.first().gesture.strokeCount)
        }
        h.finish()
        onMain { assertTrue(h.composer.padHold(150, 150)) }
        h.finish()
    }

    @Test
    fun dragStepAfterExternalCancelRepressesInsteadOfFailing() {
        val h = Harness()
        onMain { assertTrue(h.composer.padHold(100, 100)) }
        h.finish()
        onMain { assertTrue(h.composer.dragBegin(400, 400)) }
        h.finish()
        onMain {
            assertTrue(h.composer.reset())
            // 记录已清空时 dragStep 不能返回 false，否则执行器会抛"视角手势派发失败"中断整条路线。
            assertTrue(h.composer.dragStep(450, 450, 80))
        }
        onMain { assertEquals(1, h.pending.size) }
        h.finish()
    }

    @Test
    fun repeatedExternalCancelsClearHoldButKeepServiceUsable() {
        val h = Harness()
        onMain { assertTrue(h.composer.padHold(100, 100)) }
        repeat(4) {
            h.finish(cancel = true)
            SystemClock.sleep(REBUILD_WAIT_MS)
            instrumentation.waitForIdleSync()
        }
        onMain {
            // 连续取消超过上限后丢弃本地记录，但不能把服务留在不可恢复状态。
            assertFalse(h.composer.hasHold())
            assertTrue(h.composer.padHold(200, 200))
        }
        h.finish()
    }

    @Test
    fun lostDispatchCallbackIsRecoveredByWatchdog() {
        val h = Harness()
        onMain { assertTrue(h.composer.padHold(100, 100)) }
        // 系统既不回调 onCompleted 也不回调 onCancelled：旧实现会永远停在 dispatching。
        SystemClock.sleep(WATCHDOG_WAIT_MS)
        instrumentation.waitForIdleSync()
        onMain {
            assertFalse(h.composer.hasHold())
            assertTrue(h.composer.padHold(200, 200))
        }
        h.finish()
        val release = h.release()
        onMain { assertEquals(1, h.pending.size) }
        h.finish()
        assertTrue(release.get(1, TimeUnit.SECONDS))
    }

    private companion object {
        /** 外部取消后的延迟重建等待时间，需大于 GestureComposer.REBUILD_DELAY_MS。 */
        const val REBUILD_WAIT_MS = 300L

        /** 派发看门狗等待时间，需大于 GestureComposer.DISPATCH_TIMEOUT_MS。 */
        const val WATCHDOG_WAIT_MS = 3500L
    }
}
