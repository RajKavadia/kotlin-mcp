package mcp.mutate

import com.sun.jdi.ClassType
import com.sun.jdi.IncompatibleThreadStateException
import com.sun.jdi.InvocationException
import com.sun.jdi.Method
import com.sun.jdi.ObjectReference
import com.sun.jdi.ReferenceType
import com.sun.jdi.ThreadReference
import com.sun.jdi.Value
import com.sun.jdi.VirtualMachine
import mcp.session.leaveOneSuspend
import mcp.session.suspendThreadByEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import mcp.inspect.renderValue

/**
 * Phase 2 value model: JSON in, JDWP mirror out.
 * First version supports what the spec promises: strings, numbers, booleans, null.
 * Anything else is a clear error (enums/Bundle/Intent-extras land in Phase 6).
 */
fun mirrorValue(vm: VirtualMachine, json: JsonElement, targetType: String): Value? {
    if (json is JsonNull) {
        if (isPrimitive(targetType)) {
            throw IllegalArgumentException("null cannot go into primitive '$targetType'.")
        }
        return null
    }
    val p = json as? JsonPrimitive
        ?: throw IllegalArgumentException(
            "Only strings, numbers, booleans and null are supported in Phase 2 " +
                "(got ${json::class.simpleName} for '$targetType')."
        )
    return when {
        p.isString -> when {
            targetType == "java.lang.String" -> vm.mirrorOf(p.content)
            targetType == "char" || targetType == "java.lang.Character" ->
                if (p.content.length == 1) vm.mirrorOf(p.content[0])
                else throw IllegalArgumentException("Need one char for '$targetType', got \"${p.content}\".")
            // Lenient: numeric/boolean strings convert when the target demands it.
            isNumeric(targetType) -> mirrorNumber(vm, p.content, targetType)
            targetType == "boolean" || targetType == "java.lang.Boolean" ->
                p.content.toBooleanStrictOrNull()?.let { vm.mirrorOf(it) }
                    ?: throw IllegalArgumentException("Need boolean for '$targetType', got \"${p.content}\".")
            else -> throw IllegalArgumentException(
                "Cannot put string \"${p.content}\" into '$targetType'. Phase 2 targets: String/number/boolean."
            )
        }
        p.booleanOrNull != null -> when (targetType) {
            "boolean", "java.lang.Boolean" -> vm.mirrorOf(p.booleanOrNull!!)
            else -> throw IllegalArgumentException("Cannot put boolean into '$targetType'.")
        }
        else -> mirrorNumber(vm, p.content, targetType) // JSON number
    }
}

private fun isPrimitive(t: String) = t in setOf(
    "boolean", "byte", "char", "short", "int", "long", "float", "double", "void"
)

private fun isNumeric(t: String) = t in setOf(
    "byte", "java.lang.Byte", "short", "java.lang.Short",
    "int", "java.lang.Integer", "long", "java.lang.Long",
    "float", "java.lang.Float", "double", "java.lang.Double",
)

private fun mirrorNumber(vm: VirtualMachine, raw: String, targetType: String): Value {
    try {
        return when (targetType) {
            "byte", "java.lang.Byte" -> vm.mirrorOf(raw.toByte())
            "short", "java.lang.Short" -> vm.mirrorOf(raw.toShort())
            "int", "java.lang.Integer" -> vm.mirrorOf(raw.toInt())
            "long", "java.lang.Long" -> vm.mirrorOf(raw.toLong())
            "float", "java.lang.Float" -> vm.mirrorOf(raw.toFloat())
            "double", "java.lang.Double" -> vm.mirrorOf(raw.toDouble())
            "char", "java.lang.Character" ->
                if (raw.length == 1) vm.mirrorOf(raw[0])
                else throw IllegalArgumentException("Need one char, got \"$raw\".")
            "java.lang.String" -> vm.mirrorOf(raw)
            else -> throw IllegalArgumentException(
                "Cannot put number $raw into '$targetType'. Phase 2 targets: String/number/boolean."
            )
        }
    } catch (e: NumberFormatException) {
        throw IllegalArgumentException("Cannot put $raw into '$targetType': not a number.")
    }
}

