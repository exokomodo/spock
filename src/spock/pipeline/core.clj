(ns spock.pipeline.core
  "Data-driven Vulkan graphics pipeline builder.

   Usage:
     (-> (pipeline/builder device render-pass extent)
         (pipeline/vert-spv   spirv-byte-buffer)
         (pipeline/frag-spv   spirv-byte-buffer)
         (pipeline/topology   :triangle-list)   ; default
         (pipeline/cull-mode  :back)            ; default
         (pipeline/blend-mode :opaque)          ; default
         (pipeline/vertex-input [])             ; default — no vertex buffers
         (pipeline/build!))
     ;; => {:pipeline long :layout long}

   All options are optional; the defaults produce a simple
   fill-mode triangle pipeline matching the drakon hello example."
  (:require [spock.shader.core :as shader])
  (:import [org.lwjgl.system MemoryStack]
           [org.lwjgl.vulkan
            VK10
            VkDevice
            VkGraphicsPipelineCreateInfo
            VkPipelineShaderStageCreateInfo
            VkPipelineVertexInputStateCreateInfo
            VkPipelineInputAssemblyStateCreateInfo
            VkPipelineViewportStateCreateInfo
            VkPipelineRasterizationStateCreateInfo
            VkPipelineMultisampleStateCreateInfo
            VkPipelineColorBlendStateCreateInfo
            VkPipelineColorBlendAttachmentState
            VkPipelineLayoutCreateInfo
            VkShaderModuleCreateInfo
            VkViewport VkRect2D VkOffset2D VkExtent2D]))

;; ---------------------------------------------------------------------------
;; Keyword → Vulkan constant maps
;; ---------------------------------------------------------------------------
(def ^:private topology-map
  {:point-list                  VK10/VK_PRIMITIVE_TOPOLOGY_POINT_LIST
   :line-list                   VK10/VK_PRIMITIVE_TOPOLOGY_LINE_LIST
   :line-strip                  VK10/VK_PRIMITIVE_TOPOLOGY_LINE_STRIP
   :triangle-list               VK10/VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
   :triangle-strip              VK10/VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP
   :triangle-fan                VK10/VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN})

(def ^:private cull-mode-map
  {:none  VK10/VK_CULL_MODE_NONE
   :front VK10/VK_CULL_MODE_FRONT_BIT
   :back  VK10/VK_CULL_MODE_BACK_BIT
   :both  VK10/VK_CULL_MODE_FRONT_AND_BACK})

(def ^:private polygon-mode-map
  {:fill  VK10/VK_POLYGON_MODE_FILL
   :line  VK10/VK_POLYGON_MODE_LINE
   :point VK10/VK_POLYGON_MODE_POINT})

;; ---------------------------------------------------------------------------
;; Builder constructor
;; ---------------------------------------------------------------------------
(defn builder
  "Create a pipeline config map.
   device      — VkDevice
   render-pass — long handle
   extent      — {:width int :height int}"
  [^VkDevice device render-pass extent]
  {:device      device
   :render-pass render-pass
   :extent      extent
   ;; Shader stages (populated by vert-spv / frag-spv)
   :vert-spv    nil
   :frag-spv    nil
   ;; Pipeline state options
   :topology    :triangle-list
   :cull-mode   :back
   :front-face  :clockwise
   :polygon-mode :fill
   :blend-mode  :opaque
   ;; Vertex input — seq of VkVertexInputBindingDescription / AttributeDescription maps
   ;; Empty = no vertex buffers (hardcoded vertices in shader)
   :vertex-bindings   []
   :vertex-attributes []})

;; ---------------------------------------------------------------------------
;; Builder option setters
;; ---------------------------------------------------------------------------
(defn vert-spv
  "Set the vertex shader SPIR-V. buf is a java.nio.ByteBuffer."
  [config ^java.nio.ByteBuffer buf]
  (assoc config :vert-spv buf))

(defn frag-spv
  "Set the fragment shader SPIR-V. buf is a java.nio.ByteBuffer."
  [config ^java.nio.ByteBuffer buf]
  (assoc config :frag-spv buf))

(defn vert-path
  "Load and set vertex shader from a GLSL source path (compiles to SPIR-V)."
  [config ^String glsl-path]
  (when-not (shader/compile-glsl glsl-path)
    (throw (RuntimeException. (str "Failed to compile vertex shader: " glsl-path))))
  (let [buf (shader/load-spirv (str glsl-path ".spv"))]
    (when-not buf
      (throw (RuntimeException. (str "Failed to load vertex SPIR-V: " glsl-path ".spv"))))
    (vert-spv config buf)))

(defn frag-path
  "Load and set fragment shader from a GLSL source path (compiles to SPIR-V)."
  [config ^String glsl-path]
  (when-not (shader/compile-glsl glsl-path)
    (throw (RuntimeException. (str "Failed to compile fragment shader: " glsl-path))))
  (let [buf (shader/load-spirv (str glsl-path ".spv"))]
    (when-not buf
      (throw (RuntimeException. (str "Failed to load fragment SPIR-V: " glsl-path ".spv"))))
    (frag-spv config buf))  )

