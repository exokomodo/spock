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
  (:require [spock.renderer.core :as renderer]
            [spock.renderer.vulkan :as vk])
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
;; state atom: {:window 0 :renderables [] :running? false}

(defn make-game
  ([title]     (make-game title 1280 720))
  ([title w h] (->Game title w h
                       (vk/make-vulkan-renderer title)
                       (atom {:window 0 :renderables [] :running? false}))))

(defn add-renderable! [game renderable]
  (swap! (:state game) update :renderables conj renderable))

(defn start-recording!
  "Begin recording rendered frames to path (mp4).
   fps controls the playback frame rate. Call after the game is running."
  ([game path]     (start-recording! game path 30))
  ([game path fps] (renderer/start-recording! (:renderer game) path fps)))

(defn stop-recording!
  "Finalise the recording and flush to disk."
  [game]
  (renderer/stop-recording! (:renderer game)))

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
    (GLFW/glfwSetKeyCallback
      window
      (reify org.lwjgl.glfw.GLFWKeyCallbackI
        (invoke [_ win key _sc action _mods]
          (when (and (= key GLFW/GLFW_KEY_ESCAPE)
                     (= action GLFW/GLFW_RELEASE))
            (GLFW/glfwSetWindowShouldClose win true)))))
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
  "Entry point for the render thread.
   window   — GLFW window handle (long); surface already created by main thread
   renderer — Renderer instance (already has surface in state)
   ready-p  — promise to deliver true/exception once Vulkan init is done
   stop?    — volatile! read each frame; set true by main to request exit
   error-p  — promise to deliver any frame-loop exception"
  [game ready-p stop? error-p]
  (let [r        (:renderer game)
        window   (:window @(:state game))
        w        (:width game)
        h        (:height game)]
    (try
      ;; Vulkan init (device onwards — surface was created on main thread)
      (when-not (renderer/init! r window w h)
        (throw (RuntimeException. "Vulkan renderer init failed")))
      ;; Signal main thread: ready to go
      (deliver ready-p true)
      ;; Frame loop
      (loop []
        (when-not @stop?
          (renderer/render! r (:renderables @(:state game)))
          (recur)))
      ;; Drain GPU before cleanup
      (renderer/cleanup! r)
      (catch Exception e
        ;; Deliver to whichever promise hasn't been delivered yet
        (if (realized? ready-p)
          (deliver error-p e)
          (deliver ready-p e))))))

;; ---------------------------------------------------------------------------
;; start!
;; ---------------------------------------------------------------------------
(defn start!
  "Start the game. Blocks on the main thread until the window is closed.
   - GLFW window + surface: main thread
   - Vulkan device/swapchain/frame loop: render thread
   - Event polling + on-tick!: main thread"
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
      ;; Surface must be created on the main thread before handing off to render thread
      (renderer/create-surface! r window)
      ;; Launch render thread — it will do the rest of Vulkan init
      (.start render-t)
      ;; Wait for Vulkan init to complete (or fail)
      (let [ready-val @ready-p]
        (when (instance? Exception ready-val)
          (throw ready-val)))
      ;; User init (on main thread, Vulkan is ready)
      (on-init! lifecycle)
      (swap! (:state game) assoc :running? true)
      ;; Main event loop
      (loop [last-t (System/nanoTime)]
        (when (and (:running? @(:state game))
                   (not (GLFW/glfwWindowShouldClose window)))
          ;; Check for render thread errors
          (when (realized? error-p)
            (throw @error-p))
          (GLFW/glfwPollEvents)
          (let [now   (System/nanoTime)
                delta (/ (double (- now last-t)) 1e9)]
            (on-tick! lifecycle delta)
            (recur now))))
      (on-done! lifecycle)
      (finally
        ;; Signal render thread and wait for it
        (vreset! stop? true)
        (.join render-t 5000)
        (destroy-window! game)))))
