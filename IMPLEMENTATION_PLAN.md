# kotlin-runtime-mcp — Phase-by-Phase Implementation Plan + Learnings

Source spec: `kotlin-mcp-commandline.md` (222 lines).
Separate product from `flutter_ai_whisper` (Dart VM Service). This one speaks **adb/JDWP + JDI**.
Target: running native Kotlin Android app, **no app modification**.

```
AI agent ──stdio/MCP──> kotlin-runtime-mcp ──adb/JDWP──> debuggable app process
```

## 0. What we learned (feasibility + environment)

### 0.1 Machine (verified 2026-10-13, Windows)
- `java 21.0.12 Temurin + javac` — OK. JDI (`com.sun.jdi`) ships in-JDK, no extra install.
- `adb 1.0.41 / SDK 37.0.1` at `...\Sdk\platform-tools\adb.exe` — OK.
  SDK has `platforms/`, `build-tools/`, `emulator/`, `system-images/`.
- `adb devices` currently **empty** — proof later needs 1 emulator OR 1 physical device.
- `kotlin` / `gradle` NOT on PATH — bootstrap via Gradle wrapper + Maven Central
  (`org.jetbrains.kotlin.jvm` plugin + `io.modelcontextprotocol:mcp-sdk`). No system install needed.
- `kotlin-mcp/` today = spec doc only. Greenfield JVM project, must NOT share
  `VmBridge` or live inside `flutter_ai_whisper`.

### 0.2 Core technique (why it is buildable)
- A normal `assembleDebug` (`android:debuggable="true"`) already opens a JDWP endpoint.
  `adb jdwp` = allowlist. Release/Play builds never appear → refuse before attach.
- JDI can: list threads + frames, read stack/locals/fields, `setValue` on non-final
  fields, `invokeMethod` on a suspended thread, set modification watchpoints.
  This is exactly what Android Studio's debugger does — same ceiling, agent-driven.
- ART will NOT compile new Kotlin. Only existing fields/methods/routes/Activities.
  Source change → rebuild. Spec is honest about this; keep it.
- Emulator == physical device to JDWP. Physical needs: Developer options ON,
  USB debugging ON, RSA authorize on first plug, `adb devices` shows `device`
  (not `unauthorized`), debuggable build in foreground. No root for debuggable apps.
  Multi-device → always `adb -s <serial>`. Second debugger (Studio) attached →
  second attach fails → return `detach-studio-first`, don't hang.

### 0.3 MCP fit (why stdio MCP works)
- MCP = stdio/JSON-RPC + tool schemas. This server is request→short-text-reply,
  no streaming. One JVM process, `addTool(name, description, schema)` per tool.
- Failure convention (copied from `flutter_ai_whisper/llms.txt`): never throw out
  of handler; return `isError:true` + reason + fix hint; transport stays up.
- Binary (`screenshot` later) = base64 PNG in `TextContent`/`ImageContent`, capped.
- One JDWP attach at a time (mutex). Long JDI calls get timeouts — MCP clients
  time out idle tools.

### 0.4 Lessons ported from flutter_ai_whisper
- `connect_to_app` with no URI (DTD auto-discovery) → here: `list_debuggable_apps`
  with no args; `serial` optional, like `vmUri` fallback.
- `list_active_state_holders` filter/expressions UX → here: `filter` + explicit
  class/field probe list (keeps tokens low on big apps).
- `inject_state_variable` expression rules → here: overload resolution must print
  candidates; `val`-without-setter refusal must name the setter to call instead.
- `setState`-is-async + offstage-deferred rule → here: Compose recompose is async;
  poll `read_state`, never assert inline. Documented per tool.
- Never-throw + actionable error text → every miss returns what JDI *actually sees*
  (closest class/field/method, R8/mapping hint).

### 0.5 Known risks (owned, not blockers)
| # | Risk | Response (phase) |
|---|------|------------------|
| R1 | `ReferenceType.instances()` slow on big heaps | cap count, require `instance` (P6) |
| R2 | `invokeMethod` deadlock if method waits on suspended thread | invoke ON main, `INVOKE_SINGLE_THREADED`, timeout, resume-in-finally (P8) |
| R3 | Compose backing-field write leaves screen stale | direct to `state.value` setter; `set_state_value` helper (P7) |
| R4 | Multi-NavController / foreground-Activity discovery fragile per API | return list + require `instance`, pin per-API (P3/P6) |
| R5 | ART field-watchpoint support differs from HotSpot | spike first, keep best-effort (P4/P9) |
| R6 | Studio already attached | detect + clean error (P6) |
| R7 | Obfuscated debug build renames fields | return actual JDI names + `mapping.txt` hint (P6) |
| R8 | `adb` missing / no device | error naming `adb devices` before any JDWP (P1) |

