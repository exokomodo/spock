(ns spin-shooter.scripts.gameover
  "Spin Shooter — game over scene.

   Displays a reddish screen. Press R to restart (swap back to :game).
   If :auto-close-secs is set in the scene EDN (non-zero), the game
   closes automatically after that many seconds."
  (:require [spock.scene        :as scene]
            [spock.renderer.core :as renderer]
            [spock.input.core   :as input]
            [spock.log          :as log]
            [spock.audio.core :as audio])
  (:import [org.lwjgl.glfw GLFW]))

(def ^:private elapsed (atom 0.0))

(def ^:private state
  (atom {:sound/gameover    nil}))

(defn on-init [game sc shared-state]
  (log/info "gameover: score=" (:score @shared-state))
  (reset! elapsed 0.0)
  (renderer/set-clear-color! (:renderer game) [0.55 0.05 0.05 1.0])
  (let [base-dir "examples/spin_shooter/audio"
        gameover-id (try (audio/load-sound! (str base-dir "/gameover.wav"))
                         (catch Exception e
                           (log/warn "audio: could not load gameover.wav:" (.getMessage e))
                           nil))]
    ;; Reset local state with fresh gun layout + loaded sound ids
    (reset! state {:sound/gameover gameover-id}))
  ;; Play gameover sound (if loaded successfully)
  (when-let [id (:sound/gameover @state)]
    (audio/play! id)))

(defn on-tick [game sc delta shared-state]
  (let [auto-close (get (:config sc) :auto-close-secs 0)
        t          (swap! elapsed + delta)]
    ;; R key → restart immediately
    (when (input/key-pressed? :r)
      (swap! shared-state assoc :score 0)
      (renderer/set-clear-color! (:renderer game) [0.1 0.12 0.18 1.0])
      (scene/swap! :game))
    ;; Auto-close after timeout (0 = disabled)
    (when (and (pos? auto-close) (>= t auto-close))
      (let [window (:window @(:state game))]
        (GLFW/glfwSetWindowShouldClose window true)))))

(defn on-done [game sc shared-state]
  (log/debug "gameover: on-done"))
