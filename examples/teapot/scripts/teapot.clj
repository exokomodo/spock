(ns teapot.scripts.teapot
  "Teapot scene script — orbits the camera around a slowly-rotating teapot."
  (:require [spock.scene           :as scene]
            [spock.entity          :as entity]
            [spock.renderable.mesh :as mesh]
            [spock.camera          :as camera]
            [spock.log             :as log])
  (:import [org.lwjgl.glfw GLFW]))

(def ^:private state (atom {:angle 0.0 :elapsed 0.0}))

;; ---------------------------------------------------------------------------
;; Matrix helpers
;; ---------------------------------------------------------------------------

(defn- mat4-mul
  "Multiply two 4x4 column-major float-arrays."
  [^floats a ^floats b]
  (let [out (float-array 16)]
    (dotimes [col 4]
      (dotimes [row 4]
        (aset out (+ row (* col 4))
              (float (loop [k 0 s 0.0]
                       (if (= k 4) s
                           (recur (inc k)
                                  (+ s (* (aget a (+ row (* k 4)))
                                          (aget b (+ k (* col 4))))))))))))
    out))

(defn- scale-matrix
  "Uniform scale 4x4 matrix (column-major)."
  [s]
  (float-array [(float s) 0 0 0
                0 (float s) 0 0
                0 0 (float s) 0
                0 0 0 1]))

(defn- translation-matrix
  "Translation 4x4 matrix (column-major)."
  [x y z]
  (float-array [1 0 0 0
                0 1 0 0
                0 0 1 0
                (float x) (float y) (float z) 1]))

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(defn on-init [_game _scene _shared]
  (reset! state {:angle 0.0 :elapsed 0.0})
  (log/info "teapot on-init"))

(defn on-tick [game scene delta _shared]
  (let [{:keys [angle elapsed]} @state
        dt          (double delta)
        new-angle   (+ angle (* dt 0.6))
        new-elapsed (+ elapsed dt)

        ;; Orbit camera around the teapot
        cam-x  (* 5.0 (Math/sin new-angle))
        cam-z  (* 5.0 (Math/cos new-angle))
        cam    (camera/make-camera [cam-x 2.5 cam-z] [0.0 0.5 0.0] [0.0 1.0 0.0]
                                   45.0 0.1 100.0)
        aspect (/ 1280.0 720.0)
        vp     (camera/vp-matrix cam aspect)

        ;; Scale teapot down (it's large) and lift it slightly
        model (mat4-mul (translation-matrix 0.0 -0.3 0.0)
                        (scale-matrix 0.08))]

    (swap! state assoc :angle new-angle :elapsed new-elapsed)

    ;; Push transform to the mesh renderable
    (let [entities (scene/get-entities scene)]
      (when-let [teapot-ent (first (filter #(= (:id %) :teapot) entities))]
        (when-let [r (entity/get-component teapot-ent :renderable)]
          (reset! (mesh/instances r)
                  [{:model model :vp vp :color [0.8 0.6 0.3 1.0]}]))))

    ;; Auto-close after 30 seconds
    (when (>= new-elapsed 30.0)
      (GLFW/glfwSetWindowShouldClose (:window @(:state game)) true))))

(defn on-done [_game _scene _shared]
  (log/info "teapot on-done"))
