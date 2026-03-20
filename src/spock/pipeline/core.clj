(ns spock.pipeline.core
  "Data-driven Vulkan graphics pipeline builder."
  (:require [spock.shader.core :as shader]
            [spock.log :as log])
  (:import [org.lwjgl.system MemoryStack]
           [org.lwjgl.vulkan
            VK10
            VkDevice
            VkCommandBuffer
            VkGraphicsPipelineCreateInfo
            VkPipelineShaderStageCreateInfo
            VkPipelineVertexInputStateCreateInfo
            VkVertexInputBindingDescription
            VkVertexInputAttributeDescription
            VkPipelineInputAssemblyStateCreateInfo
            VkPipelineViewportStateCreateInfo
            VkPipelineDynamicStateCreateInfo
            VkPipelineRasterizationStateCreateInfo
            VkPipelineMultisampleStateCreateInfo
            VkPipelineColorBlendStateCreateInfo
            VkPipelineColorBlendAttachmentState
            VkPipelineLayoutCreateInfo
            VkPushConstantRange
            VkShaderModuleCreateInfo
            VkDescriptorSetLayoutBinding
            VkDescriptorSetLayoutCreateInfo
            VkDescriptorSetLayout
            VkDescriptorPoolSize
            VkDescriptorPoolCreateInfo
            VkDescriptorPool
            VkDescriptorSetAllocateInfo
            VkDescriptorSet
            VkDescriptorImageInfo
            VkWriteDescriptorSet]))

;; ---------------------------------------------------------------------------
;; Builder constructor
;; ---------------------------------------------------------------------------
(defn builder [^VkDevice device render-pass]
  {:device             device
   :render-pass        render-pass
   :vert-spv           nil
   :frag-spv           nil
   :topology           VK10/VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
   :cull-mode          VK10/VK_CULL_MODE_NONE
   :front-face         VK10/VK_FRONT_FACE_COUNTER_CLOCKWISE
   :polygon-mode       VK10/VK_POLYGON_MODE_FILL
   :push-constant-size nil  ; bytes, nil = no push constants
   ;; vertex input: nil = no vertex buffers (hardcoded in shader)
   ;; non-nil: {:stride N :attributes [{:location L :format F :offset O} ...]}
   :vertex-input       nil})

;; ---------------------------------------------------------------------------
;; Option setters
;; ---------------------------------------------------------------------------
(defn vert-spv    [cfg buf]  (assoc cfg :vert-spv buf))
(defn frag-spv    [cfg buf]  (assoc cfg :frag-spv buf))
(defn topology    [cfg t]    (assoc cfg :topology    (case t :triangle-list VK10/VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST :triangle-fan VK10/VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN :line-strip VK10/VK_PRIMITIVE_TOPOLOGY_LINE_STRIP :point-list VK10/VK_PRIMITIVE_TOPOLOGY_POINT_LIST t)))
(defn cull-mode   [cfg m]    (assoc cfg :cull-mode   (case m :none VK10/VK_CULL_MODE_NONE :back VK10/VK_CULL_MODE_BACK_BIT :front VK10/VK_CULL_MODE_FRONT_BIT m)))
(defn front-face  [cfg f]    (assoc cfg :front-face  (case f :clockwise VK10/VK_FRONT_FACE_CLOCKWISE :counter-clockwise VK10/VK_FRONT_FACE_COUNTER_CLOCKWISE f)))
(defn polygon-mode [cfg m]   (assoc cfg :polygon-mode (case m :fill VK10/VK_POLYGON_MODE_FILL :line VK10/VK_POLYGON_MODE_LINE m)))

(defn push-constant-size
  "Configure a VK_SHADER_STAGE_VERTEX_BIT push constant range of size-bytes."
  [cfg size-bytes]
  (assoc cfg :push-constant-size (int size-bytes)))

(defn vertex-input
  "Configure vertex input for a packed interleaved vertex buffer.
   stride — stride in bytes between vertices.
   attributes — vector of {:location int :format VkFormat-int :offset int}."
  [cfg stride attributes]
  (assoc cfg :vertex-input {:stride stride :attributes attributes}))

(defn vert-path [cfg path]
  (when-not (shader/compile-glsl path)
    (throw (RuntimeException. (str "Failed to compile: " path))))
  (vert-spv cfg (shader/load-spirv (str path ".spv"))))

(defn frag-path [cfg path]
  (when-not (shader/compile-glsl path)
    (throw (RuntimeException. (str "Failed to compile: " path))))
  (frag-spv cfg (shader/load-spirv (str path ".spv"))))

