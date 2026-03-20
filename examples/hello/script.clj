(ns hello.script
  "Lifecycle script for the Hello Vulkan EDN example.
   Animates the clear color and auto-closes after 5 seconds."
  (:require [spock.renderer.core :as renderer]
            [spock.log           :as log])
  (:import [org.lwjgl.glfw GLFW]))

;; State local to this script — a simple atom is fine for a single-scene game.
(def ^:private state (atom {:dirs    [0.1 0.2 0.3 0.0]
                             :elapsed 0.0}))

(defn on-init [game]
  (log/log "hello.script/on-init"))

(defn on-tick [game delta]
  (let [r      (:renderer game)
        color  (renderer/get-clear-color r)
        {:keys [dirs elapsed]} @state
        new-color (mapv (fn [c d]
                          (-> (+ c (* (double d) (double delta)))
                              (max 0.0) (min 1.0)))
                        color dirs)
        new-dirs  (mapv (fn [c' d]
                          (cond (>= (double c') 1.0) (- (Math/abs (double d)))
                                (<= (double c') 0.0) (Math/abs (double d))
                                :else d))
                        new-color dirs)
        new-elapsed (+ elapsed delta)]
    (renderer/set-clear-color! r new-color)
    (swap! state assoc :dirs new-dirs :elapsed new-elapsed)
    ;; Auto-close after 5 seconds
    (when (>= new-elapsed 5.0)
      (let [window (:window @(:state game))]
        (GLFW/glfwSetWindowShouldClose window true)))))

(defn on-done [game]
  (log/log "hello.script/on-done"))
