# java-debug

English | [简体中文](README.zh-CN.md)

A **Java remote breakpoint debugging Skill** for AI agents such as Claude Code. It attaches to a remote JVM with JDWP enabled. The AI plans and sets breakpoints, waits for hits, collects call stacks and local variables, evaluates expressions and single-steps. It then reports a root cause based only on **runtime data it actually collected**.

At its core is the in-house `.agents/skills/java-debug/scripts/jdi-client.jar`. It uses only the JDK's built-in `com.sun.jdi` and has zero external dependencies. The jar is not shipped; it is built locally from the bundled source the first time the skill runs. It runs as a resident HTTP server, and every operation is a single JSON command, so `curl` is all you need to drive it.

---

## Why not just use jdb

`jdb` is made for a human typing in a terminal, and it is awkward for an AI to drive. Its output is text that has to be parsed, timing has to be guessed with tmux + sleep, and by default it freezes the whole JVM. This Skill redesigns the interaction layer for AI-driven debugging:

| Aspect | jdb | java-debug |
|--------|-----|------------|
| Suspend scope | `SUSPEND_ALL`: a hit freezes the whole JVM | `SUSPEND_EVENT_THREAD`: only the hit thread freezes; other business threads keep serving |
| Interaction | Text output that has to be parsed; relies on tmux + sleep to race timing | Structured JSON requests and responses; no tmux, no sleep races |
| Hit information | `where` / `locals` must be typed one by one | The hit response already includes the stack and locals |
| Waiting | Polling | Event-driven blocking wait (`wait-breakpoint`) with a timeout |
| Workflow guardrails | None | Built-in workflow: the user must confirm the breakpoint plan, the session must end with disconnect, and conclusions must be based on observed values |

---

## Security

Remote debugging is inherently dangerous: JDWP has no authentication, so anyone who can connect can run arbitrary code. This Skill makes its safety boundaries hard constraints wherever possible, instead of relying on the operator's discipline.

