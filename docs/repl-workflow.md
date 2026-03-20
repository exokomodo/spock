# REPL Workflow (Calva / nREPL)

Spock uses GLFW for windowing and Vulkan for rendering. Both have
**main-thread requirements** on macOS (AppKit enforces this). The JVM's nREPL
server runs on worker threads. This doc explains how the two coexist.

## macOS: Why -XstartOnFirstThread is required

On macOS, AppKit's `NSRunLoop` (used internally by GLFW) must run on the OS
main thread — the thread that called `main()`. If you start GLFW from any
other thread, you get a hang or an `NSInternalInconsistencyException`.

The JVM flag `-XstartOnFirstThread` tells the JVM to treat the first thread
as the AppKit main thread. Without it, GLFW cannot create windows on macOS.

The `:dev` profile in `project.clj` adds this flag automatically on macOS.
On Linux it's a no-op.

## Jack-in with Calva (VS Code)

The `.vscode/settings.json` in this repo configures Calva to always jack in
with `lein with-profile dev repl`. This means:

- `-XstartOnFirstThread` is applied on macOS automatically
- `examples/` is on the source path so `hello.core` is accessible from the REPL

To start a REPL session: **Calva → Start Project REPL and Connect (Jack-in)**.
Accept the defaults — it will use the `dev` profile.

## Dispatching work to the main thread

nREPL evals run on worker threads. Calling GLFW functions or touching Vulkan
resources from a worker thread will crash or corrupt state.

Use `spock.dispatch/dispatch!` to run a fn on the main thread on the next tick:

```clojure
(require '[spock.dispatch :as dispatch])

;; Safe: dispatches to the main thread
(dispatch/dispatch! #(swap! my-atom assoc :paused true))

;; Unsafe: would run on the nREPL worker thread
;; (GLFW/glfwSetWindowShouldClose window true)  ← don't do this directly
```

The game loop calls `spock.dispatch/drain!` once per tick (after
`glfwPollEvents`, before `on-tick!`). Any fns you submit will run on the
next frame.

## Pure Clojure state is always safe

Only GLFW/Vulkan calls require dispatch. Reading and writing plain Clojure
atoms from the REPL is safe from any thread.

```clojure
;; Fine from the REPL directly:
@(:state my-game)
(swap! my-atom update :score inc)
```