(defn descriptor-layout
  "Add descriptor set bindings to the pipeline config.
   bindings is a vector of {:binding int :type :combined-image-sampler :stage :fragment}."
  [cfg bindings]
  (assoc cfg :descriptor-bindings bindings))

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
;; Descriptor set layout helper
;; ---------------------------------------------------------------------------
(defn- binding-type->vk [t]
  (case t
    :combined-image-sampler VK10/VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
    :uniform-buffer         VK10/VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
    :storage-buffer         VK10/VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
    (int t)))

(defn- binding-stage->vk [s]
  (case s
    :vertex   VK10/VK_SHADER_STAGE_VERTEX_BIT
    :fragment VK10/VK_SHADER_STAGE_FRAGMENT_BIT
    :all      VK10/VK_SHADER_STAGE_ALL_GRAPHICS
    (int s)))

(defn- create-descriptor-set-layout!
  "Create a VkDescriptorSetLayout from a vector of binding descriptors.
   Returns the layout handle (long)."
  [^VkDevice device bindings]
  (let [stack (MemoryStack/stackPush)]
    (try
      (let [n   (count bindings)
            buf (VkDescriptorSetLayoutBinding/calloc n stack)]
        (dorun
         (map-indexed
          (fn [i {:keys [binding type stage]}]
            (doto ^VkDescriptorSetLayoutBinding (.get buf (int i))
              (.binding         (int binding))
              (.descriptorType  (int (binding-type->vk type)))
              (.descriptorCount 1)
              (.stageFlags      (int (binding-stage->vk stage)))
              (.pImmutableSamplers nil)))
          bindings))
        (let [ci (doto (VkDescriptorSetLayoutCreateInfo/calloc stack)
                   (.sType VK10/VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                   (.pBindings buf))
              lp (.mallocLong stack 1)
              r  (VK10/vkCreateDescriptorSetLayout device ci nil lp)]
          (when (not= r VK10/VK_SUCCESS)
            (throw (RuntimeException. (str "vkCreateDescriptorSetLayout failed: " r))))
          (.get lp 0)))
      (finally
        (MemoryStack/stackPop)))))

;; ---------------------------------------------------------------------------
;; build!
;; ---------------------------------------------------------------------------
(defn build! [{:keys [^VkDevice device render-pass
                      vert-spv frag-spv
                      topology cull-mode front-face polygon-mode
                      push-constant-size vertex-input descriptor-bindings]}]
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
          (when vertex-input
            ;; Binding description: binding 0, per-vertex
            (let [bd (VkVertexInputBindingDescription/calloc 1 stack)
                  bd0 (.get bd 0)]
              (.binding bd0 0)
              (.stride bd0 (int (:stride vertex-input)))
              (.inputRate bd0 VK10/VK_VERTEX_INPUT_RATE_VERTEX)
              (.pVertexBindingDescriptions vi bd))
            ;; Attribute descriptions
            (let [attrs (:attributes vertex-input)
                  ad (VkVertexInputAttributeDescription/calloc (count attrs) stack)]
              (dorun (map-indexed
                      (fn [i {:keys [location format offset]}]
                        (let [a (.get ad (int i))]
                          (.location a (int location))
                          (.binding  a 0)
                          (.format   a (int format))
                          (.offset   a (int offset))))
                      attrs))
              (.pVertexAttributeDescriptions vi ad)))

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
                                 (.put VK10/VK_DYNAMIC_STATE_VIEWPORT)
                                 (.put VK10/VK_DYNAMIC_STATE_SCISSOR)
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
                          (let [layout-ci (VkPipelineLayoutCreateInfo/calloc stack)
                                ;; Optionally create descriptor set layout
                                dsl-handle (when (seq descriptor-bindings)
                                             (create-descriptor-set-layout! device descriptor-bindings))]
                            (.sType layout-ci VK10/VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                            ;; Optional push constant range
                            (when push-constant-size
                              (let [pcr (VkPushConstantRange/calloc 1 ^MemoryStack stack)
                                    r0  (.get pcr 0)]
                                (.stageFlags r0 (bit-or VK10/VK_SHADER_STAGE_VERTEX_BIT
                                                        VK10/VK_SHADER_STAGE_FRAGMENT_BIT))
                                (.offset     r0 0)
                                (.size       r0 (int push-constant-size))
                                (.pPushConstantRanges layout-ci pcr)))
                            ;; Optional descriptor set layout
                            (when dsl-handle
                              (let [dsl-buf (doto (.mallocLong stack 1)
                                              (.put (long dsl-handle))
                                              (.flip))]
                                (.pSetLayouts layout-ci dsl-buf)))
                            (let [lp (.mallocLong stack 1)
                                  r  (VK10/vkCreatePipelineLayout device layout-ci nil lp)]
                              (when (not= r VK10/VK_SUCCESS)
                                (when dsl-handle
                                  (VK10/vkDestroyDescriptorSetLayout device (long dsl-handle) nil))
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
                                    {:pipeline               pipeline
                                     :layout                 layout
                                     :device                 device
                                     :descriptor-set-layout  dsl-handle})))))))))))))))
      (finally
        (VK10/vkDestroyShaderModule device vert-mod nil)
        (VK10/vkDestroyShaderModule device frag-mod nil)
        (MemoryStack/stackPop)))))