---

## Phase 1 — Attach and read (START NOW)

**Status: code-complete 2026-10-13.** `./gradlew check` green (10/10 tests, fake-adb,
no hardware). Stdio smoke test green (handshake, tools/list, all failure paths
return `isError:true`). Live-device proof still pending (needs emulator/phone +
sample app).

**Goal:** nothing else is trustworthy until a live field reads back.
**Tools:** `list_debuggable_apps`, `read_state` (+ `threads` as freebie, read-only).
**Layers:** `adb/` (jdwp list, forward, device check), `session/` (attach/suspend/resume,
always-resume), `inspect/` (stack, locals, fields), `bin/` (stdio MCP entry).

Steps:
1. New Kotlin JVM project, separate repo/dir (this dir). Gradle wrapper, JDK 21,
   `org.jetbrains.kotlin.jvm`, `io.modelcontextprotocol:mcp-sdk`, JUnit5.
2. `adb/` impl: `adb [-s serial] jdwp` → pids; `adb shell ps -A` reverse-map pid→package;
   `adb forward tcp:<ephemeral-localhost> jdwp:<pid>`; `forward --remove` cleanup.
   Unit-testable with fake `adb` script (no device needed for build).
3. `session/` impl: JDI `SocketAttachingConnector` → `localhost:<port>`; find main/looper
   thread; `suspend()`; run block; `resume()` + `vm.dispose()` + remove-forward in `finally`.
   Mutex: one attach at a time.
4. `inspect/` impl: top frame of main thread → method/stack; `visibleVariables()` locals;
   named field read (static via `ClassType`, instance via single live instance or error
   listing count); `threads` = `vm.allThreads()` + top frame each.
5. `bin/` impl: stdio MCP server, register 3 tools with full JSON schemas + descriptions.
   Handlers never throw; `isError:true` on: no device, pid not in `adb jdwp`, attach
   timeout/contention, unknown class/field, multi-instance ambiguity.
6. Proof (needs device + sample app, manual): launch sample debug app with
   `ViewModel.count`; `list` shows pid; `read_state` number == screen; release variant
   pid absent → clean error. No file changed in sample app.

**Done when:** debug pid listed + one field read with no sample-app commit; release refuses;
`./gradlew check` clean (build + tests, no device required).

## Phase 2 — Write memory
**Status: code-complete 2026-10-13.** `mutate/` layer (`mirrorValue` for
String/number/boolean/null incl. lenient numeric/boolean strings, `setField` with
final/val refusal + read-back, `callMethod` with overload picker +
single-threaded invoke + app-exception rendering). Tools `set_field`,
`call_method` registered. `./gradlew check` green (55/55 tests across 10 suites:
adb parsing/discovery, session rules, tool registry + JSON-RPC dispatch, render,
overloads, set/call paths incl. multi-instance, static writes and app-exception
rendering — all via fake-adb and dynamic-proxy JDI doubles, no hardware).
green. Live proof pending (same device gate as Phase 1). Recompose detection
deferred to Phase 7 as planned; write path honestly reports read-back only.
**Tools:** `set_field`, `call_method`. **Layer:** `mutate/`.
- `set_field(pid,className,field,value[,instance,serial])`: resolve class; static→`setValue`
  on type; instance→single live instance or named `instance`; refuse `final`/`val`-no-setter/
  R8-mismatch with actionable text; read-back + return value.
- `call_method(pid,className,method[,arguments,instance,serial])`: resolve overload,
  `invokeMethod` on suspended main (`INVOKE_SINGLE_THREADED`), return toString or app
  exception as `isError:true`.
- Proof: `count` changes via tool, next `read_state` shows it, existing setter/`load()`
  runs. Release still refuses.
**Done when:** agent changes 1 field + calls 1 existing method, both visible to later read.