/** Resolves the receiver: static type, or one live instance (single or named id). */
internal fun resolveReceiver(
    vm: VirtualMachine,
    className: String,
    instanceUniqueId: String?,
    needInstance: Boolean,
    matchField: String? = null,
    matchValue: String? = null,
): Receiver {
    val types = vm.classesByName(className)
    if (types.isEmpty()) {
        throw IllegalArgumentException(
            "Class '$className' not loaded. Use the fully qualified name as in the debug build " +
                "(R8 may rename it)."
        )
    }
    val type = types.first()
    if (!needInstance) return Receiver.Static(type)
    val max = if (instanceUniqueId != null || matchField != null) 200L else 2L
    val insts = types.flatMap { t ->
        try {
            t.instances(max)
        } catch (_: UnsupportedOperationException) {
            throw IllegalArgumentException(
                "Heap instance scan unsupported on this runtime. Pass `instance` (object id) explicitly."
            )
        }
    }
    if (insts.isEmpty()) {
        throw IllegalArgumentException(
            "No live instances of '$className' right now. Navigate to the screen that creates it first."
        )
    }
    if (matchField != null) {
        val wanted = matchValue ?: ""
        val hits = insts.filter { fieldText(it, matchField) == wanted }
        if (hits.isEmpty()) {
            throw IllegalArgumentException(
                "No live '$className' with $matchField=$wanted. " +
                    "Saw: ${insts.joinToString { "${it.uniqueID()}=${fieldText(it, matchField)}" }}."
            )
        }
        if (hits.size > 1) {
            throw IllegalArgumentException(
                "${hits.size} instances of '$className' have $matchField=$wanted. " +
                    "Pass `instance`: ${hits.joinToString { it.uniqueID().toString() }}."
            )
        }
        return Receiver.Instance(type, hits.first())
    }
    if (insts.size == 1 && instanceUniqueId == null) return Receiver.Instance(type, insts.first())
    if (instanceUniqueId == null) {
        throw IllegalArgumentException(
            "${insts.size} live instances of '$className'. " +
                "Re-call with `instance` set to one id: " +
                insts.joinToString { "${it.uniqueID()} (${renderValue(it)})" } + "."
        )
    }
    val target = insts.firstOrNull { it.uniqueID() == instanceUniqueId.toLongOrNull() }
        ?: throw IllegalArgumentException(
            "No live instance id '$instanceUniqueId' on '$className'. " +
                "Live: ${insts.joinToString { "${it.uniqueID()} (${renderValue(it)})" }}."
        )
    return Receiver.Instance(type, target)
}

private fun fieldText(obj: ObjectReference, name: String): String? {
    val field = try {
        obj.referenceType().fieldByName(name)
    } catch (_: Exception) {
        null
    } ?: return null
    val value = try {
        obj.getValue(field)
    } catch (_: Exception) {
        return null
    }
    return when (value) {
        null -> "null"
        is com.sun.jdi.StringReference -> value.value()
        else -> renderValue(value)
    }
}

sealed interface Receiver {
    data class Static(val type: ReferenceType) : Receiver
    data class Instance(val type: ReferenceType, val obj: ObjectReference) : Receiver
}

/**
 * `set_field`: write one existing field, then read it back.
 * Refuses `final` (incl. Kotlin `val` backing fields) with the setter to call instead.
 * Compose note: writing a private backing field changes memory but may not recompose —
 * verify with read_state and prefer the state's setter via call_method (helper in Phase 7).
 */
