package mcp.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import mcp.adb.AdbClient
import mcp.adb.RealAdbRunner
import mcp.inspect.listThreads
import mcp.inspect.readState
import mcp.mutate.argsList
import mcp.mutate.callMethod
import mcp.mutate.setField
import mcp.session.SessionManager

/** MCP tool result. Failures are values (`isError=true`), never throws. */
data class CallResult(val text: String, val isError: Boolean = false)

/** Tool descriptor served on `tools/list`. */
data class ToolDef(val name: String, val description: String, val inputSchema: String)

/** Lenient arg readers: agents often send numbers as strings (e.g. pid "24113"). */
internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.let { if (it.isString) it.content else null }

internal fun JsonObject.intArg(key: String): Int? {
    val p = this[key] as? JsonPrimitive ?: return null
    return p.intOrNull ?: p.content.toIntOrNull()
}

internal fun JsonObject.boolArg(key: String, default: Boolean = false): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

/** One memory per server process, shared by every fresh [AdbClient]. */
private fun liveAdbFor(): (String?) -> AdbClient {
    val memory = mcp.adb.DiscoveryMemory()
    return { serial -> AdbClient(RealAdbRunner(20), serial, RealAdbRunner(2), memory) }
}

/** Dependencies a tool handler needs. Session attach is per-call, mutex-guarded. */
class ToolEnv(
    val adbFor: (serial: String?) -> AdbClient = liveAdbFor(),
    val sessions: SessionManager = SessionManager(adbFor),
)

val TOOL_DEFS = listOf(
    ToolDef(
        "list_debuggable_apps",
        "List debuggable Android processes (pid + package). Call first; pids come only from `adb jdwp` — release/Play builds never appear. Optional `serial` selects one device.",
        """{"type":"object","properties":{"serial":{"type":"string","description":"Device serial from `adb devices` when several are attached"}}}""",
    ),
    ToolDef(
        "read_state",
        "Read a running debug app: main-thread stack, top-frame locals, and optionally one named field (className+field). Read before a write and after one to verify. pid must come from list_debuggable_apps.",
        """{"type":"object","required":["pid"],"properties":{"pid":{"description":"Process id from list_debuggable_apps (number or string)"},"className":{"type":"string","description":"Fully qualified class declaring the field"},"field":{"type":"string","description":"Field name as in the debug build"},"instance":{"description":"Object id when several live instances exist (see error text)"},"serial":{"type":"string"},"frameDepth":{"type":"integer","description":"Stack frames to return, default 8"}}}""",
    ),
    ToolDef(
        "threads",
        "List threads + top frame of each on a debuggable process. Use when the UI is frozen. Read-only.",
        """{"type":"object","required":["pid"],"properties":{"pid":{"description":"Process id from list_debuggable_apps (number or string)"},"serial":{"type":"string"}}}""",
    ),
    ToolDef(
        "set_field",
        "Write one existing field in memory, then read it back. Refuses final/val fields (call a setter via call_method instead). pid must come from list_debuggable_apps.",
        """{"type":"object","required":["pid","className","field","value"],"properties":{"pid":{"description":"Process id from list_debuggable_apps (number or string)"},"className":{"type":"string","description":"Fully qualified class declaring the field"},"field":{"type":"string","description":"Field name as in the debug build"},"value":{"description":"New value: string, number, boolean or null"},"instance":{"description":"Object id when several live instances exist (see error text)"},"serial":{"type":"string"}}}""",
    ),
    ToolDef(
        "call_method",
        "Invoke one existing method on a live object (or a static method) on the main thread. Returns the result or the exception the app threw. The method must already exist — nothing is compiled. ART requires a debugger event before invoke; the tool performs that stop and wakes the main looper. A successful retry ends with (event-suspended). Object ids do not survive the next tool call; pass instanceField and instanceValue to pick an instance in this same call.",
        """{"type":"object","required":["pid","className","method"],"properties":{"pid":{"description":"Process id from list_debuggable_apps (number or string)"},"className":{"type":"string","description":"Class of the receiver"},"method":{"type":"string","description":"Method that already exists"},"arguments":{"type":"array","description":"Values for the existing parameters (string/number/boolean/null)"},"instance":{"description":"Object id from an error in this same server process. Ids from an earlier tool call are stale."},"instanceField":{"type":"string","description":"Field used to choose the instance in this call, such as mText"},"instanceValue":{"type":"string","description":"Required field value, such as Open settings"},"resumeAll":{"type":"boolean","description":"Resume the whole VM during the call when the runtime rejects single-threaded invocation (default false)"},"serial":{"type":"string"}}}""",
    ),
)

fun handleTool(env: ToolEnv, name: String, args: JsonObject): CallResult = try {
    when (name) {
        "list_debuggable_apps" -> listApps(env, args)
        "read_state" -> readStateTool(env, args)
        "threads" -> threadsTool(env, args)
        "set_field" -> setFieldTool(env, args)
        "call_method" -> callMethodTool(env, args)
        else -> CallResult("Unknown tool '$name'. Available: ${TOOL_DEFS.joinToString { it.name }}.", true)
    }
} catch (e: Exception) {
    CallResult("Tool '$name' failed unexpectedly: ${e.message}", true)
}