## Phase 3 — Open a screen
**Tool:** `navigate_to_screen(pid,screen[,arguments,instance,serial])`. **Layer:** `navigate/`.
- Route (`/` or non-class-like): scan `NavController`/`NavHostController` instances →
  1 instance: `navigate(screen)` (resolve Compose `navigate(String)` vs View
  `navigate(Int)` overloads); N instances: foreground Activity's controller or return
  list + require `instance`.
- Activity (`*Activity` class): foreground `Activity` (via `ActivityThread.mActivities`
  read, pinned per API) → `startActivity(component + extras)`.
- Missing route → graph error, no destination created. No navigator → `no-navigator`,
  back stack untouched.
**Done when:** 1 existing route + 1 existing Activity open; sample source unchanged.

## Phase 4 — Explain a freeze or surprise write
**Tools:** `threads` (promote), `watch_field(pid,className,field[,timeoutMs,serial])`.
- `threads`: full list + top frames + main/looper flag; ANR hint when main blocked.
- `watch_field`: `ModificationWatchpointRequest` → resume → wait with timeout →
  writer stack → delete request. One-shot.
**Done when:** blocked main shows top frame; next assignment names writer method.
Pre-spike: verify `canWatchFieldModification` on ART target; keep best-effort if gap.

## Phase 5 — Prove it on screen (next)
**Tools:** `current_screen`, `screenshot`, `app_logs`.
- `current_screen`: resumed Activity (`dumpsys activity activities`) + Nav backstack entry.
- `screenshot`: `adb exec-out screencap -p`, base64, capped.
- `app_logs`: `adb logcat -d --pid=<pid> -t N`, package-filtered.
- Recipe: every set/call/navigate followed by `read_state` + `current_screen`/`screenshot`.
**Done when:** field change visible on screenshot/log; navigation names destination.

## Phase 6 — Agent UX: instances, filter, types
- Multi-instance everywhere returns `[{id,toString-preview,activity,resumed?}]`.
- `filter` on list/read + explicit probe list.
- `serial` everywhere; `detach-studio-first` on contention.
- Types beyond String/number/bool/null: enums-by-name, Uri/Bundle/Intent-extras, lists;
  ambiguity prints candidates.
- Error overhaul: closest JDI names, val-vs-var, R8/`mapping.txt`, Compose-stale hint.
**Done when:** ambiguity test returns list; filtered read = 1 call; enum/Bundle round-trip.

## Phase 7 — Compose-aware writes + full navigation
- `set_state_value` (MutableState setter on main; poll for recompose).
- `collect_flow_once` (StateFlow/LiveData current + next emission, timeout).
- `back_stack`, `pop_back`/`pop_to(route)`, deep-link Uri, startActivity extras+flags.
**Done when:** Compose write recomposes in poll window; backstack round-trips.

## Phase 8 — Safety, perf, ship
- `--read-only` flag; audit log (pid/class/field/old→new); localhost-only forward.
- Optional persistent-attach; ReferenceType cache; per-call timeouts; mutex.
- GraalVM native image; `mcpServers` JSON; CHANGELOG; emulator CI (debug vs release).
**Done when:** `./gradlew check` clean, emulator CI green, release refuses, audit complete.

## Phase 9 — Later, only if a real session needs it
- Timed/continuous watch; ANR lock-owner report; `mapping.txt` resolver.
- OUT OF SCOPE for all above: host-side "compile new Kotlin into ART", hot-swap
  method bodies. Source change → rebuild. (Different product.)

## Session recipe (how an agent drives it)
1. Dev launches debug build, leaves app foreground.
2. `list_debuggable_apps` → keep pid (+ serial if multi-device).
3. `read_state` (ViewModel field of interest).
4. `navigate_to_screen` if not showing.
5. `set_field` or `call_method`.
6. `read_state` again (+ `current_screen`/`screenshot` once Phase 5 lands).
   Returned value is the proof; screen is proof only via setter/observed-State write.

## Acceptance (all phases)
- No file under target app created/edited.
- `adb jdwp` is the only attach allowlist.
- Failing tool still resumes thread + removes forward + keeps transport up.
- `set_field` returns post-write read-back.
- `navigate_to_screen` opens only pre-existing route/Activity.
- `flutter_ai_whisper` Dart tooling unchanged; Kotlin server is its own project.