fun setField(
    vm: VirtualMachine,
    className: String,
    field: String,
    valueJson: JsonElement,
    instanceUniqueId: String?,
): String {
    val types = vm.classesByName(className)
    if (types.isEmpty()) throw IllegalArgumentException("Class '$className' not loaded (see read_state hint).")
    val type = types.first()
    val f = type.fieldByName(field)
        ?: throw IllegalArgumentException(
            "Field '$field' not found on '$className'. " +
                "Fields JDI sees: ${type.allFields().take(30).joinToString { it.name() }}."
        )
    if (f.isFinal) {
        throw IllegalArgumentException(
            "Field '$field' on '$className' is final" +
                (if (f.isPrivate) " (Kotlin `val` has no setter)" else "") +
                " — assignment refused. Call an existing setter/method via call_method instead."
        )
    }
    val value = try {
        mirrorValue(vm, valueJson, f.typeName())
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Bad `value` for '$field' (${f.typeName()}): ${e.message}")
    }
    try {
        if (f.isStatic) {
            (type as? ClassType)?.setValue(f, value)
                ?: throw IllegalArgumentException("Static write needs a ClassType for '$className'.")
        } else {
            when (val r = resolveReceiver(vm, className, instanceUniqueId, needInstance = true)) {
                is Receiver.Instance -> r.obj.setValue(f, value)
                is Receiver.Static -> throw IllegalArgumentException("unreachable")
            }
        }
    } catch (e: IllegalArgumentException) {
        throw e // already actionable
    } catch (e: Exception) {
        throw IllegalStateException("Write failed: ${e.message}")
    }
    // Read-back: the proof the write landed.
    val back = if (f.isStatic) type.getValue(f)
    else (resolveReceiver(vm, className, instanceUniqueId, needInstance = true) as Receiver.Instance).obj.getValue(f)
    return "${f.name()} = ${renderValue(back)} (read back after write)"
}

/** One overload candidate rendered for ambiguity errors. Pure, unit-testable. */
fun signatureOf(m: Method): String =
    "${m.name()}(${m.argumentTypeNames().joinToString(", ")}) : ${m.returnTypeName()}"

internal fun pickOverload(candidates: List<Method>, argCount: Int): Method {
    val byCount = candidates.filter {
        val n = it.argumentTypeNames().size
        n == argCount || (it.isVarArgs && argCount >= n - 1)
    }
    if (byCount.isEmpty()) {
        throw IllegalArgumentException(
            "No overload of '${candidates.firstOrNull()?.name()}' takes $argCount argument(s). " +
                "Candidates: ${candidates.joinToString { signatureOf(it) }}."
        )
    }
    if (byCount.size > 1) {
        throw IllegalArgumentException(
            "${byCount.size} overloads take $argCount argument(s) — Phase 2 needs an exact-type match. " +
                "Candidates: ${byCount.joinToString { signatureOf(it) }}."
        )
    }
    return byCount.first()
}

/**
 * `call_method`: invoke one existing method on the suspended [thread], return the
 * result rendering — or the exception the app itself threw (as a failure value,
 * transport stays up). A plain VM pause is not enough for ART: on
 * [IncompatibleThreadStateException], [wake] runs and the call is retried
 * after the thread stops on a debugger event.
 */
