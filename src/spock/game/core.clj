(ns spock.game.core
  "Game loop abstraction. Mirrors drakon::Game.
   GLFW must be called from the main thread — this is single-threaded."
  (:require [spock.renderer.core :as renderer]
            [spock.renderer.vulkan :as vk])
  (:import [org.lwjgl.glfw GLFW Callbacks]
           [org.lwjgl.system MemoryUtil]))

;; ---------------------------------------------------------------------------
;; GameLifecycle protocol
;; ---------------------------------------------------------------------------
(defprotocol GameLifecycle
  (on-init! [this]
    "Called once before the game loop starts.")
  (on-tick! [this delta]
    "Called every frame. delta is elapsed seconds (double).")
  (on-done! [this]
    "Called once after the game loop exits."))

;; ---------------------------------------------------------------------------
;; Game record
;; ---------------------------------------------------------------------------
(defrecord Game [title width height renderer state])
;; state atom: {:running? false :renderables [] :window 0}

(defn make-game
  ([title]       (make-game title 1280 720))
  ([title w h]
   (->Game title w h
           (vk/make-vulkan-renderer)
           (atom {:running? false :renderables [] :window 0}))))

(defn add-renderable! [game renderable]
  (swap! (:state game) update :renderables conj renderable))

;; ---------------------------------------------------------------------------
;; GLFW window
;; ---------------------------------------------------------------------------
(defn- init-glfw! [game]
  (when-not (GLFW/glfwInit)
    (throw (RuntimeException. "Failed to initialize GLFW")))
  (GLFW/glfwDefaultWindowHints)
  (GLFW/glfwWindowHint GLFW/GLFW_CLIENT_API GLFW/GLFW_NO_API)
  (GLFW/glfwWindowHint GLFW/GLFW_RESIZABLE GLFW/GLFW_FALSE)
  (let [window (GLFW/glfwCreateWindow
                 (:width game)
                 (:height game)
                 (:title game)
                 MemoryUtil/NULL
                 MemoryUtil/NULL)]
    (when (= window MemoryUtil/NULL)
      (throw (RuntimeException. "Failed to create GLFW window")))
    (GLFW/glfwSetKeyCallback
      window
      (reify org.lwjgl.glfw.GLFWKeyCallbackI
        (invoke [_ win key _scancode action _mods]
          (when (and (= key GLFW/GLFW_KEY_ESCAPE)
                     (= action GLFW/GLFW_RELEASE))
            (GLFW/glfwSetWindowShouldClose win true)))))
    (swap! (:state game) assoc :window window)
    window))

(defn- cleanup-glfw! [game]
  (let [window (:window @(:state game))]
    (when (not= window 0)
      (Callbacks/glfwFreeCallbacks window)
      (GLFW/glfwDestroyWindow window)))
  (GLFW/glfwTerminate))

;; ---------------------------------------------------------------------------
;; Game loop
;; ---------------------------------------------------------------------------
(defn start!
  "Start the game loop. lifecycle must satisfy GameLifecycle.
   Blocks until the window is closed. Call from the main thread."
  [game lifecycle]
  (let [window (init-glfw! game)
        r      (:renderer game)]
    (try
      ;; Init renderer
      (when-not (renderer/init! r window (:width game) (:height game))
        (throw (RuntimeException. "Renderer init failed")))
      ;; User init
      (on-init! lifecycle)
      ;; Game loop
      (swap! (:state game) assoc :running? true)
      (loop [last-time (System/nanoTime)]
        (when (and (:running? @(:state game))
                   (not (GLFW/glfwWindowShouldClose window)))
          (GLFW/glfwPollEvents)
          (let [now   (System/nanoTime)
                delta (/ (- now last-time) 1e9)]
            (on-tick! lifecycle delta)
            (renderer/render! r (:renderables @(:state game)))
            (recur now))))
      (on-done! lifecycle)
      (finally
        (renderer/cleanup! r)
        (cleanup-glfw! game)))))
