# kotlin-runtime-mcp

MCP server for a **running native Kotlin Android app**. The agent talks to this process on stdin. This process attaches to the app with `adb` and JDWP, reads or changes memory, and detaches. The app project is not modified: no Gradle plugin, no library, no manifest entry, and no debug hook in source.

```
AI agent ── stdio / MCP ──> kotlin-runtime-mcp ── adb / JDWP ──> debuggable app
```

A normal debug build is already attachable. `assembleDebug` sets `android:debuggable="true"`, which opens a JDWP port. Android Studio uses that port. This server uses the same port, for one tool call at a time.

This is a different product from `flutter_ai_whisper`. That server speaks the Dart VM Service. A Flutter app on Android still belongs to that server. This one speaks the Android debugger protocol, so it sees Kotlin and Java objects in ART.

| Document | Who it is for |
| --- | --- |
| This file | Developers building, running, and changing the server |
| [`AGENTS.md`](AGENTS.md) | An agent deciding which tool to call |
| [`kotlin-mcp-commandline.md`](kotlin-mcp-commandline.md) | The product spec |
| [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) | Phases that are not built yet |

Version `0.1.0`. JDK 21. Kotlin 2.1.

## What you can do today

Five tools are registered:

| Tool | Reads | Writes | Runs app code |
| --- | --- | --- | --- |
| `list_debuggable_apps` | pid and package | no | no |
| `read_state` | stack, locals, one field | no | no |
| `threads` | thread list and top frames | no | no |
| `set_field` | the field after the write | one existing field | no |
| `call_method` | the method's return value | whatever that method writes | yes, one existing method |

`set_field` changes a field that already exists. `call_method` runs a method that is already loaded. Neither compiles new Kotlin into the process. ART will not accept a new snippet over JDWP, and this server does not try.

## What this server does not do

- It does not edit, patch, or instrument the app project.
- It does not attach to a release, profile, or Play build. Those builds are absent from discovery, and every tool returns an error if you pass their pid anyway.
- It does not bypass `android:debuggable`.
- It does not keep the process suspended after the tool returns. Resume, dispose, and `adb forward --remove` run in a `finally` block.
- It does not take an `Intent`, `Bundle`, `Uri`, or enum as a method argument. Phase 2 arguments are strings, numbers, booleans, and null.
- It does not ship `navigate_to_screen`, `watch_field`, `screenshot`, or `app_logs` yet. See the plan for those.

## Repository layout

```
kotlin-mcp/
  src/main/kotlin/mcp/
    Main.kt              stdio JSON-RPC entry
    tools/Tools.kt       tool names, schemas, argument checks
    adb/Adb.kt           devices, jdwp, ps/dumpsys fallback, forward
    session/Session.kt   attach, suspend, resume, one-at-a-time lock
    session/EventSuspend.kt
                         breakpoint or step so invokeMethod is legal
    inspect/Inspect.kt   stack, locals, named field, threads
    mutate/Mutate.kt     set_field, call_method, instance lookup
  src/test/kotlin/mcp/   JUnit 5. No device. Fake adb and fake JDI.
  example-app/           CounterProof, the manual proof app
  scripts/mcp-call.ps1   one tool call from a terminal
  build/install/         produced by installDist; not committed
```

The server is a normal JVM program. It is not an Android library and it is not installed on the device. JDI (`com.sun.jdi`) comes with JDK 21. The only Android-side requirement is a debuggable process and `adb`.

## Requirements

- JDK 21. `java -version` must report 21 or newer. JDI is not a Maven dependency.
- Android SDK platform-tools. `adb` must be on `PATH` for the process that launches the server. If you start the server from Cursor, Cursor's environment needs `adb`, not only your terminal.
- One emulator or a physical device in `adb devices` with state `device`. `unauthorized` means the phone is waiting for the RSA prompt.
- A debug build left in the foreground. `assembleDebug` / `installDebug`. Release builds are refused by design.

## Build

From the repository root:

```bat
.\gradlew.bat check
.\gradlew.bat installDist
```

`check` compiles and runs the unit tests. It does not need a device. `installDist` writes:

```
build\install\kotlin-runtime-mcp\bin\kotlin-runtime-mcp.bat
build\install\kotlin-runtime-mcp\lib\*.jar
```

The application main class is `mcp.MainKt`. Group `dev.kotmcp`, version `0.1.0`.

After you change server code, run `installDist` again and **restart the MCP client**. A server that is already running has the old jar loaded. Cursor does not pick up a replaced jar until you disable and enable `kotlin-runtime-mcp`, or start a new client.

## Run