private fun listApps(env: ToolEnv, args: JsonObject): CallResult {
    val serial = args.str("serial")
    val adb = env.adbFor(serial)
    if (adb.devices().isEmpty()) {
        return CallResult(
            "No devices attached. Connect an emulator or phone (USB debugging on, RSA authorized) " +
                "so `adb devices` lists it, then retry.",
            true,
        )
    }
    val apps = adb.listDebuggableApps().getOrElse { return CallResult(it.message ?: "Discovery failed.", true) }
    val lines = apps.joinToString("\n") {
        "pid=${it.pid} package=${it.packageName ?: "<unknown>"}${if (serial != null) " serial=$serial" else ""}"
    }
    return CallResult("Debuggable processes (${apps.size}, via ${adb.lastDiscovery}):\n$lines")
}

private fun readStateTool(env: ToolEnv, args: JsonObject): CallResult {
    val pid = args.intArg("pid")
        ?: return CallResult("Provide `pid` from list_debuggable_apps (number or numeric string).", true)
    val serial = args.str("serial")
    val className = args.str("className")
    val field = args.str("field")
    val instance = args.str("instance") ?: (args["instance"] as? JsonPrimitive)?.content
    val depth = args.intArg("frameDepth") ?: 8
    if ((className == null) != (field == null)) {
        return CallResult("Pass both `className` and `field`, or neither.", true)
    }
    val report = env.sessions.withMainThread(pid, serial) { vm, main, _ ->
        readState(vm, main, className, field, depth.coerceIn(1, 50), instance)
    }.getOrElse { return CallResult(it.message ?: "read_state failed.", true) }

    val sb = StringBuilder("pid=$pid main-thread top frames:\n")
    if (report.topFrames.isEmpty()) sb.append("(no frames)\n")
    report.topFrames.forEach { sb.append("- ${it.method} @ ${it.location}\n") }
    sb.append("locals:\n")
    if (report.locals.values.isEmpty()) sb.append("(none${report.locals.note?.let { ": $it" } ?: ""})\n")
    report.locals.values.forEach { (k, v) -> sb.append("- $k = $v\n") }
    report.namedField?.let { sb.append("field: $it\n") }
    return CallResult(sb.toString().trimEnd())
}

private fun threadsTool(env: ToolEnv, args: JsonObject): CallResult {
    val pid = args.intArg("pid")
        ?: return CallResult("Provide `pid` from list_debuggable_apps (number or numeric string).", true)
    val serial = args.str("serial")
    val list = env.sessions.withMainThread(pid, serial) { vm, _, _ -> listThreads(vm) }
        .getOrElse { return CallResult(it.message ?: "threads failed.", true) }
    return CallResult(
        "Threads on pid=$pid (${list.size}):\n" +
            list.joinToString("\n") { "- ${it.name} [${it.status}] suspended=${it.suspended} :: ${it.topFrame}" }
    )
}

private fun setFieldTool(env: ToolEnv, args: JsonObject): CallResult {
    val pid = args.intArg("pid")
        ?: return CallResult("Provide `pid` from list_debuggable_apps (number or numeric string).", true)
    val className = args.str("className")
        ?: return CallResult("Provide `className` (fully qualified, as in the debug build).", true)
    val field = args.str("field")
        ?: return CallResult("Provide `field` (name as it exists in the debug build).", true)
    if (!args.containsKey("value")) {
        return CallResult("Provide `value` (string, number, boolean or null).", true)
    }
    val value = args.getValue("value")
    val instance = args.str("instance") ?: (args["instance"] as? JsonPrimitive)?.content
    val serial = args.str("serial")
    val out = env.sessions.withMainThread(pid, serial) { vm, _, _ ->
        setField(vm, className, field, value, instance)
    }.getOrElse { return CallResult(it.message ?: "set_field failed.", true) }
    return CallResult("pid=$pid $out")
}

private fun callMethodTool(env: ToolEnv, args: JsonObject): CallResult {
    val pid = args.intArg("pid")
        ?: return CallResult("Provide `pid` from list_debuggable_apps (number or numeric string).", true)
    val className = args.str("className")
        ?: return CallResult("Provide `className` (class of the receiver).", true)
    val method = args.str("method")
        ?: return CallResult("Provide `method` (a method that already exists).", true)
    val callArgs = argsList(args["arguments"])
    val instance = args.str("instance") ?: (args["instance"] as? JsonPrimitive)?.content
    val resumeAll = args.boolArg("resumeAll")
    val serial = args.str("serial")
    val matchField = args.str("instanceField")
    val matchValue = args.str("instanceValue")
    val out = env.sessions.withMainThread(pid, serial) { vm, main, pkg ->
        callMethod(
            vm, main, className, method, callArgs, instance, resumeAll,
            wake = { if (!pkg.isNullOrBlank()) env.adbFor(serial).wakeLooper(pkg) },
            matchField = matchField,
            matchValue = matchValue,
        )
    }.getOrElse { return CallResult(it.message ?: "call_method failed.", true) }
    return CallResult("pid=$pid $className.$method: $out")
}
