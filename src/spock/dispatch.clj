(ns spock.dispatch
  "Main-thread dispatch queue for safe REPL → game interaction.

   On macOS, GLFW and Vulkan surface operations must run on the main thread.
   The JVM's nREPL server (used by Calva and other editors) runs on worker
   threads.  Calling GLFW or touching Vulkan resources directly from a REPL
   eval will crash or corrupt state.

   This namespace provides a single LinkedBlockingQueue that the game loop
   drains every tick.  Any code — REPL evals, background threads, tests —
   can submit work to the main thread via `dispatch!`.

   Usage from the REPL:
     (require '[spock.dispatch :as dispatch])
     (dispatch/dispatch! #(swap! my-game-state assoc :paused true))

   The game loop calls `drain!` once per tick (see spock.game.core/start!).")

(defonce ^java.util.concurrent.LinkedBlockingQueue
  queue
  (java.util.concurrent.LinkedBlockingQueue.))

(defn dispatch!
  "Submit a zero-argument fn to be called on the main thread on the next tick.
   Safe to call from any thread, including nREPL worker threads.
   Returns immediately; the fn runs asynchronously on the next game tick."
  [f]
  (.put queue f))

(defn drain!
  "Execute all pending fns in the queue on the calling thread.
   Call this from the main game loop once per tick, before or after on-tick!.
   Any exception thrown by a dispatched fn is caught and printed; it does not
   crash the game loop."
  []
  (loop []
    (when-let [f (.poll queue)]
      (try (f)
           (catch Exception e
             (println "[spock.dispatch] Error in dispatched fn:" (.getMessage e))
             (.printStackTrace e)))
      (recur))))
