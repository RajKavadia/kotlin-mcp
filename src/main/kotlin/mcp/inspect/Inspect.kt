package mcp.inspect

import com.sun.jdi.AbsentInformationException
import com.sun.jdi.ClassNotLoadedException
import com.sun.jdi.IncompatibleThreadStateException
import com.sun.jdi.InvalidStackFrameException
import com.sun.jdi.ObjectReference
import com.sun.jdi.PrimitiveValue
import com.sun.jdi.StringReference
import com.sun.jdi.ThreadReference
import com.sun.jdi.VirtualMachine

/** Short, agent-readable rendering of a JDWP value. No remote method invocation. */
fun renderValue(v: com.sun.jdi.Value?): String = when (v) {
    null -> "null"
    is StringReference -> "\"${v.value()}\""
    is PrimitiveValue -> v.underlyingValue().toString()
    is ObjectReference -> "<${v.referenceType().name()}#${v.uniqueID()}>"
    else -> v.toString()
}

private fun PrimitiveValue.underlyingValue(): Any = when (this) {
    is com.sun.jdi.BooleanValue -> value()
    is com.sun.jdi.ByteValue -> value()
    is com.sun.jdi.CharValue -> value()
    is com.sun.jdi.DoubleValue -> value()
    is com.sun.jdi.FloatValue -> value()
    is com.sun.jdi.IntegerValue -> value()
    is com.sun.jdi.LongValue -> value()
    is com.sun.jdi.ShortValue -> value()
    else -> this.toString()
}

/** One stack frame, agent-readable. */
data class FrameInfo(val method: String, val location: String)

/** Locals of the top frame; empty with a note when debug info is absent. */
data class LocalsInfo(val values: Map<String, String>, val note: String?)

/** Full Phase 1 read: top stack + locals + optional named field. */
data class StateReport(
    val topFrames: List<FrameInfo>,
    val locals: LocalsInfo,
    val namedField: String?,
)

/**
 * Reads the suspended [main] thread: top frames, top-frame locals, and optionally
 * one named field (static or single-live-instance). Pure over an attached VM;
 * suspend/resume is owned by SessionManager.
 */
fun readState(
    vm: VirtualMachine,
    main: ThreadReference,
    className: String?,
    field: String?,
    frameDepth: Int = 8,
    instanceUniqueId: String? = null,
): StateReport {
    val frames = try {
        main.frames().take(frameDepth).map {
            val m = try {
                it.location().method().run { declaringType().name() + "." + name() + signature() }
            } catch (_: Exception) {
                "<unknown>"
            }
            val loc = try {
                it.location().run { "${declaringType().name()}:${lineNumber()}" }
            } catch (_: Exception) {
                "<unknown>"
            }
            FrameInfo(m, loc)
        }
    } catch (_: IncompatibleThreadStateException) {
        emptyList()
    } catch (_: InvalidStackFrameException) {
        emptyList()
    }

    val locals = try {
        val frame = main.frame(0)
        try {
            val vars = frame.visibleVariables()
            LocalsInfo(vars.associate { it.name() to renderValue(frame.getValue(it)) }, null)
        } catch (_: AbsentInformationException) {
            LocalsInfo(
                emptyMap(),
                "No local-variable debug info in this build (needs debuggable, non-minified)."
            )
        }
    } catch (_: Exception) {
        LocalsInfo(emptyMap(), "Top frame unavailable.")
    }

    val named = if (className != null && field != null) readNamedField(vm, className, field, instanceUniqueId) else null
    return StateReport(frames, locals, named)
}

/** Reads one field; single-live-instance rule for instance fields. */
internal fun readNamedField(
    vm: VirtualMachine,
    className: String,
    field: String,
    instanceUniqueId: String? = null,
): String {
    val types = vm.classesByName(className)
    if (types.isEmpty()) {
        val hint = "Class '$className' not loaded. Use the fully qualified name as in the debug build " +
            "(R8 may rename it — list actual names via a broader read first)."
        return "error: $hint"
    }
    val type = types.first()
    val f = type.fieldByName(field)
        ?: return "error: Field '$field' not found on '$className'. " +
            "Fields JDI sees: ${type.allFields().take(30).joinToString { it.name() }}."
    return try {
        if (f.isStatic) {
            "${f.name()} = ${renderValue(type.getValue(f))} (static)"
        } else {
            val insts = try {
                type.instances(2)
            } catch (_: UnsupportedOperationException) {
                return "error: Heap instance scan unsupported on this runtime. " +
                    "Pass `instance` (object id) explicitly."
            } catch (e: Exception) {
                return "error: Instance scan failed: ${e.message}"
            }
            when {
                insts.isEmpty() -> "error: No live instances of '$className' right now. " +
                    "Navigate to the screen that creates it first."
                insts.size > 1 && instanceUniqueId == null -> "error: ${insts.size} live instances of '$className'. " +
                    "Re-call with `instance` set to one id: " +
                    insts.joinToString { "${it.uniqueID()} (${renderValue(it)})" } + "."
                else -> {
                    val target = if (insts.size == 1) insts.first()
                    else insts.firstOrNull { it.uniqueID() == instanceUniqueId?.toLongOrNull() }
                        ?: return "error: No live instance id '$instanceUniqueId' on '$className'. " +
                            "Live ids: ${insts.joinToString { it.uniqueID().toString() }}."
                    "${f.name()} = ${renderValue(target.getValue(f))}"
                }
            }
        }
    } catch (e: ClassNotLoadedException) {
        "error: Class not yet loaded for field access: ${e.message}"
    } catch (e: Exception) {
        "error: Field read failed: ${e.message}"
    }
}

/** Read-only thread dump: every thread + its top frame. */
data class ThreadInfo(val name: String, val status: String, val suspended: Boolean, val topFrame: String)

fun listThreads(vm: VirtualMachine): List<ThreadInfo> {
    val all = try {
        vm.allThreads()
    } catch (e: Exception) {
        return listOf(ThreadInfo("<unavailable>", "error: ${e.message}", false, ""))
    }
    return all.map { t ->
        val name = try {
            t.name()
        } catch (_: Exception) {
            "<unknown>"
        }
        val status = try {
            t.status().toString()
        } catch (_: Exception) {
            "UNKNOWN"
        }
        val suspended = try {
            t.isSuspended
        } catch (_: Exception) {
            false
        }
        val top = try {
            if (t.frameCount() > 0) {
                val loc = t.frame(0).location()
                "${loc.declaringType().name()}.${loc.method().name()}:${loc.lineNumber()}"
            } else "(no frames)"
        } catch (_: Exception) {
            "(thread not suspended or frames unavailable)"
        }
        ThreadInfo(name, status, suspended, top)
    }
}
