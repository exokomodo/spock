(ns spock.game.core
  "Game loop abstraction. Mirrors drakon::Game."
  (:require [spock.renderer.vulkan :as vk]))

(defprotocol GameLifecycle
  (on-init! [this]
    "Called once before the game loop starts. Load assets, add renderables.")
  (on-tick! [this delta]
    "Called every frame. delta is elapsed seconds since last frame (double).")
  (on-done! [this]
    "Called once after the game loop exits."))

(defrecord Game [title width height renderer state]
  ;; state atom holds: {:running? true :renderables [] :window 0}
  )

(defn make-game
  ([title]           (make-game title 1280 720))
  ([title w h]
   (->Game title w h
           (vk/make-vulkan-renderer)
           (atom {:running? false :renderables [] :window 0}))))

(defn add-renderable! [game renderable]
  (swap! (:state game) update :renderables conj renderable))

(defn run!
  "Start the game loop. lifecycle must satisfy GameLifecycle."
  [game lifecycle]
  ;; TODO #4 — GLFW window creation, event loop, delta time, renderer wiring
  (println (str "[Game] run! '" (:title game) "' — TODO: GLFW + render loop"))
  (on-init! lifecycle)
  ;; Stub: single tick at delta=0
  (on-tick! lifecycle 0.0)
  (on-done! lifecycle))
