(ns hello.core
  "Hello Vulkan — triangle with animated clear color.
   Mirrors exokomodo/drakon examples/hello/main.cpp"
  (:require [spock.game.core      :as game]
            [spock.renderable.core :as renderable]
            [spock.renderer.core   :as renderer]
            [spock.pipeline.core   :as pipeline]
            [spock.log             :as log])
  (:import [org.lwjgl.glfw GLFW]
           [org.lwjgl.vulkan VkCommandBuffer VK10])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; TriangleRenderable
;; ---------------------------------------------------------------------------
(defrecord TriangleRenderable [pipeline-atom]
  renderable/Renderable

  (draw [_this command-buffer _device _render-pass _extent]
    (let [{:keys [pipeline layout]} @pipeline-atom]
      (log/log "draw called pipeline=" pipeline "layout=" layout "cb=" command-buffer)
      (if (and pipeline layout)
        (do
          (VK10/vkCmdBindPipeline
            ^VkCommandBuffer command-buffer
            VK10/VK_PIPELINE_BIND_POINT_GRAPHICS
            (long pipeline))
          (VK10/vkCmdDraw
            ^VkCommandBuffer command-buffer
            3 1 0 0)
          (log/log "draw: vkCmdDraw issued"))
        (log/log "draw: NO PIPELINE")))))

(defn make-triangle-renderable []
  (->TriangleRenderable (atom {})))

(defn build-pipeline! [renderable renderer shader-dir]
  (let [ext    (renderer/get-extent      renderer)
        rp     (renderer/get-render-pass renderer)
        dev    (renderer/get-device      renderer)]
    (log/log "build-pipeline! extent=" ext "rp=" rp "dev=" dev)
    (try
      (let [pl (-> (pipeline/builder dev rp)
                   (pipeline/vert-path (str shader-dir "triangle.vert"))
                   (pipeline/frag-path (str shader-dir "triangle.frag"))
                   (pipeline/topology   :triangle-list)
                   (pipeline/cull-mode  :none)
                   (pipeline/build!))]
        (log/log "pipeline built:" (:pipeline pl))
        (reset! (:pipeline-atom renderable) pl))
      (catch Exception e
        (log/log "PIPELINE BUILD FAILED:" (.getMessage e))
        (.printStackTrace e)))))

;; ---------------------------------------------------------------------------
;; HelloGame lifecycle
;; ---------------------------------------------------------------------------
(defn- update-clear-color! [game delta dirs]
  (let [color     (renderer/get-clear-color (:renderer game))
        new-color (mapv (fn [c d] (-> (+ c (* (double d) (double delta)))
                                      (max 0.0) (min 1.0)))
                        color dirs)
        new-dirs  (mapv (fn [c' d]
                          (cond (>= (double c') 1.0) (- (Math/abs (double d)))
                                (<= (double c') 0.0) (Math/abs (double d))
                                :else d))
                        new-color dirs)]
    (renderer/set-clear-color! (:renderer game) new-color)
    new-dirs))

(defrecord HelloGame [g triangle dirs-atom]
  game/GameLifecycle

  (on-init! [_this]
    (log/log "on-init! begin")
    (let [shader-dir (str (System/getProperty "user.dir")
                          "/examples/hello/shaders/")]
      (build-pipeline! triangle (:renderer g) shader-dir)
      (game/add-renderable! g triangle))
    (log/log "on-init! done"))

  (on-tick! [_this delta]
    (swap! dirs-atom #(update-clear-color! g delta %))
    ;; Auto-close after 5 seconds.
    (swap! (:state g) (fn [s]
                        (let [t (+ (get s :elapsed 0.0) delta)]
                          (when (>= t 5.0)
                            (GLFW/glfwSetWindowShouldClose (:window s) true))
                          (assoc s :elapsed t)))))

  (on-done! [_this]
    (log/log "on-done!")))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------
(defn -main [& _args]
  (log/log "spock hello starting")
  (let [g        (game/make-game "Hello Vulkan")
        triangle (make-triangle-renderable)
        lc       (->HelloGame g triangle (atom [0.1 0.2 0.3 0.0]))]
    (game/start! g lc))
  ;; LWJGL/AWT threads keep the JVM alive after start! returns.
  ;; Force-exit so closing the window (or pressing Escape) actually quits.
  (System/exit 0))
