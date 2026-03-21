(ns teapot.scripts.teapot
  "Teapot scene script — rotates the teapot and orbits the camera."
  (:require [spock.scene    :as scene]
            [spock.entity   :as entity]
            [spock.renderable.mesh :as mesh]
            [spock.camera   :as camera]
            [spock.log      :as log])
  (:import [org.lwjgl.glfw GLFW]))

(def ^:private state (atom {:angle 0.0 :elapsed 0.0}))

;; ---------------------------------------------------------------------------
;; Matrix helpers
;; ---------------------------------------------------------------------------

(defn- rotation-y
  "4x4 rotation matrix around Y axis (column-major float array)."
  [radians]
  (let [c (Math/cos radians)
        s (Math/sin radians)]
    (float-array [c   0.0 (- s) 0.0
                  0.0 1.0 0.0   0.0
                  s   0.0 c    0.0
                  0.0 0.0 0.0   1.0])))

(defn- translation
  "4x4 translation matrix (column-major float array)."
  [x y z]
  (float-array [1.0 0.0 0.0 0.0
                0.0 1.0 0.0 0.0
                0.0 0.0 1.0 0.0
                (float x) (float y) (float z) 1.0]))

(defn- mat4-mul
  "Multiply two 4x4 column-major matrices."
  [^floats a ^floats b]
  (let [out (float-array 16)]
    (dotimes [col 4]
      (dotimes [row 4]
        (aset out (+ row (* col 4))
              (float (reduce + (map (fn [k] (* (aget a (+ row (* k 4)))
                                               (aget b (+ k (* col 4)))))
                                   (range 4)))))))
    out))

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(defn on-init [game scene shared-state]
  (log/log "teapot on-init"))

(defn on-tick [game scene delta shared-state]
  (let [dt      (double delta)
        {:keys [angle elapsed]} @state
        new-angle   (+ angle (* dt 0.8))
        new-elapsed (+ elapsed dt)
        ;; Orbit camera
        cam-x   (* 4.0 (Math/sin new-angle))
        cam-z   (* 4.0 (Math/cos new-angle))
        cam     (camera/make-camera [cam-x 2.0 cam-z] [0.0 0.0 0.0] [0.0 1.0 0.0] 45.0 0.1 100.0)
        aspect  (/ 1280.0 720.0)
        vp      (camera/vp-matrix cam aspect)
        ;; Model: scale down teapot (it's large), center it
        scale   (float-array [0.1 0.0 0.0 0.0
                               0.0 0.1 0.0 0.0
                               0.0 0.0 0.1 0.0
                               0.0 0.0 0.0 1.0])
        model   (mat4-mul (translation 0.0 -0.5 0.0) scale)]
    (swap! state assoc :angle new-angle :elapsed new-elapsed)
    ;; Update renderable instances
    (let [entities (scene/get-entities scene)
          find-r   (fn [id]
                     (some-> (filter #(= (:id %) id) entities)
                             first
                             (entity/get-component :renderable)))]
      (when-let [r (find-r :teapot)]
        (when-let [inst (mesh/instances r)]
          (reset! inst [{:model model :vp vp :color [0.8 0.6 0.3 1.0]}]))))
    ;; Close after 30 seconds
    (when (>= new-elapsed 30.0)
      (let [window (:window @(:state game))]
        (GLFW/glfwSetWindowShouldClose window true)))))

(defn on-done [game scene shared-state]
  (log/log "teapot on-done"))
