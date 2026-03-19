(ns spock.examples.hello.core
  "Hello Vulkan — triangle with animated clear color.
   Mirrors exokomodo/drakon examples/hello/main.cpp"
  (:require [spock.game.core :as game]
            [spock.renderable.core :as renderable]
            [spock.renderer.core :as renderer])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; TriangleRenderable
;; ---------------------------------------------------------------------------
;; TODO #3 — implement pipeline creation (depends on #2 being fully wired)
(defrecord TriangleRenderable [shader-dir pipeline-state]
  renderable/Renderable
  (draw [this command-buffer device render-pass extent]
    ;; TODO #6 — ensure-pipeline, vkCmdBindPipeline, vkCmdDraw 3 verts
    nil))

(defn make-triangle-renderable [shader-dir]
  (->TriangleRenderable shader-dir (atom {:pipeline-layout 0
                                          :graphics-pipeline 0})))

;; ---------------------------------------------------------------------------
;; HelloGame lifecycle
;; ---------------------------------------------------------------------------
(defn- update-clear-color! [game delta dirs]
  (let [color  (renderer/get-clear-color (:renderer game))
        new-color (mapv (fn [c d] (-> (+ c (* d delta))
                                      (max 0.0)
                                      (min 1.0)))
                        color dirs)
        new-dirs  (mapv (fn [c' d]
                          (cond (>= c' 1.0) (- (Math/abs d))
                                (<= c' 0.0)  (Math/abs d)
                                :else        d))
                        new-color dirs)]
    (renderer/set-clear-color! (:renderer game) new-color)
    new-dirs))

(defrecord HelloGame [g dirs-atom]
  game/GameLifecycle

  (on-init! [this]
    (println "Initializing Hello Vulkan")
    (let [shader-dir (str (System/getProperty "user.dir")
                          "/examples/hello/shaders/")]
      (game/add-renderable! g (make-triangle-renderable shader-dir))))

  (on-tick! [this delta]
    (swap! dirs-atom #(update-clear-color! g delta %)))

  (on-done! [this]
    (println "Hello Vulkan done")))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------
(defn -main [& _args]
  (let [g  (game/make-game "Hello Vulkan")
        lc (->HelloGame g (atom [0.1 0.2 0.3 0.0]))]
    (game/start! g lc)))