(defn topology   [config t]   (assoc config :topology t))
(defn cull-mode  [config m]   (assoc config :cull-mode m))
(defn front-face [config f]   (assoc config :front-face f))
(defn polygon-mode [config m] (assoc config :polygon-mode m))
(defn blend-mode [config m]   (assoc config :blend-mode m))
(defn vertex-input
  "Set vertex bindings + attributes.
   bindings   — seq of {:binding int :stride int :input-rate :vertex/:instance}
   attributes — seq of {:location int :binding int :format int :offset int}"
  [config bindings attributes]
  (assoc config :vertex-bindings bindings :vertex-attributes attributes))

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------
(defn- create-shader-module
  "Allocate a VkShaderModule from a SPIR-V ByteBuffer. Returns the handle (long)."
  [^VkDevice device ^java.nio.ByteBuffer spv]
  (let [^MemoryStack stack (MemoryStack/stackPush)
        ci (doto (VkShaderModuleCreateInfo/calloc stack)
             (.sType VK10/VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
             (.pCode spv))
        lp (.mallocLong stack 1)
        _  (let [r (VK10/vkCreateShaderModule device ci nil lp)]
             (when (not= r VK10/VK_SUCCESS)
               (MemoryStack/stackPop)
               (throw (RuntimeException. (str "vkCreateShaderModule failed (VkResult=" r ")")))))]
    (let [handle (.get lp 0)]
      (MemoryStack/stackPop)
      handle)))

(defn- front-face->vk [f]
  (case f
    :clockwise         VK10/VK_FRONT_FACE_CLOCKWISE
    :counter-clockwise VK10/VK_FRONT_FACE_COUNTER_CLOCKWISE))

;; ---------------------------------------------------------------------------
;; build!
;; ---------------------------------------------------------------------------
(defn build!
  "Allocate VkPipelineLayout + VkGraphicsPipeline from the config map.
   Returns {:pipeline long :layout long} or throws on failure.
   Caller is responsible for calling destroy! when done."
  [config]
  (let [{:keys [^VkDevice device render-pass extent
                vert-spv frag-spv
                topology cull-mode front-face polygon-mode
                blend-mode]} config]
    (when-not vert-spv (throw (RuntimeException. "Pipeline builder: no vertex shader set")))
    (when-not frag-spv (throw (RuntimeException. "Pipeline builder: no fragment shader set")))

    (let [^MemoryStack stack (MemoryStack/stackPush)
          ;; Shader modules (temporary — destroyed after pipeline creation)
          vert-mod (create-shader-module device vert-spv)
          frag-mod (create-shader-module device frag-spv)

          ;; Shader stages
          stages (doto (VkPipelineShaderStageCreateInfo/callocStack 2 stack)
                   (-> (.get 0)
                       (doto (.sType VK10/VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                             (.stage VK10/VK_SHADER_STAGE_VERTEX_BIT)
                             (.module vert-mod)
                             (.pName (.UTF8 stack "main" true))))
                   (-> (.get 1)
                       (doto (.sType VK10/VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                             (.stage VK10/VK_SHADER_STAGE_FRAGMENT_BIT)
                             (.module frag-mod)
                             (.pName (.UTF8 stack "main" true)))))

          ;; Vertex input — no vertex buffers by default
          ;; No vertex buffers — hardcoded vertices in shader.
          ;; Counts are derived from buffer sizes; passing nil leaves them 0.
          vertex-input-ci (doto (VkPipelineVertexInputStateCreateInfo/calloc stack)
                            (.sType VK10/VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                            (.pVertexBindingDescriptions nil)
                            (.pVertexAttributeDescriptions nil))

          ;; Input assembly
          input-assembly (doto (VkPipelineInputAssemblyStateCreateInfo/calloc stack)
                           (.sType VK10/VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                           (.topology (get topology-map topology VK10/VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
                           (.primitiveRestartEnable false))

          ;; Viewport + scissor (static — no dynamic state)
          w      (int (:width extent))
          h      (int (:height extent))
          vp-buf (doto (VkViewport/calloc 1 ^MemoryStack stack)
                   (-> (.get 0)
                       (doto (.x 0.0) (.y 0.0)
                             (.width (float w)) (.height (float h))
                             (.minDepth 0.0) (.maxDepth 1.0))))
          sc-buf (doto (VkRect2D/calloc 1 ^MemoryStack stack)
                   (-> (.get 0)
                       (doto (.offset (doto (VkOffset2D/calloc stack) (.set 0 0)))
                             (.extent (doto (VkExtent2D/calloc stack) (.width w) (.height h))))))
          viewport-ci (doto (VkPipelineViewportStateCreateInfo/calloc stack)
                        (.sType VK10/VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                        (.viewportCount 1)
                        (.pViewports vp-buf)
                        (.scissorCount 1)
                        (.pScissors sc-buf))

          ;; Rasterizer
          raster (doto (VkPipelineRasterizationStateCreateInfo/calloc stack)
                   (.sType VK10/VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                   (.depthClampEnable false)
                   (.rasterizerDiscardEnable false)
                   (.polygonMode (get polygon-mode-map polygon-mode VK10/VK_POLYGON_MODE_FILL))
                   (.lineWidth 1.0)
                   (.cullMode (get cull-mode-map cull-mode VK10/VK_CULL_MODE_BACK_BIT))
                   (.frontFace (front-face->vk (or front-face :clockwise)))
                   (.depthBiasEnable false))

          ;; Multisampling — disabled
          multisample (doto (VkPipelineMultisampleStateCreateInfo/calloc stack)
                        (.sType VK10/VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                        (.sampleShadingEnable false)
                        (.rasterizationSamples VK10/VK_SAMPLE_COUNT_1_BIT))

          ;; Color blend attachment
          color-mask    (bit-or VK10/VK_COLOR_COMPONENT_R_BIT
                                VK10/VK_COLOR_COMPONENT_G_BIT
                                VK10/VK_COLOR_COMPONENT_B_BIT
                                VK10/VK_COLOR_COMPONENT_A_BIT)
          blend-att-buf (doto (VkPipelineColorBlendAttachmentState/calloc 1 ^MemoryStack stack)
                          (-> (.get 0)
                              (doto (.colorWriteMask color-mask)
                                    (.blendEnable (not= blend-mode :opaque)))))
          color-blend (doto (VkPipelineColorBlendStateCreateInfo/calloc stack)
                        (.sType VK10/VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                        (.logicOpEnable false)
                        (.logicOp VK10/VK_LOGIC_OP_COPY)
                        (.attachmentCount 1)
                        (.pAttachments blend-att-buf))

          ;; Pipeline layout (no push constants / descriptor sets yet)
          layout-ci (doto (VkPipelineLayoutCreateInfo/calloc stack)
                      (.sType VK10/VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                      (.pSetLayouts nil)
                      (.pPushConstantRanges nil))
          lp (.mallocLong stack 1)
          _  (let [r (VK10/vkCreatePipelineLayout device layout-ci nil lp)]
               (when (not= r VK10/VK_SUCCESS)
                 (VK10/vkDestroyShaderModule device vert-mod nil)
                 (VK10/vkDestroyShaderModule device frag-mod nil)
                 (MemoryStack/stackPop)
                 (throw (RuntimeException. (str "vkCreatePipelineLayout failed (VkResult=" r ")")))))
          layout (.get lp 0)

          ;; Graphics pipeline
          pipeline-buf (doto (VkGraphicsPipelineCreateInfo/callocStack 1 stack)
                         (-> (.get 0)
                             (doto (.sType VK10/VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                                   (.stageCount 2)
                                   (.pStages stages)
                                   (.pVertexInputState vertex-input-ci)
                                   (.pInputAssemblyState input-assembly)
                                   (.pViewportState viewport-ci)
                                   (.pRasterizationState raster)
                                   (.pMultisampleState multisample)
                                   (.pDepthStencilState nil)
                                   (.pColorBlendState color-blend)
                                   (.pDynamicState nil)
                                   (.layout layout)
                                   (.renderPass (long render-pass))
                                   (.subpass 0)
                                   (.basePipelineHandle VK10/VK_NULL_HANDLE)
                                   (.basePipelineIndex -1))))
          _  (.rewind lp)
          _  (let [r (VK10/vkCreateGraphicsPipelines device VK10/VK_NULL_HANDLE pipeline-buf nil lp)]
               (println "[pipeline] vkCreateGraphicsPipelines result:" r
                        "(SUCCESS=" VK10/VK_SUCCESS ")")
               (when (not= r VK10/VK_SUCCESS)
                 (VK10/vkDestroyPipelineLayout device layout nil)
                 (VK10/vkDestroyShaderModule device vert-mod nil)
                 (VK10/vkDestroyShaderModule device frag-mod nil)
                 (MemoryStack/stackPop)
                 (throw (RuntimeException. (str "vkCreateGraphicsPipelines failed (VkResult=" r ")")))))
          pipeline (.get lp 0)]

      ;; Shader modules are no longer needed after pipeline creation
      (VK10/vkDestroyShaderModule device vert-mod nil)
      (VK10/vkDestroyShaderModule device frag-mod nil)
      (MemoryStack/stackPop)
      (println "[pipeline] built — pipeline:" pipeline "layout:" layout)
      {:pipeline pipeline
       :layout   layout
       :device   device})))

;; ---------------------------------------------------------------------------
;; destroy!
;; ---------------------------------------------------------------------------
(defn destroy!
  "Destroy the pipeline and its layout.
   pipeline-map — the map returned by build!"
  [{:keys [^VkDevice device pipeline layout]}]
  (when (and device pipeline layout)
    (VK10/vkDestroyPipeline       device pipeline nil)
    (VK10/vkDestroyPipelineLayout device layout   nil)))
