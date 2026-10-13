\# Kotlin Runtime MCP

An MCP server that lets an AI agent read, change, and navigate a \*\*running native Kotlin Android app\*\* over JDWP. The app repository is not modified. No Gradle plugin, no library, no manifest entry, and no debug hook in source.

This is a separate product from `flutter\_ai\_whisper`. That server speaks the Dart VM Service. This one speaks the Android debugger protocol. A Flutter app on Android is still served by `flutter\_ai\_whisper`.

\## Objective

Give a coding agent the same kind of live control over a native Kotlin process that `flutter\_ai\_whisper` has over a Flutter isolate:

\- See the in-memory value that the screen is using.

\- Change that value while the process keeps running.

\- Open a screen the app already contains.

\- Do all of that without editing the app.

The agent never attaches to the phone. It calls tools on a local MCP server. The server attaches to the debug process.

\## Solution

A normal debug build is already attachable. Android Studio and `assembleDebug` set `android:debuggable="true"`, which opens a JDWP port. The MCP server, running on the developer machine, uses `adb` to find that port and the Java Debug Interface to suspend one thread, read or write a field, call a method that is already loaded, or open an existing route or Activity. It then resumes the thread.

```

AI agent ──stdio/MCP──> kotlin-runtime-mcp ──adb/JDWP──> debuggable app process

```

Nothing is added to the app. The route, the Activity, the field, and the method must already be in the build that is running. A Play or release build does not appear in `adb jdwp`, and the server stops before any write or navigation.

ART will not compile a new Kotlin snippet into the running process. The server sets fields and calls methods that already exist. That is the same ceiling as Android Studio's debugger, reached from an agent instead of from the IDE.

\## What this does not do

\- It does not edit, patch, or instrument the app project.

\- It does not attach to a release, profile, or Play build.

\- It does not bypass `android:debuggable`.

\- It does not load new Kotlin, a new screen, or a new method.

\- It does not replace a rebuild when the source itself must change.

\- It does not ship inside `flutter\_ai\_whisper` or share `VmBridge`.

\## Who uses it, and when

| Who | When |

| --- | --- |

| The developer | Starts the debug app once, from Android Studio or `assembleDebug`. No source edit. |

| The MCP client (Cursor or another agent host) | Starts `kotlin-runtime-mcp` on stdio when the agent session begins. |

| The agent | Calls a tool after the app is in the foreground and `adb devices` shows the emulator or phone. |

| The server | Attaches only for the duration of that tool call, then resumes the thread. |

The developer does not add a dependency. The only machine requirements are a JDK (for JDI), `adb` on `PATH`, and one debuggable process.

\## Runtime sequence

Every mutating tool follows the same order.

1\. \*\*Discover.\*\* `adb jdwp` lists process ids that are debuggable. The server drops any pid that is not on that list.

2\. \*\*Forward.\*\* `adb forward tcp:<local> jdwp:<pid>` exposes that process on localhost.

3\. \*\*Attach.\*\* JDI connects to the forwarded port.

4\. \*\*Suspend.\*\* The main thread is suspended. Locals exist only in a suspended frame. Fields can be read on an object the server has a reference to.

5\. \*\*Act.\*\* One of: read fields and stack, set one field, call one existing method, or navigate.

6\. \*\*Resume.\*\* The thread is resumed even when the action fails.

7\. \*\*Report.\*\* The tool returns a short text result. `isError: true` means the app was left unchanged or the action was rejected.

The server never leaves the process suspended after the tool returns.

\## Design

\### Process

One JVM program. Kotlin, using JDI (`com.sun.jdi`). It is not an Android library and it is not installed on the device.

```

kotlin-runtime-mcp

&#x20; bin/          MCP stdio entry, tool registration

&#x20; adb/          jdwp list, forward, device check

&#x20; session/      attach, suspend, resume, always resume on failure

&#x20; inspect/      stack, locals, fields, threads

&#x20; mutate/       set one field, call one existing method

&#x20; navigate/     route on NavController, or start an Activity

```

