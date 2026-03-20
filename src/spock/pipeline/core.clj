(ns spock.pipeline.core
  "Data-driven Vulkan graphics pipeline builder."
  (:require [spock.shader.core :as shader]
            [spock.log :as log])
  (:import [org.lwjgl.system MemoryStack]
           [org.lwjgl.vulkan
            VK10
            VkDevice
            VkGraphicsPipelineCreateInfo
            VkPipelineShaderStageCreateInfo
            VkPipelineVertexInputStateCreateInfo
            VkPipelineInputAssemblyStateCreateInfo
            VkPipelineViewportStateCreateInfo
            VkPipelineDynamicStateCreateInfo
            VkPipelineRasterizationStateCreateInfo
            VkPipelineMultisampleStateCreateInfo
            VkPipelineColorBlendStateCreateInfo
            VkPipelineColorBlendAttachmentState
            VkPipelineLayoutCreateInfo
            VkShaderModuleCreateInfo]))

;; ---------------------------------------------------------------------------
;; Builder constructor
;; ---------------------------------------------------------------------------
(defn builder [^VkDevice device render-pass]
  {:device       device
   :render-pass  render-pass
   :vert-spv     nil
   :frag-spv     nil
   :topology     VK10/VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
   :cull-mode    VK10/VK_CULL_MODE_NONE
   :front-face   VK10/VK_FRONT_FACE_COUNTER_CLOCKWISE
   :polygon-mode VK10/VK_POLYGON_MODE_FILL})

;; ---------------------------------------------------------------------------
;; Option setters
;; ---------------------------------------------------------------------------
(defn vert-spv    [cfg buf]  (assoc cfg :vert-spv buf))
(defn frag-spv    [cfg buf]  (assoc cfg :frag-spv buf))
(defn topology    [cfg t]    (assoc cfg :topology    (case t :triangle-list VK10/VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST t)))
(defn cull-mode   [cfg m]    (assoc cfg :cull-mode   (case m :none VK10/VK_CULL_MODE_NONE :back VK10/VK_CULL_MODE_BACK_BIT :front VK10/VK_CULL_MODE_FRONT_BIT m)))
(defn front-face  [cfg f]    (assoc cfg :front-face  (case f :clockwise VK10/VK_FRONT_FACE_CLOCKWISE :counter-clockwise VK10/VK_FRONT_FACE_COUNTER_CLOCKWISE f)))
(defn polygon-mode [cfg m]   (assoc cfg :polygon-mode (case m :fill VK10/VK_POLYGON_MODE_FILL :line VK10/VK_POLYGON_MODE_LINE m)))

(defn vert-path [cfg path]
  (when-not (shader/compile-glsl path)
    (throw (RuntimeException. (str "Failed to compile: " path))))
  (vert-spv cfg (shader/load-spirv (str path ".spv"))))

(defn frag-path [cfg path]
  (when-not (shader/compile-glsl path)
    (throw (RuntimeException. (str "Failed to compile: " path))))
  (frag-spv cfg (shader/load-spirv (str path ".spv"))))

