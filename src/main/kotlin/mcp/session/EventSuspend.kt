package mcp.session

import com.sun.jdi.Location
import com.sun.jdi.ThreadReference
import com.sun.jdi.VirtualMachine
import com.sun.jdi.event.BreakpointEvent
import com.sun.jdi.event.Event
import com.sun.jdi.event.StepEvent
import com.sun.jdi.request.EventRequest
import com.sun.jdi.request.StepRequest

/**
 * Methods the main looper re-enters after nativePollOnce returns.
 * loopOnce (API 31+) is preferred; loop and MessageQueue.next cover older runtimes.
 */
internal val LOOPER_STOPS = listOf(
    "android.os.Looper" to "loopOnce",
    "android.os.Looper" to "loop",
    "android.os.MessageQueue" to "next",
)

internal fun chooseLooperStop(usable: (className: String, method: String) -> Boolean): Pair<String, String>? =
    LOOPER_STOPS.firstOrNull { (cls, method) -> usable(cls, method) }

/**
 * ART rejects invokeMethod after VirtualMachine.suspend(). Resume, stop [thread]
 * on a breakpoint or a step-out, and return while that event suspend is held.
 * Caller must resume the VM afterwards.
 */
internal fun suspendThreadByEvent(vm: VirtualMachine, thread: ThreadReference, wake: () -> Unit) {
    val erm = vm.eventRequestManager()
    val request = stopRequest(vm, erm, thread)
    request.enable()
    try {
        // Drop the VirtualMachine.suspend() so the looper can run to the event.
        vm.resume()
        try {
            wake()
        } catch (_: Exception) { }
        if (!waitForStop(vm, thread, request, 8_000)) {
            throw IllegalStateException(
                "Main thread did not stop on a debugger event within 8s, so invokeMethod " +
                    "is still illegal. Leave the app in the foreground and retry."
            )
        }
    } finally {
        try {
            request.disable()
        } catch (_: Exception) { }
        try {
            erm.deleteEventRequest(request)
        } catch (_: Exception) { }
    }
}

/**
 * invokeMethod re-suspends every thread when it returns. Leave exactly one
 * suspend so SessionManager's finally resume actually lets the app run.
 */
internal fun leaveOneSuspend(vm: VirtualMachine, thread: ThreadReference) {
    repeat(4) {
        val count = try {
            thread.suspendCount()
        } catch (_: Exception) {
            return
        }
        if (count <= 1) return
        try {
            vm.resume()
        } catch (_: Exception) {
            return
        }
    }
}

private fun stopRequest(
    vm: VirtualMachine,
    erm: com.sun.jdi.request.EventRequestManager,
    thread: ThreadReference,
): EventRequest {
    val location = looperLocation(vm)
    if (location != null) {
        val bp = erm.createBreakpointRequest(location)
        bp.setSuspendPolicy(EventRequest.SUSPEND_ALL)
        bp.addThreadFilter(thread)
        bp.addCountFilter(1)
        return bp
    }
    val step = erm.createStepRequest(thread, StepRequest.STEP_MIN, StepRequest.STEP_OUT)
    step.setSuspendPolicy(EventRequest.SUSPEND_ALL)
    step.addCountFilter(1)
    return step
}

private fun looperLocation(vm: VirtualMachine): Location? {
    val choice = chooseLooperStop { cls, method ->
        vm.classesByName(cls).any { type ->
            type.methodsByName(method).any { m -> !m.isAbstract && !m.isNative && !m.isConstructor }
        }
    } ?: return null
    val (cls, method) = choice
    val target = vm.classesByName(cls).firstNotNullOfOrNull { type ->
        type.methodsByName(method).firstOrNull { m -> !m.isAbstract && !m.isNative && !m.isConstructor }
    } ?: return null
    return try {
        target.location()
    } catch (_: Exception) {
        null
    }
}

private fun waitForStop(
    vm: VirtualMachine,
    thread: ThreadReference,
    request: EventRequest,
    timeoutMs: Long,
): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    val queue = vm.eventQueue()
    while (true) {
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0) return false
        val set = try {
            queue.remove(remaining)
        } catch (_: InterruptedException) {
            return false
        } catch (_: Exception) {
            return false
        } ?: return false
        val hit = set.any { event: Event ->
            event.request() == request && eventThread(event)?.uniqueID() == thread.uniqueID()
        }
        if (hit) return true
        try {
            set.resume()
        } catch (_: Exception) { }
    }
}

private fun eventThread(event: Event): ThreadReference? = when (event) {
    is BreakpointEvent -> event.thread()
    is StepEvent -> event.thread()
    else -> null
}
