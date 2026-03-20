(ns spin-shooter.scripts.gameover
  "Spin Shooter — game over scene.

   Displays a reddish screen. Press R to restart (swap back to :game)."
  (:require [spock.scene       :as scene]
            [spock.renderer.core :as renderer]
            [spock.input.core  :as input]
            [spock.log         :as log]))

(defn on-init [game sc shared-state]
  (log/log "spin-shooter gameover on-init score=" (:score @shared-state))
  ;; Set a reddish clear color
  (renderer/set-clear-color! (:renderer game) [0.55 0.05 0.05 1.0]))

(defn on-tick [game sc delta shared-state]
  ;; Press R to restart
  (when (input/key-pressed? :r)
    (swap! shared-state assoc :score 0)
    (renderer/set-clear-color! (:renderer game) [0.1 0.12 0.18 1.0])
    (scene/swap! :game)))

(defn on-done [game sc shared-state]
  (log/log "spin-shooter gameover on-done"))