**Command port hardening** (jdi-client's own port, 15005 by default)

- The bind address is hard-coded to `127.0.0.1`, and no switch can open it up. To use it from another machine, go through an `ssh -L` tunnel
- The `Host` header must be `127.0.0.1`, `localhost` or `[::1]`, and any request with an `Origin` header gets a 403. This blocks cross-site POSTs from browsers and DNS rebinding
- A POST must carry `Content-Type: application/json` (otherwise 415). The body is capped at 1 MB, and the request must arrive within 30s so slow connections cannot exhaust the worker threads
- JSON parsing is strict: nesting deeper than 64 levels, illegal escapes, unescaped control characters and trailing content after the JSON are all rejected

**Protecting the target JVM**

- Only the hit thread is frozen (`SUSPEND_EVENT_THREAD`), so the service as a whole keeps running
- The client keeps at most 64 hit scenes. Beyond that, new hits are released without collecting a scene and the response reports `dropped_events`. This stops "wait for one, don't resume, wait again" from slowly draining the thread pool
- `wait-breakpoint` and `step` both time out. A timeout response lists `frozen_threads`, the threads that were reported but never resumed, so none get forgotten
- At most 2 `step` / `eval` commands run at once, which always leaves a worker free for `resume` / `disconnect`. A stuck eval cannot take down the command port
- `eval` has a hard deny list: `System.exit`, `Runtime.exit` / `halt` / `exec`, `ProcessBuilder.start`, `Thread.stop` / `suspend`
- A breakpoint hit only reads fields and never calls `toString()`, so a hit by itself runs no business code
- After `disconnect`, every frozen thread in the target JVM resumes automatically

**Workflow hard rules** (written into the Skill; the AI must follow them)

- The breakpoint plan must be shown to the user and confirmed before connecting or setting breakpoints
- The session must end with `disconnect`, whether it finishes normally, fails midway or is interrupted
- Methods with side effects must not be evaluated (DB writes, sending MQ, changing state, clearing caches)
- Before attaching to production, the user must confirm that the JDWP port is behind an IP allowlist or an SSH tunnel, and must be told that breakpoints freeze business threads
- Sensitive data from auth, payment or privacy-related frames (tokens, ID numbers, bank card numbers, passwords) must not be pasted verbatim into the conversation or the report

> Limits: the deny list guards against slips. It is **not a sandbox**, and other methods with side effects can still be called. The 64 cap limits the scenes the client keeps, **not the number of threads the target suspends at once**, so a burst of concurrent hits can still suspend many threads instantly. In production, put breakpoints on cold, lock-free paths and resume quickly.

---

## Features

- **Breakpoints**: method-entry and line breakpoints. If the class isn't loaded yet, the breakpoint is deferred automatically. Every overload and every executable location on a line (including lambdas) gets a breakpoint
- **Multiple classloaders / hot deployment**: every loaded copy of a same-name class gets the breakpoint, copies loaded later are covered automatically, and `class_loader_id` shows which copy was hit. When a copy cannot get the breakpoint, `deferred_failures` gives the reason (wrong method name, no bytecode on that line, not compiled with `-g`, etc.)
- **Field-tree display**: objects are expanded by field to depth 2, and `toString()` is never called automatically. Call it explicitly with `eval "obj.toString()"` when needed
- **Concurrent hit isolation**: `step` returns only the requested thread's events. Other threads' hits stay queued in order for `wait-breakpoint`
- **State inspection**: local variables of any frame, full call stack (depth can be limited) and the thread list
- **Expression evaluation** (like IDEA's Evaluate Expression): local variables, field access, instance methods, chained calls, static methods and static fields, and literal arguments. When a same-name class has several copies, pick one with `class_loader_id`
- **Single-stepping**: `over` / `into` / `out`. The response includes the stack and locals at the new location. Running into another breakpoint mid-step behaves as in IDEA
- **Hot-deployment friendly**: on JDK 9+ targets, static calls go through a MethodHandle and do not pin the called class's classloader, so old versions can still unload
- **Clear error codes**: `ConnectFailed`, `Timeout`, `Superseded`, `VmDisconnected`, `Busy`, `AmbiguousClass`, `InvalidArgs` and more, each with a defined meaning and remedy
- **Reconnect**: after the target JVM restarts, one `connect` command reconnects
- **No direct connection** (`--manual`): generates a command list for the user to run on the target or a jump host; the output is then pasted back for analysis
- **Compatibility**: the client runs on JDK 8 (with `tools.jar` added) or JDK 9+, and can debug JVMs older than itself

---

## Quick start

1. Start the target JVM with JDWP and compile it with `-g` (the Maven / Gradle default):

   ```bash
   # JDK 9+ (under zsh, quote the whole argument)
   java "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005" -jar app.jar
   # JDK 8
   java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005 -jar app.jar
   ```

2. Copy (or symlink) `.agents/skills/java-debug/` to `~/.claude/skills/java-debug/` and invoke it from the agent:

   ```text
   /java-debug 10.0.1.5:5005 --repo /path/to/repo
   /java-debug 10.0.1.5:5005 --bp com.example.OrderService.query --bp com.example.OrderService:123
   ```

   Options: `--bp` sets a breakpoint up front (repeatable), `--repo` is the local source path, `--max-bp` caps breakpoints per round, `--wait-timeout` sets how many seconds to wait for a hit, and `--manual` only generates the command list.

3. The AI then works through these steps: read the code to find the entry point → show the breakpoint plan for your confirmation → connect and set breakpoints → ask you to trigger the request → collect state, step and evaluate → disconnect → report the execution path, variable snapshot, root cause and fix.

See [SKILL.md](.agents/skills/java-debug/SKILL.md) for the full workflow, command reference and error handling.

---

## Layout

```text
java-debug/
├── .agents/skills/java-debug/      # The Skill itself (install this directory)
│   ├── SKILL.md                    # Skill definition: workflow, command reference, safety rules, error handling
│   ├── scripts/
│   │   ├── ensure-jdi-client.sh    # Builds scripts/jdi-client.jar on first use (not shipped, git-ignored)
│   │   └── jdi-client.jar          # Local build output
│   └── jdi-client/                 # Client source (Maven project)
│       ├── build.sh
│       └── src/main/java/cn/ares/jvm/jdi/
│           ├── transport/          # HTTP / stdio transport, zero-dependency JSON
│           ├── command/            # Command routing and command handlers
│           ├── connection/         # Connection lifecycle, concurrency permits
│           ├── session/            # JDWP connection, thread and classloader lookup
│           ├── breakpoint/         # Breakpoints and deferred breakpoints
│           ├── execution/          # Event loop; freeze / wait / resume / step state machine
│           ├── inspect/            # Stack, locals, value formatting
│           └── eval/               # Expression evaluation, dangerous-method guard, static calls
```

## Building

```bash
bash .agents/skills/java-debug/scripts/ensure-jdi-client.sh
```

If `scripts/jdi-client.jar` is missing, the script builds it with Maven (`jdi-client/build.sh`) and falls back to the local JDK's `javac` + `jar` when Maven is absent or fails. It needs a JDK (not just a JRE); on JDK 8 it adds `tools.jar` automatically, while JDK 9+ ships `com.sun.jdi` built in. The output is Java 8 bytecode. The script only checks that the jar exists, so after changing the source, delete the jar and rerun it.
