(ns hello.scripts.hello
  "Lifecycle script for the Hello Vulkan scene.
   Animates the clear color and auto-closes after 5 seconds."
  (:require [spock.renderer.core :as renderer]
            [spock.audio.core    :as audio]
            [spock.scene         :as scene]
            [spock.entity        :as entity]
            [spock.log           :as log])
  (:import [org.lwjgl.glfw GLFW]))

(def ^:private state (atom {:dirs    [0.1 0.2 0.3 0.0]
                            :elapsed 0.0}))

(defn on-init [game scene shared-state]
  (log/log "hello.scripts.hello/on-init")
  ;; Play the beep once on scene start
  (when-let [beep (->> (scene/get-entities scene)
                       (filter #(= (:id %) :beep))
                       first
                       (#(entity/get-component % :audio)))]
    (audio/play! beep :gain 0.15)))

(defn on-tick [game scene delta shared-state]
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
    (when (>= new-elapsed 5.0)
      (let [window (:window @(:state game))]
        (GLFW/glfwSetWindowShouldClose window true)))))

(defn on-done [game scene shared-state]
  (log/log "hello.scripts.hello/on-done"))
