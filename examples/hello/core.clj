(ns spock.examples.hello.core
  "Hello Vulkan — triangle with animated clear color.
   Mirrors exokomodo/drakon examples/hello/main.cpp"
  (:require [spock.game.core :as game]
            [spock.renderable.core :as renderable])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; TriangleRenderable
;; ---------------------------------------------------------------------------
;; TODO #3 — implement pipeline creation once Renderer is wired up
(defrecord TriangleRenderable [shader-dir pipeline-state]
  renderable/Renderable
  (draw [this command-buffer device render-pass extent]
    ;; TODO #6 — ensure pipeline, vkCmdBindPipeline, vkCmdDraw
    (println "[TriangleRenderable] draw — TODO")))

(defn make-triangle-renderable [shader-dir]
  (->TriangleRenderable shader-dir (atom {:pipeline-layout 0
                                          :graphics-pipeline 0})))

;; ---------------------------------------------------------------------------
;; HelloGame lifecycle
;; ---------------------------------------------------------------------------
(def clear-color-directions (atom [0.1 0.2 0.3 0.0]))

(defn- update-clear-color [game delta]
  (let [color     (game/get-clear-color (:renderer game))  ; TODO: renderer protocol
        dirs      @clear-color-directions
        new-color (mapv (fn [c d]
                          (let [c' (+ c (* d delta))]
                            (cond (> c' 1.0) 1.0
                                  (< c' 0.0) 0.0
                                  :else      c')))
                        color dirs)
        new-dirs  (mapv (fn [c' c d]
                          (cond (>= c' 1.0) (- (Math/abs d))
                                (<= c' 0.0) (Math/abs d)
                                :else       d))
                        new-color color dirs)]
    (reset! clear-color-directions new-dirs)
    new-color))

(defrecord HelloGame [g]
  game/GameLifecycle

  (on-init! [this]
    (println "Initializing Hello Vulkan")
    (let [shader-dir (str (System/getProperty "user.dir")
                          "/examples/hello/shaders/")]
      (game/add-renderable! g (make-triangle-renderable shader-dir))))

  (on-tick! [this delta]
    (update-clear-color g delta))

  (on-done! [this]
    (println "Hello Vulkan done")))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------
(defn -main [& _args]
  (let [g  (game/make-game "Hello Vulkan")
        lc (->HelloGame g)]
    (game/run! g lc)))