;; ---------------------------------------------------------------------------
;; push-constants!
;; ---------------------------------------------------------------------------
(defn push-constants!
  "Record a vkCmdPushConstants call into command-buffer.
   command-buffer — VkCommandBuffer
   layout         — pipeline layout handle (long)
   stage          — shader stage flags int (e.g. VK10/VK_SHADER_STAGE_VERTEX_BIT)
   data           — java.nio.ByteBuffer of push constant data (position=0, limit=size)"
  ([^VkCommandBuffer command-buffer layout stage ^java.nio.ByteBuffer data]
   (push-constants! command-buffer layout stage 0 data))
  ([^VkCommandBuffer command-buffer layout stage offset ^java.nio.ByteBuffer data]
   (VK10/vkCmdPushConstants command-buffer (long layout) (int stage) (int offset) data)))

;; ---------------------------------------------------------------------------
;; destroy!
;; ---------------------------------------------------------------------------
(defn destroy! [{:keys [^VkDevice device pipeline layout descriptor-set-layout]}]
  (when (and device pipeline layout)
    (VK10/vkDestroyPipeline       device (long pipeline) nil)
    (VK10/vkDestroyPipelineLayout device (long layout)   nil)
    (when descriptor-set-layout
      (VK10/vkDestroyDescriptorSetLayout device (long descriptor-set-layout) nil))))

;; ---------------------------------------------------------------------------
;; Descriptor pool + set allocation
;; ---------------------------------------------------------------------------

(defn create-descriptor-pool!
  "Create a VkDescriptorPool capable of allocating max-sets combined-image-sampler descriptors.
   Returns the pool handle (long)."
  [^VkDevice device max-sets]
  (let [stack (MemoryStack/stackPush)]
    (try
      (let [ps  (doto (VkDescriptorPoolSize/calloc 1 stack)
                  (-> (.get 0)
                      (doto
                       (.type VK10/VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        (.descriptorCount (int max-sets)))))
            ci  (doto (VkDescriptorPoolCreateInfo/calloc stack)
                  (.sType VK10/VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                  (.pPoolSizes ps)
                  (.maxSets (int max-sets)))
            lp  (.mallocLong stack 1)
            r   (VK10/vkCreateDescriptorPool device ci nil lp)]
        (when (not= r VK10/VK_SUCCESS)
          (throw (RuntimeException. (str "vkCreateDescriptorPool failed: " r))))
        (.get lp 0))
      (finally
        (MemoryStack/stackPop)))))

(defn allocate-descriptor-set!
  "Allocate a VkDescriptorSet from pool and write texture at binding 0.
   pipeline-map  — result of build! (must have :descriptor-set-layout and :device)
   desc-pool     — VkDescriptorPool handle (long)
   texture-map   — result of spock.texture.core/load-texture!
                   (must have :image-view and :sampler)
   Returns the descriptor set handle (long)."
  [{:keys [^VkDevice device descriptor-set-layout]} desc-pool texture-map]
  (let [stack (MemoryStack/stackPush)]
    (try
      (let [dsl-buf (doto (.mallocLong stack 1)
                      (.put (long descriptor-set-layout))
                      (.flip))
            ai  (doto (VkDescriptorSetAllocateInfo/calloc stack)
                  (.sType VK10/VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                  (.descriptorPool (long desc-pool))
                  (.pSetLayouts dsl-buf))
            lp  (.mallocLong stack 1)
            r   (VK10/vkAllocateDescriptorSets device ai lp)]
        (when (not= r VK10/VK_SUCCESS)
          (throw (RuntimeException. (str "vkAllocateDescriptorSets failed: " r))))
        (let [ds      (.get lp 0)
              img-info (doto (VkDescriptorImageInfo/calloc 1 stack)
                         (-> (.get 0)
                             (doto
                              (.imageLayout VK10/VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                               (.imageView   (long (:image-view texture-map)))
                               (.sampler     (long (:sampler    texture-map))))))
              write   (doto (VkWriteDescriptorSet/calloc 1 stack)
                        (-> (.get 0)
                            (doto
                             (.sType VK10/VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                              (.dstSet ds)
                              (.dstBinding 0)
                              (.dstArrayElement 0)
                              (.descriptorType VK10/VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                              (.descriptorCount 1)
                              (.pImageInfo img-info))))]
          (VK10/vkUpdateDescriptorSets device write nil)
          ds))
      (finally
        (MemoryStack/stackPop)))))
