(ns hello.core
  "Hello Vulkan — triangle with animated clear color.
   Mirrors exokomodo/drakon examples/hello/main.cpp"
  (:require [spock.game.core     :as game]
            [spock.renderable.core :as renderable]
            [spock.renderer.core   :as renderer]
            [spock.pipeline.core   :as pipeline])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; TriangleRenderable
;; ---------------------------------------------------------------------------
(defrecord TriangleRenderable [shader-dir pipeline-atom]
  renderable/Renderable

  (draw [this command-buffer device render-pass extent]
    (when (empty? @pipeline-atom)
      (println "[TriangleRenderable] draw called for first time"))
    ;; Lazily build the pipeline on first draw call
    (when (nil? (:pipeline @pipeline-atom))
      (println "[TriangleRenderable] building pipeline, shader-dir:" shader-dir)
      (try
        (let [pl (-> (pipeline/builder device render-pass {:width  (.width  ^org.lwjgl.vulkan.VkExtent2D extent)
                                                            :height (.height ^org.lwjgl.vulkan.VkExtent2D extent)})
                     (pipeline/vert-path (str shader-dir "triangle.vert"))
                     (pipeline/frag-path (str shader-dir "triangle.frag"))
                     (pipeline/topology  :triangle-list)
                     (pipeline/cull-mode :back)
                     (pipeline/build!))]
          (println "[TriangleRenderable] pipeline built:" pl)
          (reset! pipeline-atom pl))
        (catch Exception e
          (println "[TriangleRenderable] pipeline build FAILED:" (.getMessage e))
          (.printStackTrace e))))
    ;; Record draw commands
    (let [{:keys [pipeline layout]} @pipeline-atom]
      (if (and pipeline layout)
        (do
          (println "[TriangleRenderable] vkCmdBindPipeline + vkCmdDraw")
          (org.lwjgl.vulkan.VK10/vkCmdBindPipeline
            ^org.lwjgl.vulkan.VkCommandBuffer command-buffer
            org.lwjgl.vulkan.VK10/VK_PIPELINE_BIND_POINT_GRAPHICS
            (long pipeline))
          (org.lwjgl.vulkan.VK10/vkCmdDraw
            ^org.lwjgl.vulkan.VkCommandBuffer command-buffer
            3 1 0 0))
        (println "[TriangleRenderable] draw skipped — no pipeline")))))

(defn make-triangle-renderable [shader-dir]
  (->TriangleRenderable shader-dir (atom {})))

;; ---------------------------------------------------------------------------
;; HelloGame lifecycle
;; ---------------------------------------------------------------------------
(defn- update-clear-color! [game delta dirs]
  (let [color     (renderer/get-clear-color (:renderer game))
        new-color (mapv (fn [c d] (-> (+ c (* (double d) (double delta)))
                                      (max 0.0)
                                      (min 1.0)))
                        color dirs)
        new-dirs  (mapv (fn [c' d]
                          (cond (>= (double c') 1.0) (- (Math/abs (double d)))
                                (<= (double c') 0.0) (Math/abs (double d))
                                :else d))
                        new-color dirs)]
    (renderer/set-clear-color! (:renderer game) new-color)
    new-dirs))

(defrecord HelloGame [g dirs-atom]
  game/GameLifecycle

  (on-init! [_this]
    (println "Initializing Hello Vulkan")
    (let [shader-dir (str (System/getProperty "user.dir")
                          "/examples/hello/shaders/")]
      (game/add-renderable! g (make-triangle-renderable shader-dir))))

  (on-tick! [_this delta]
    (swap! dirs-atom #(update-clear-color! g delta %)))

  (on-done! [_this]
    (println "Hello Vulkan done")))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------
(defn -main [& _args]
  (let [g  (game/make-game "Hello Vulkan")
        lc (->HelloGame g (atom [0.1 0.2 0.3 0.0]))]
    (game/start! g lc)))