;; ---------------------------------------------------------------------------
;; Shader module helper
;; ---------------------------------------------------------------------------
(defn- create-shader-module ^long [^VkDevice device ^java.nio.ByteBuffer spv]
  (let [stack (MemoryStack/stackGet)
        ci    (VkShaderModuleCreateInfo/calloc stack)]
    (.sType ci VK10/VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
    (.pCode ci spv)
    (let [lp (-> stack (.mallocLong 1))
          r  (VK10/vkCreateShaderModule device ci nil lp)]
      (when (not= r VK10/VK_SUCCESS)
        (throw (RuntimeException. (str "vkCreateShaderModule failed: " r))))
      (.get lp 0))))

;; ---------------------------------------------------------------------------
;; build!
;; ---------------------------------------------------------------------------
(defn build! [{:keys [^VkDevice device render-pass
                      vert-spv frag-spv
                      topology cull-mode front-face polygon-mode]}]
  (when-not vert-spv (throw (RuntimeException. "No vertex shader")))
  (when-not frag-spv (throw (RuntimeException. "No fragment shader")))

  (let [stack (MemoryStack/stackPush)
        vert-mod (create-shader-module device vert-spv)
        frag-mod (create-shader-module device frag-spv)]
    (try
      ;; ---- shader stages ----
      (let [stages (VkPipelineShaderStageCreateInfo/calloc 2 ^MemoryStack stack)]

        (let [s (.get stages 0)]
          (.sType s VK10/VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
          (.stage s VK10/VK_SHADER_STAGE_VERTEX_BIT)
          (.module s vert-mod)
          (.pName s (.UTF8 stack "main" true)))

        (let [s (.get stages 1)]
          (.sType s VK10/VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
          (.stage s VK10/VK_SHADER_STAGE_FRAGMENT_BIT)
          (.module s frag-mod)
          (.pName s (.UTF8 stack "main" true)))

        ;; ---- vertex input ----
        (let [vi (VkPipelineVertexInputStateCreateInfo/calloc stack)]
          (.sType vi VK10/VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
          ;; no vertex buffers — hardcoded in shader

          ;; ---- input assembly ----
          (let [ia (VkPipelineInputAssemblyStateCreateInfo/calloc stack)]
            (.sType ia VK10/VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
            (.topology ia (int topology))
            (.primitiveRestartEnable ia false)

            ;; ---- viewport state (dynamic) ----
            (let [vps (VkPipelineViewportStateCreateInfo/calloc stack)]
              (.sType vps VK10/VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
              (.viewportCount vps 1)
              (.scissorCount vps 1)

              ;; ---- dynamic state ----
              (let [dyn-states (doto (.mallocInt stack 2)
                                 (.put 0 VK10/VK_DYNAMIC_STATE_VIEWPORT)
                                 (.put 1 VK10/VK_DYNAMIC_STATE_SCISSOR)
                                 (.flip))
                    dyn (VkPipelineDynamicStateCreateInfo/calloc stack)]
                (.sType dyn VK10/VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                (.pDynamicStates dyn dyn-states)

                ;; ---- rasterizer ----
                (let [rast (VkPipelineRasterizationStateCreateInfo/calloc stack)]
                  (.sType rast VK10/VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                  (.depthClampEnable rast false)
                  (.rasterizerDiscardEnable rast false)
                  (.polygonMode rast (int polygon-mode))
                  (.lineWidth rast 1.0)
                  (.cullMode rast (int cull-mode))
                  (.frontFace rast (int front-face))
                  (.depthBiasEnable rast false)

                  ;; ---- multisampling ----
                  (let [ms (VkPipelineMultisampleStateCreateInfo/calloc stack)]
                    (.sType ms VK10/VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    (.sampleShadingEnable ms false)
                    (.rasterizationSamples ms VK10/VK_SAMPLE_COUNT_1_BIT)

                    ;; ---- color blend attachment ----
                    (let [att (VkPipelineColorBlendAttachmentState/calloc stack)
                          color-mask (bit-or VK10/VK_COLOR_COMPONENT_R_BIT
                                             VK10/VK_COLOR_COMPONENT_G_BIT
                                             VK10/VK_COLOR_COMPONENT_B_BIT
                                             VK10/VK_COLOR_COMPONENT_A_BIT)]
                      (.colorWriteMask att color-mask)
                      (.blendEnable att false)

                      ;; wrap single att in a 1-element buffer via address
                      (let [att-buf (VkPipelineColorBlendAttachmentState/create (.address att) 1)]

                        ;; ---- color blend state ----
                        (let [cb (VkPipelineColorBlendStateCreateInfo/calloc stack)]
                          (.sType cb VK10/VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                          (.logicOpEnable cb false)
                          (.logicOp cb VK10/VK_LOGIC_OP_COPY)
                          (.attachmentCount cb 1)
                          (.pAttachments cb att-buf)

                          ;; ---- pipeline layout ----
                          (let [layout-ci (VkPipelineLayoutCreateInfo/calloc stack)]
                            (.sType layout-ci VK10/VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                            (let [lp (.mallocLong stack 1)
                                  r  (VK10/vkCreatePipelineLayout device layout-ci nil lp)]
                              (when (not= r VK10/VK_SUCCESS)
                                (throw (RuntimeException. (str "vkCreatePipelineLayout failed: " r))))
                              (let [layout (.get lp 0)

                                    ;; ---- graphics pipeline ----
                                    pci (VkGraphicsPipelineCreateInfo/calloc stack)]
                                (.sType pci VK10/VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                                (.stageCount pci 2)
                                (.pStages pci stages)
                                (.pVertexInputState pci vi)
                                (.pInputAssemblyState pci ia)
                                (.pViewportState pci vps)
                                (.pRasterizationState pci rast)
                                (.pMultisampleState pci ms)
                                (.pDepthStencilState pci nil)
                                (.pColorBlendState pci cb)
                                (.pDynamicState pci dyn)
                                (.layout pci layout)
                                (.renderPass pci (long render-pass))
                                (.subpass pci 0)
                                (.basePipelineHandle pci VK10/VK_NULL_HANDLE)
                                (.basePipelineIndex pci -1)

                                (let [pci-buf (VkGraphicsPipelineCreateInfo/create (.address pci) 1)
                                      _ (.rewind lp)
                                      r (VK10/vkCreateGraphicsPipelines device VK10/VK_NULL_HANDLE pci-buf nil lp)]
                                  (when (not= r VK10/VK_SUCCESS)
                                    (VK10/vkDestroyPipelineLayout device layout nil)
                                    (throw (RuntimeException. (str "vkCreateGraphicsPipelines failed: " r))))
                                  (let [pipeline (.get lp 0)]
                                    (log/log "pipeline/build! OK pipeline=" pipeline "layout=" layout)
                                    {:pipeline pipeline
                                     :layout   layout
                                     :device   device})))))))))))))))
      (finally
        (VK10/vkDestroyShaderModule device vert-mod nil)
        (VK10/vkDestroyShaderModule device frag-mod nil)
        (MemoryStack/stackPop)))))

;; ---------------------------------------------------------------------------
;; destroy!
;; ---------------------------------------------------------------------------
(defn destroy! [{:keys [^VkDevice device pipeline layout]}]
  (when (and device pipeline layout)
    (VK10/vkDestroyPipeline       device (long pipeline) nil)
    (VK10/vkDestroyPipelineLayout device (long layout)   nil)))