fun callMethod(
    vm: VirtualMachine,
    thread: ThreadReference,
    className: String,
    method: String,
    argsJson: List<JsonElement>,
    instanceUniqueId: String?,
    resumeAllThreads: Boolean = false,
    wake: (() -> Unit)? = null,
    matchField: String? = null,
    matchValue: String? = null,
): String {
    val types = vm.classesByName(className)
    if (types.isEmpty()) throw IllegalArgumentException("Class '$className' not loaded (see read_state hint).")
    val type = types.first()
    val named = type.methodsByName(method).filter { !it.isConstructor }
    if (named.isEmpty()) {
        throw IllegalArgumentException(
            "Method '$method' not found on '$className'. " +
                "Methods JDI sees: ${type.allMethods().take(40).joinToString { it.name() }}."
        )
    }
    // Prefer an instance receiver when one is available; else a static method.
    val instance: ObjectReference? = try {
        (resolveReceiver(
            vm, className, instanceUniqueId, needInstance = true, matchField, matchValue,
        ) as? Receiver.Instance)?.obj
    } catch (e: IllegalArgumentException) {
        if (instanceUniqueId != null || matchField != null) throw e
        null
    }
    val pool = if (instance != null) named.filter { !it.isStatic } else named.filter { it.isStatic }
    if (pool.isEmpty()) {
        throw IllegalArgumentException(
            if (instance != null) "No instance method '$method' on '$className' (only statics?)."
            else "No static method '$method' and no live instance to call it on. " +
                "Navigate to the creating screen first or pass `instance`."
        )
    }
    val target = pickOverload(pool, argsJson.size)
    val argTypes = target.argumentTypeNames()
    val jdiArgs: List<Value?> = argsJson.mapIndexed { i, j ->
        val t = if (target.isVarArgs && i >= argTypes.size - 1) argTypes.last() else argTypes[i]
        try {
            mirrorValue(vm, j, t)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Bad argument ${i + 1} for ${signatureOf(target)}: ${e.message}")
        }
    }
    // Varargs trailing params: Phase 2 passes them positionally; ART handles the array pack.
    // (Full varargs array packing lands in Phase 6 with richer types.)
    val threadState = try {
        "suspended=${thread.isSuspended()}, suspendCount=${thread.suspendCount()}"
    } catch (e: Exception) {
        "state-unknown: ${e.message}"
    }
    fun invokeOnce(options: Int): String {
        val result: Value? = if (target.isStatic) {
            (type as? ClassType)?.invokeMethod(thread, target, jdiArgs, options)
        } else {
            instance!!.invokeMethod(thread, target, jdiArgs, options)
        }
        return if (target.returnTypeName() == "void") "ok (void)" else "returned ${renderValue(result)}"
    }

    val pausedOptions = if (resumeAllThreads) 0 else ObjectReference.INVOKE_SINGLE_THREADED
    var eventSuspended = false
    return try {
        try {
            invokeOnce(pausedOptions)
        } catch (e: IncompatibleThreadStateException) {
            if (wake == null) {
                throw IllegalStateException(
                    "Invocation failed [${e.javaClass.name}] (thread $threadState): ${e.message}"
                )
            }
            // Event suspend, then let other threads run during the call so a
            // method that needs a lock they hold does not deadlock.
            suspendThreadByEvent(vm, thread, wake)
            eventSuspended = true
            try {
                invokeOnce(0) + " (event-suspended)"
            } catch (again: IncompatibleThreadStateException) {
                throw IllegalStateException(
                    "Invocation failed after an event suspend [${again.javaClass.name}] " +
                        "(thread suspended=${thread.isSuspended()}, suspendCount=${thread.suspendCount()}): ${again.message}"
                )
            }
        }
    } catch (e: InvocationException) {
        throw IllegalStateException("App threw ${renderAppException(e.exception())}")
    } catch (e: IllegalStateException) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: Exception) {
        throw IllegalStateException("Invocation failed [${e.javaClass.name}] (thread $threadState): ${e.message}")
    } finally {
        if (eventSuspended) leaveOneSuspend(vm, thread)
    }
}

/** Best-effort rendering of the app's own exception: type + detailMessage field. */
internal fun renderAppException(ex: ObjectReference): String {
    val type = try {
        ex.referenceType().name()
    } catch (_: Exception) {
        "Exception"
    }
    val msg = try {
        val f = ex.referenceType().fieldByName("detailMessage") ?: return type
        (ex.getValue(f) as? com.sun.jdi.StringReference)?.value() ?: return type
    } catch (_: Exception) {
        return type
    }
    return "$type: $msg"
}

/** Extracts the positional `arguments` array; missing/empty = no-arg call. */
fun argsList(argsJson: JsonElement?): List<JsonElement> =
    (argsJson as? JsonArray)?.toList() ?: emptyList()