The protocol is newline-delimited JSON-RPC 2.0 on stdin and stdout. Stdout is only protocol. Diagnostics go to stderr. A log line on stdout will break the client.

```bat
.\build\install\kotlin-runtime-mcp\bin\kotlin-runtime-mcp.bat
```

The process prints `kotlin-runtime-mcp 0.1.0 listening on stdio` on stderr and then waits for one JSON object per line.

### Cursor

Cursor does not read `.mcp.json` in the repo. Add the server in Cursor Settings → Tools & MCP, or write `.cursor/mcp.json` in this project:

```json
{
  "mcpServers": {
    "kotlin-runtime-mcp": {
      "command": "C:\\path\\to\\kotlin-mcp\\build\\install\\kotlin-runtime-mcp\\bin\\kotlin-runtime-mcp.bat",
      "args": []
    }
  }
}
```

Use the absolute path to the `installDist` bat. Enable the server. Start a **new** chat. The chat that was already open does not see a server added after it started.

A user-level file is `%USERPROFILE%\.cursor\mcp.json`. That file must be valid JSON. An extra brace makes Cursor keep the previous server list and ignore the new entry.

If the server shows as connected but every call hangs, the Windows `.bat` may not be passing stdin through to `java`. The reliable check is to run the jar directly:

```bat
java -classpath "build\install\kotlin-runtime-mcp\lib\*" mcp.MainKt
```

### One call from PowerShell

`scripts/mcp-call.ps1` starts the server, sends `initialize` and one `tools/call`, and exits. It is a thin client, not a second implementation.

```bat
.\scripts\mcp-call.ps1 -ProcessId 12345 -ClassName com.example.counterproof.CounterViewModel -Field count -Value 20
.\scripts\mcp-call.ps1 -ProcessId 12345 -ClassName com.example.counterproof.HomeFragment -Method onResume
.\scripts\mcp-call.ps1 -ProcessId 12345 -ClassName com.example.counterproof.CounterViewModel -Method load
```

`-ProcessId` is optional when exactly one debuggable app is running. The parameter is not named `-Pid` because PowerShell already uses `$PID` for its own process.

## Protocol

Implemented methods:

| Method | Reply |
| --- | --- |
| `initialize` | `protocolVersion` `2024-11-05`, `capabilities.tools`, `serverInfo` |
| `ping` | empty result |
| `tools/list` | the five tools and their JSON schemas |
| `tools/call` | `{ content: [{ type: "text", text }], isError? }` |
| notifications (no `id`) | no reply |

`tools/call` failures are still a successful JSON-RPC result. The tool text explains the failure and `isError` is `true`. The app is left running. A JSON-RPC error is reserved for a broken request (bad JSON, unknown method, missing tool name).

`pid` is accepted as a JSON number or a numeric string. Agents often send `"24113"`.

## What every mutating call does

`read_state`, `threads`, `set_field`, and `call_method` share `SessionManager.withMainThread`:

1. Take the process lock. A second call within 30 seconds gets "Another tool call is attached". Do not fire two tools in parallel.
2. Discover debuggable pids **again**. The pid must be on that list right now.
3. `adb forward tcp:<free-port> jdwp:<pid>`. The forward is localhost only.
4. JDI `SocketAttach` to `127.0.0.1` on that port, with a 10 second attach timeout.
5. `VirtualMachine.suspend()` so frames and locals exist. A running thread throws `IncompatibleThreadStateException` from `frames()`.
6. Find the thread named `main`.
7. Run the tool.
8. In `finally`: `resume()`, `dispose()`, `adb forward --remove`. This runs when the tool succeeds and when it fails.

The suspend is for a stable stack and a stable heap read. It is **not** a legal suspend for `invokeMethod`. The Java Debug Interface only allows a method call after an event that occurred in that thread (a breakpoint or a step). `VirtualMachine.suspend()` and `ThreadReference.suspend()` do not qualify. `call_method` converts the pause into an event suspend before it invokes. See below.

Object ids are per attach. `findViewById` in one call can return `#67`, and the next call's heap scan can list `#38`, `#39`, `#40` for the same buttons. Pass `instanceField` / `instanceValue` so selection and the call happen in one attach.

## Discovery

`list_debuggable_apps` and the allowlist inside every other tool use `AdbClient.listDebuggableApps`.