The session layer owns the invariant: attach only to a pid from `adb jdwp`, and resume in a `finally` block.

\### Tools

| Tool | When the agent calls it | What it does |

| --- | --- | --- |

| `list\_debuggable\_apps` | First, and whenever the device set changes | Returns pid, package name if `adb` can resolve it, and whether the process is currently resumed |

| `read\_state` | Before a write, and after one, to check the result | Suspends, returns the main-thread stack, locals in the top frame, and named fields |

| `set\_field` | When a value already in memory should change | Writes one field the class already has, then reads it back |

| `call\_method` | When the app already has a method that performs the change | Invokes that method on a live object |

| `navigate\_to\_screen` | When the agent needs a particular existing screen | Opens a route or an Activity that is already in the app |

| `watch\_field` | When the agent needs to know who wrote a value | Reports the method that next assigns that field, then removes the watch |

| `threads` | When the UI is frozen | Lists threads and the top frame of each |

`set\_field` and `call\_method` are the memory writes. `navigate\_to\_screen` is navigation. The others let the agent see the result instead of guessing.

\### `set\_field`

Arguments:

| Argument | Required | Meaning |

| --- | --- | --- |

| `pid` | yes | Process from `list\_debuggable\_apps` |

| `className` | yes | Fully qualified class that declares the field |

| `field` | yes | Field name as it exists in the debug build |

| `value` | yes | New value. Strings, numbers, booleans, and `null` in the first version |

| `instance` | no | Which instance, when more than one is live. Default: the single instance, or an error if several exist |

How:

1\. Resolve the class in the attached VM.

2\. If the field is static, set it on the class.

3\. If it is an instance field, take the only live instance, or the instance the agent named.

4\. Assign through JDI. Access checks are the debugger's, so a private field in a debug build can be written.

5\. Read the field back and return that value.

The write is refused when the field is `final`, when the Kotlin property is a `val` with no setter and no mutable backing field, or when R8 has renamed the field. Compose state updates only when the assigned member is the state value the UI reads (`state.value` via `call\_method` on the main thread). Writing a private backing field leaves the screen stale. The tool returns the value it read back and says whether a recompose method was called.

\### `call\_method`

Arguments:

| Argument | Required | Meaning |

| --- | --- | --- |

| `pid` | yes | Debuggable process |

| `className` | yes | Class of the receiver |

| `method` | yes | Method that already exists |

| `arguments` | no | Values for the existing parameters |

| `instance` | no | Which instance. Same rule as `set\_field` |

How: resolve the method, invoke it on the suspended main thread, return the string form of the result or the exception the app threw. The server does not invent a method.

\### `navigate\_to\_screen`

Arguments:

| Argument | Required | Meaning |

| --- | --- | --- |

| `pid` | yes | Debuggable process |

| `screen` | yes | A route (`details/42`, `/checkout`) or an Activity class (`com.example.app.SettingsActivity`) |

| `arguments` | no | Route arguments the existing destination already accepts |

How, in order:

1\. If `screen` contains a `/` or does not look like a class name, treat it as a Navigation route.

2\. Find loaded classes `androidx.navigation.NavController` and `androidx.navigation.NavHostController`.

3\. Take live instances. One instance: call `navigate` with `screen`. Several: use the resumed Activity's controller, or return the instance list and ask the agent to pass `instance`.

4\. If `screen` is a class name ending in `Activity`, call `startActivity` on the current foreground `Activity` with that component.

5\. Resume, then return `navigated` and the screen id.

The destination must already be in the navigation graph or the manifest. A missing route returns the navigator's error and does not create a destination. No `NavController` and no matching Activity returns `no-navigator` and leaves the back stack as it was.

\### `read\_state`, `watch\_field`, `threads`

`read\_state` is the check after a write and the way the agent sees a `ViewModel` without logging. `watch\_field` sets a modification watch, resumes, and returns the stack of the next write, then clears the watch. `threads` is read-only.

These three are the second slice. The first slice is list, set, call, and navigate.

\### Agent registration

The client starts the server. The app is not involved.

