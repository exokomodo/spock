(ns spock.renderable.triangle
  "Built-in :triangle renderable — draws a hardcoded 3-vertex triangle.

   Configuration (all forms accepted):

   1. No :shaders — uses built-in DSL RGB triangle:
      {:type :triangle}

   2. File paths:
      {:type    :triangle
       :shaders {:vert \"path/to/triangle.vert\"
                 :frag \"path/to/triangle.frag\"}}

   3. Inline DSL descriptor maps:
      {:type    :triangle
       :shaders {:vert {:outputs [{:name :frag-color :type :vec3 :location 0}]
                        :main [(set! gl-Position (vec4 0.0 0.0 0.0 1.0))
                               (set! frag-color (vec3 1.0 0.0 0.0))]}
                 :frag {:inputs  [{:name :frag-color :type :vec3 :location 0}]
                        :outputs [{:name :out-color :type :vec4 :location 0}]
                        :main    [(set! out-color (vec4 frag-color 1.0))]}}}

   Registers itself with spock.renderable.registry on namespace load."
  (:require [spock.renderable.core     :as renderable]
            [spock.renderable.registry :as registry]
            [spock.pipeline.core       :as pipeline]
            [spock.renderer.core       :as renderer]
            [spock.shader.builtins     :as builtins]
            [spock.shader.dsl          :as dsl]
            [spock.log                 :as log])
  (:import [org.lwjgl.vulkan VK10]))

(defn- shader-spv
  "Compile a shader descriptor or load a path. Returns a SPIR-V ByteBuffer.
   - string → compile file path via shaderc
   - map    → compile inline DSL descriptor"
  [stage descriptor-or-path]
  (cond
    (string? descriptor-or-path)
    (do (require 'spock.shader.core)
        (let [compile-fn (requiring-resolve 'spock.shader.core/compile-glsl)
              load-fn    (requiring-resolve 'spock.shader.core/load-spirv)]
          (compile-fn descriptor-or-path)
          (load-fn (str descriptor-or-path ".spv"))))
    (map? descriptor-or-path)
    (dsl/compile-shader (assoc descriptor-or-path :stage stage))
    :else nil))

(defn- make-triangle-renderable [cfg _renderer]
  (let [shaders       (:shaders cfg)
        vert-shader   (:vert shaders)
        frag-shader   (:frag shaders)
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
       :vert-shader   vert-shader
       :frag-shader   frag-shader
       ;; vert-path retained for edn post-init dispatch
       :vert-path     (when (string? vert-shader) vert-shader)})))

(defn build-pipeline!
  "Build the Vulkan pipeline for a triangle renderable."
  [renderable renderer]
  (let [{:keys [pipeline-atom vert-shader frag-shader]} (meta renderable)
        rp  (renderer/get-render-pass renderer)
        dev (renderer/get-device renderer)
        builder (-> (pipeline/builder dev rp)
                    (pipeline/topology :triangle-list)
                    (pipeline/cull-mode :none))
        builder (cond
                  ;; Both provided — path or DSL map
                  (and vert-shader frag-shader)
                  (-> builder
                      (pipeline/vert-spv (shader-spv :vertex vert-shader))
                      (pipeline/frag-spv (shader-spv :fragment frag-shader)))
                  ;; Neither provided — use built-in RGB triangle
                  :else
                  (-> builder
                      (pipeline/vert-spv builtins/triangle-vert)
                      (pipeline/frag-spv builtins/triangle-frag)))
        pl (pipeline/build! builder)]
    (reset! pipeline-atom pl)))

;; Register on namespace load
(registry/register-renderable! :triangle make-triangle-renderable)