1. Try `adb jdwp` on a short fuse (2 seconds in the real server). A healthy device answers in well under that.
2. If it prints pids, those pids are the allowlist. Each pid is mapped to a package with `adb shell ps -A`. A pid with no package row is kept with `packageName = null`.
3. If `adb jdwp` times out (runner exit code `-2`), the server remembers that device for the life of the process and **skips `adb jdwp` on later listings**. Discovery then uses `ps -A` plus `dumpsys package`. A package is kept only when the dumpsys text contains `DEBUGGABLE`. A release process is listed by `ps` and then dropped.
4. A fast `adb jdwp` failure (non-zero exit, not a timeout) still falls back, and the next call tries `adb jdwp` again.

The fallback has the same safety bar as `adb jdwp`: a non-debuggable process is not a legal target. The skip only removes a listing that has already failed to answer. It does not trust every running package. Package names are taken from `ps` only when they look like `com.example.app` (a dotted identifier). Kernel threads and system daemons are dropped before the `dumpsys` check.

An empty device list is reported before any JDWP attempt: "No devices attached…".

Some OEM builds, including the phone used while developing this, never answer `adb jdwp` even though `adb forward jdwp:<pid>` works. That is why the fallback exists. The tool text says `via fallback` when that path was used.

## Tools in detail

### `list_debuggable_apps`

```json
{ "serial": "emulator-5554" }
```

`serial` is optional. When several devices are attached, pass the serial from `adb devices`. The reply is text, one process per line. The parenthetical is `adb.lastDiscovery`:

```
Debuggable processes (1, via adb jdwp):
pid=29363 package=com.example.counterproof
```

After a listing timeout the same line reads `via fallback (adb jdwp skipped; the listing timed out earlier on this device)`. A pid with no `ps` row is `package=<unknown>`.

### `read_state`

```json
{
  "pid": 29363,
  "className": "com.example.counterproof.CounterViewModel",
  "field": "count",
  "instance": "22",
  "frameDepth": 8,
  "serial": "emulator-5554"
}
```

Required: `pid`. `className` and `field` are both passed or both omitted. `frameDepth` defaults to 8 and is clamped to 1..50.

The reply has three parts:

- Main-thread top frames, `class.method(signature) @ file:line`.
- Locals of frame 0. A debug build without local-variable tables returns an empty list and a note. R8-minified release builds are not a target.
- One field line, `count = 20`, or an `error:` line when the class, field, or instance cannot be resolved.

Field names are the names in the debug build. Kotlin `var count` is the field `count`. A private backing field that Compose does not read will not update the screen; this sample is a plain `var` plus a `TextView`.

### `threads`

```json
{ "pid": 29363 }
```

Every thread JDI can see, with status, suspend flag, and the top frame. Use this when the UI is frozen and you need the main thread's top frame. It does not resume a stuck app by itself.

### `set_field`

```json
{
  "pid": 29363,
  "className": "com.example.counterproof.CounterViewModel",
  "field": "count",
  "value": 42
}
```

Required: `pid`, `className`, `field`, `value`. `value` is a string, number, boolean, or null. Numeric strings are accepted when the field is numeric (`"42"` into an `int`).

Resolution:

1. `classesByName`. An empty result names the class and mentions R8 renaming.
2. `fieldByName`. An unknown field lists the names JDI can see.
3. Static fields are set on the class. Instance fields need one live instance, or `instance` set to an id from the error text.
4. `final` fields are refused. Kotlin `val` is stored as a final backing field. The error tells you to use `call_method` on a setter instead.
5. The field is read back. The reply looks like `pid=29363 count = 42 (read back after write)`.

The write does not call a recompose or `invalidate`. If the UI copies the field only in a click listener or in `onResume`, the screen stays on the old text until that code runs. On CounterProof, follow a write with `HomeFragment.onResume`.

### `call_method`

```json
{
  "pid": 29363,
  "className": "com.example.counterproof.CounterViewModel",
  "method": "assignCount",
  "arguments": [20]
}
```

Required: `pid`, `className`, `method`. `arguments` defaults to no-arg.

Overloads are chosen by argument count. Zero matches or two matches with the same count is an error that lists signatures. Varargs are recognized only far enough to accept the right count; full array packing is later work.

If a live instance exists, an instance method is preferred. Otherwise a static method is used. Constructors are not callable.

Return values are rendered with the same rules as fields (`returned "count=20"`, `returned 3`, `ok (void)`). An exception thrown by the app becomes `isError: true` with `App threw java.lang.RuntimeException: …`. The debugger stays up. That is an app failure, not a transport failure.

#### Event suspend

On ART, the first `invokeMethod` under `VirtualMachine.suspend()` throws `IncompatibleThreadStateException` even when `suspended=true` and `suspendCount=1`. `resumeAll: true` does not fix that. The spec requires an event in that thread.

`call_method` then:

1. Sets a one-shot breakpoint on the first Java method the main looper will re-enter: `android.os.Looper.loopOnce`, else `Looper.loop`, else `MessageQueue.next`. If none of those have a location, it uses a min-size step out.
2. Resumes the VM so the thread can run.
3. Wakes an idle looper with `adb shell am send-trim-memory <package> RUNNING_MODERATE`. The main thread of a foreground app often sits inside `MessageQueue.nativePollOnce`, and a breakpoint cannot fire until that native call returns.
4. Waits up to 8 seconds for the breakpoint or step.
5. Disables the request and invokes the method with other threads resumed, so a method that needs a lock they hold does not deadlock.
6. Leaves a single suspend for the session `finally`, which resumes the app.

A call that took this path ends with `(event-suspended)`. If the looper never arrives, the error says the main thread did not stop on a debugger event. Leave the app in the foreground and retry.

`resumeAll` still selects the options of the **first** attempt. The retry after an event always resumes other threads during the invoke.

#### Choosing an instance in the same call

```json
{
  "pid": 29363,
  "className": "androidx.appcompat.widget.AppCompatButton",
  "method": "performClick",
  "arguments": [],
  "instanceField": "mText",
  "instanceValue": "Open settings"
}
```

`instanceField` / `instanceValue` scan up to 200 instances of every class with that name (more than one classloader can define it) and keep the object whose field text equals the value. `String` fields are compared by their Java string, not by the quoted debug rendering. Zero matches and several matches are errors that list what was seen.

`instance` is an object id. It is only meaningful for the attach that produced it. Prefer `instanceField` when you can name a field.

`performClick` returning `true` means a click listener ran. On CounterProof that listener is what starts `SettingsActivity` or navigates to details. There is no `navigate_to_screen` tool.

## Sample app

`example-app` is CounterProof, `applicationId` `com.example.counterproof`. It is a small AppCompat app used to prove the server. Do not add a test-only method, a broadcast, or a debug flag to it. If a tool cannot see a behavior, the tool is missing that ability.

```bat
cd example-app
.\gradlew.bat :app:installDebug
adb shell am start -n com.example.counterproof/.MainActivity
```

| Type | Role |
| --- | --- |
| `MainActivity` | Hosts the navigation graph. |
| `HomeFragment` | Shows `count`. `onResume` copies `vm.count` into `R.id.count_text`. |
| `CounterViewModel` | `var count`, `increment()`, `assignCount(Int)`, `load(): String`. |
| `DetailsFragment` | Nav destination. Deep link `app://counter/details/{itemId}`. |
| `SettingsActivity` | Separate activity. Not a nav route. Opened by the **Open settings** button. `finish()` closes it. |

Home buttons, as labeled in `fragment_home.xml`:

| `mText` | What the click listener does |
| --- | --- |
| `Increment` | `vm.increment()` and refreshes the label |
| `Open details` | `NavController.navigate` to details with `itemId` `42` |
| `Open settings` | `startActivity(SettingsActivity)` |

`count` starts at 0 after a fresh process. A field write to `42` leaves the label alone until `onResume`, a click on Increment, or a real leave-and-return of the activity.

### Manual proof

With the debug app in the foreground:

1. `list_debuggable_apps` includes `com.example.counterproof`. Note the pid.
2. `read_state` with `className` `com.example.counterproof.CounterViewModel` and `field` `count` returns the number on screen, after a paint.
3. `set_field` `count` to `42`. The read-back is 42.
4. `call_method` `com.example.counterproof.HomeFragment.onResume`. The label shows 42.
5. `call_method` `CounterViewModel.load` returns `count=42` and the text ends with `(event-suspended)`.
6. `call_method` `performClick` on `androidx.appcompat.widget.AppCompatButton` with `instanceField` `mText` and `instanceValue` `Open settings`. Settings is in front.
7. `call_method` `com.example.counterproof.SettingsActivity.finish`. Home is in front again.
8. Install the release variant. The pid is absent. Tools return `isError: true`.
9. `git status` in the sample app shows no source change from the session.

`example-app/local.properties` is a machine SDK path and is gitignored.

## Timing

A cold `call_method` on a phone whose `adb jdwp` listing never returns:

| Step | Typical cost |
| --- | --- |
| First `adb jdwp` try | up to 2 seconds, then it is skipped for this server process |
| Later listings | `ps` and `dumpsys` only |
| `adb forward` and JDI attach | about a second |
| Event suspend and `invokeMethod` | a few seconds, waiting for `Looper.loopOnce` |

`set_field` skips the event suspend. Two tools in a row pay discovery and attach twice, because the server does not keep a JDWP session open. Keeping a session would make object ids stable and calls faster, and it would also hold the only debugger slot for the whole chat. That tradeoff is not taken in 0.1.0.

