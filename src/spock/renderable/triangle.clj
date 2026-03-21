(ns spock.renderable.triangle
  "Built-in :triangle renderable — draws a hardcoded 3-vertex triangle
   using caller-supplied vertex and fragment shaders.

   Configuration:
     {:type    :triangle
      :shaders {:vert \"path/to/triangle.vert\"
                :frag \"path/to/triangle.frag\"}}

   Registers itself with spock.renderable.registry on namespace load."
  (:require [spock.renderable.core     :as renderable]
            [spock.renderable.registry :as registry]
            [spock.pipeline.core       :as pipeline]
            [spock.renderer.core       :as renderer]
            [spock.shader.builtins     :as builtins]
            [spock.log                 :as log])
  (:import [org.lwjgl.vulkan VK10]))

(defn- make-triangle-renderable [cfg _renderer]
  (let [shaders   (:shaders cfg)
        vert-path (:vert shaders)
        frag-path (:frag shaders)
        pipeline-atom (atom {})]
    (with-meta
      (reify renderable/Renderable
        (draw [_this command-buffer _device _render-pass _extent]
          (let [{:keys [pipeline layout]} @pipeline-atom]
            (if (and pipeline layout)
              (do
                (VK10/vkCmdBindPipeline
                 ^org.lwjgl.vulkan.VkCommandBuffer command-buffer
                 VK10/VK_PIPELINE_BIND_POINT_GRAPHICS
                 (long pipeline))
                (VK10/vkCmdDraw
                 ^org.lwjgl.vulkan.VkCommandBuffer command-buffer
                 3 1 0 0))
              (log/trace ":triangle draw: no pipeline yet"))))
        (cleanup! [_this _device]
          (pipeline/destroy! @pipeline-atom)
          (reset! pipeline-atom {})))
      {:pipeline-atom pipeline-atom
       :vert-path     vert-path
       :frag-path     frag-path})))

(defn build-pipeline!
  "Build the Vulkan pipeline for a triangle renderable.
   Uses builtin DSL shaders by default; falls back to :shaders paths if provided."
  [renderable renderer]
  (let [{:keys [pipeline-atom vert-path frag-path]} (meta renderable)
        rp  (renderer/get-render-pass renderer)
        dev (renderer/get-device renderer)
        builder (pipeline/builder dev rp)
        builder (if (and vert-path frag-path)
                  (-> builder
                      (pipeline/vert-path vert-path)
                      (pipeline/frag-path frag-path))
                  (-> builder
                      (pipeline/vert-spv builtins/triangle-vert)
                      (pipeline/frag-spv builtins/triangle-frag)))
        pl (-> builder
               (pipeline/topology :triangle-list)
               (pipeline/cull-mode :none)
               (pipeline/build!))]
    (reset! pipeline-atom pl)))

;; Register on namespace load
(registry/register-renderable! :triangle make-triangle-renderable)
