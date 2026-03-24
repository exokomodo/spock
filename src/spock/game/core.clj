(ns spock.game.core
  "Game loop abstraction.

   Threading model:
   - Main thread  : GLFW event polling, on-tick!, window lifecycle
   - Render thread: all Vulkan work, frame loop

   Surface creation (glfwCreateWindowSurface) must happen on the main
   thread because it needs the GLFW window handle. Everything else
   Vulkan-related runs on the render thread.

   Sync:
   - ready-p   promise — render thread delivers true once Vulkan is up,
                         or an exception on failure; main thread blocks on it
   - stop?     volatile! — set true by main thread when window closes;
                           checked every frame by render thread
   - error-p   promise — render thread delivers any frame-loop exception here"
  (:require
   [spock.renderer.core :as renderer]
   [spock.renderer.vulkan :as vk]
   [spock.entity :as entity]
   [spock.input.core :as input]
   [spock.audio.core :as audio]
   [spock.settings :as settings])
  (:import [org.lwjgl.glfw GLFW Callbacks]
           [org.lwjgl.system MemoryUtil]))

;; ---------------------------------------------------------------------------
;; GameLifecycle protocol
;; ---------------------------------------------------------------------------
(defprotocol GameLifecycle
  (on-init! [this]
    "Called on the main thread once before the event loop starts.")
  (on-tick! [this delta]
    "Called on the main thread every iteration. delta is seconds (double).")
  (on-done! [this]
    "Called on the main thread after the event loop exits."))

;; ---------------------------------------------------------------------------
;; Game record
;; ---------------------------------------------------------------------------
(defrecord Game [title width height renderer state])
;; state atom: {:window 0 :entities [] :running? false}

(defn make-game
  ([title]     (make-game title 1280 720))
  ([title w h] (->Game title w h
                       (vk/make-vulkan-renderer title)
                       (atom {:window 0 :entities [] :running? false}))))

(defn add-entity!
  "Add an Entity to the game."
  [game ent]
  (swap! (:state game) update :entities conj ent))

(defn add-renderable!
  "Backward-compatible helper: wraps a bare Renderable in an anonymous entity."
  [game renderable]
  (add-entity! game (entity/from-renderable renderable)))

(defn get-entities
  "Return the current entity list."
  [game]
  (:entities @(:state game)))

;; ---------------------------------------------------------------------------
;; GLFW helpers (main thread only)
;; ---------------------------------------------------------------------------
(defn- init-window!
  "Create the GLFW window. Returns the window handle (long).
   Must be called from the main thread."
  [game]
  (when-not (GLFW/glfwInit)
    (throw (RuntimeException. "Failed to initialize GLFW")))
  (GLFW/glfwDefaultWindowHints)
  (GLFW/glfwWindowHint GLFW/GLFW_CLIENT_API GLFW/GLFW_NO_API)
  (GLFW/glfwWindowHint GLFW/GLFW_RESIZABLE  GLFW/GLFW_FALSE)
  (let [window (GLFW/glfwCreateWindow
                (int (:width game))
                (int (:height game))
                ^String (:title game)
                MemoryUtil/NULL
                MemoryUtil/NULL)]
    (when (= window MemoryUtil/NULL)
      (throw (RuntimeException. "Failed to create GLFW window")))
    ;; Register generic input callbacks (key + mouse button + cursor position).
    ;; input/register-callbacks! owns the GLFW key callback; escape-to-close is
    ;; checked each tick via input/key-released? in the event loop below.
    (input/register-callbacks! window)
    (swap! (:state game) assoc :window window)
    window))

(defn- destroy-window! [game]
  (let [window (:window @(:state game))]
    (when (not= window 0)
      (Callbacks/glfwFreeCallbacks window)
      (GLFW/glfwDestroyWindow window)))
  (GLFW/glfwTerminate))

;; ---------------------------------------------------------------------------
;; Render thread
;; ---------------------------------------------------------------------------
(defn- render-thread-fn
  "Entry point for the render thread."
  [game ready-p stop? error-p]
  (let [r (:renderer game)
        window (:window @(:state game))
        w (:width game)
        h (:height game)]
    (try
      (when-not (renderer/init! r window w h)
        (throw (RuntimeException. "Vulkan renderer init failed")))
      (deliver ready-p true)
      ;; Frame loop — extract renderables from entities each frame
      (loop []
        (when-not @stop?
          (renderer/render! r (entity/renderables (:entities @(:state game))))
          (recur)))
      ;; Clean up entity renderables while device is still alive,
      ;; then drain GPU and release all Vulkan resources.
      (entity/cleanup-all! (:entities @(:state game)) (renderer/get-device r))
      (renderer/cleanup! r)
      (catch Exception e
        (if (realized? ready-p)
          (deliver error-p e)
          (deliver ready-p e))))))

;; ---------------------------------------------------------------------------
;; start!
;; ---------------------------------------------------------------------------
(defn start!
  "Start the game. Blocks on the main thread until the window is closed."
  [game lifecycle]
  (let [window  (init-window! game)
        r       (:renderer game)
        stop?   (volatile! false)
        ready-p (promise)
        error-p (promise)
        render-t (Thread.
                  ^Runnable (fn [] (render-thread-fn game ready-p stop? error-p))
                  "spock-render")]
    (try
      (renderer/create-surface! r window)
      (.start render-t)
      (let [ready-val @ready-p]
        (when (instance? Exception ready-val)
          (throw ready-val)))
      (audio/init!)
      (audio/set-master-volume! (double (:master-volume @spock.settings/logging-config 1.0)))
      (on-init! lifecycle)
      (swap! (:state game) assoc :running? true)
      (loop [last-t (System/nanoTime)]
        (when (and (:running? @(:state game))
                   (not (GLFW/glfwWindowShouldClose window)))
          (when (realized? error-p)
            (throw @error-p))
          (GLFW/glfwPollEvents)
          ;; Engine-level Escape key closes the window (check before tick! clears :released)
          (when (input/key-released? :escape)
            (GLFW/glfwSetWindowShouldClose window true))
          (let [now   (System/nanoTime)
                delta (/ (double (- now last-t)) 1e9)]
            (on-tick! lifecycle delta)
            ;; Advance input state AFTER on-tick! so scripts see :pressed on the frame it fires
            (input/tick!)
            (audio/tick!)
            (recur now))))
      (on-done! lifecycle)
      (finally
        (vreset! stop? true)
        (.join render-t 5000)
        (audio/cleanup!)
        (destroy-window! game)))))