## Tests

```bat
.\gradlew.bat check
.\gradlew.bat test --tests mcp.ToolDefsTest
```

Tests do not need a device or an emulator. `FakeAdb` maps an `adb` argument string to a scripted `AdbResult`. `FakeJdi` is a dynamic proxy over the JDI interfaces. Unimplemented JDI methods throw, so a test that starts calling a new method fails loudly.

Covered without hardware:

- `adb jdwp` parsing, package mapping, empty jdwp, serial prefix
- timeout fallback to `ps` + `dumpsys`, and skipping a later `adb jdwp` after exit code `-2`
- release packages filtered out of the fallback
- tool registry JSON schemas and JSON-RPC dispatch
- `set_field` / `call_method` argument checks, overload choice, final-field refusal, app exceptions
- looper breakpoint preference: `loopOnce`, then `loop`, then `MessageQueue.next`

A green `check` does not prove a device session. The manual proof above is the device test. The event-suspend path and the button-text match are exercised on a phone; the unit tests cover the pure decisions those paths make.

## Development notes

### Adding a tool

1. Add a `ToolDef` in `tools/Tools.kt` with a JSON schema. `tools/list` serves that schema. Keep required fields accurate; `ToolDefsTest` parses every schema.
2. Handle the name in `handleTool`. Read args with `str`, `intArg`, and `boolArg`. Return `CallResult(text, isError)`.
3. Do device work inside `env.sessions.withMainThread`. Do not attach JDI yourself. The session owns suspend and resume.
4. Throw `IllegalArgumentException` or `IllegalStateException` with a sentence that tells the agent what to do next. `withMainThread` turns that into `Action failed: …` and still resumes.
5. Add a unit test with `FakeAdb` / `FakeJdi`. Run `check`.
6. If the behavior needs a real app, extend the manual proof. Do not add a hook to `example-app` just so the test can pass.

### Errors the agent should see

Write the failure as the tool text. Include the pid, class, or field, and the next action (`list_debuggable_apps`, pass `instanceField`, leave the app in the foreground, detach Android Studio).

Do not catch `IncompatibleThreadStateException` and report success. Do not leave a breakpoint enabled. `suspendThreadByEvent` disables and deletes its request before returning.

### One debugger

ART accepts one JDWP client. If Android Studio is attached, `SocketAttach` fails and the error says to detach it. The in-process lock stops this server from attaching twice. It cannot evict another debugger.

### Logging

`println` on stdout corrupts the MCP stream. Use `System.err` or do not log.

### Git

`build/`, `.gradle/`, `.kotlin/`, and `local.properties` are ignored. Commit `src/`, the example app sources, Gradle wrappers, `AGENTS.md`, and this file. The `installDist` tree is a build product.

## Troubleshooting

| Symptom | What is going on |
| --- | --- |
| `No devices attached` | `adb devices` is empty or unauthorized. Replug, accept the RSA prompt, or start the emulator. |
| `not debuggable` / pid missing | The process is release, or it died. List again and use the new pid. |
| `adb jdwp` timed out, then fallback works | Normal on some phones. Later calls skip the listing. |
| `JDWP attach … failed` and Android Studio is open | Detach the IDE debugger and retry. |
| `No main/looper thread` | The process has no thread named `main`, or the app is not the one you think. |
| `IncompatibleThreadStateException` after `(event-suspended)` is missing | The event wait did not run, or the server jar is old. `installDist` and restart the MCP server. |
| `did not stop on a debugger event within 8s` | The main thread stayed in `nativePollOnce`. Bring the app to the foreground. |
| Screen ignores `set_field` | The write landed. Call the paint method (`onResume` on the sample) or click the control that reads the field. |
| `No live instance id` | The id came from a previous attach. Use `instanceField` and `instanceValue`. |
| Tool list in Cursor is stale | Restart the server. The running JVM will not reload the jar. |
| Cursor MCP entry does nothing | The user `mcp.json` is invalid JSON, or the chat was opened before the server was added. |

## Later work

Not in this tree yet, and not implied by a successful `set_field`:

- `navigate_to_screen` for an existing route or Activity, including building the `Intent` the current argument model cannot express
- `watch_field` via a modification watchpoint
- `current_screen`, `screenshot`, `app_logs` (`screenshot` and logs are adb, not JDWP)
- Compose `MutableState` and `StateFlow` helpers
- a persistent attach, which would make object ids stable across calls

The boundary stays the same for all of those: the route, activity, field, and method must already be in the running debug build.
