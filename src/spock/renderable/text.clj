(ns spock.renderable.text
  "Built-in :text renderable — draws bitmap font text using AngelCode .fnt.
   One quad per glyph, UV-mapped into the font atlas texture.
   Push constants carry per-glyph NDC position, UV coords, and color.

   Configuration:
     {:type  :text
      :font  \"assets/fonts/mono.fnt\"
      :text  \"Hello\"
      :size  0.05
      :color [r g b a]}

   The renderable maintains an instances atom — vector of maps:
     {:text string :x float :y float :color [r g b a] :size float}
   Scripts update it via (text/instances renderable) each tick.

   Registers itself with spock.renderable.registry on namespace load."
  (:require [spock.renderable.core     :as renderable]
            [spock.renderable.registry :as registry]
            [spock.pipeline.core       :as pipeline]
            [spock.renderer.core       :as renderer]
            [spock.texture             :as texture]
            [spock.font                :as font]
            [spock.log                 :as log])
  (:import [org.lwjgl.vulkan
            VK10
            VkDescriptorPoolCreateInfo
            VkDescriptorPoolSize
            VkDescriptorSetAllocateInfo
            VkWriteDescriptorSet
            VkDescriptorImageInfo]
           [org.lwjgl.system MemoryStack]
           [java.nio ByteBuffer ByteOrder]))

;; ---------------------------------------------------------------------------
;; Push constant layout (std430) — 48 bytes total
;;   offset  0: vec2  pos     (8 bytes) — NDC bottom-left of this glyph
;;   offset  8: vec2  size    (8 bytes) — NDC width/height
;;   offset 16: vec2  uv_pos  (8 bytes) — UV top-left in atlas (0.0–1.0)
;;   offset 24: vec2  uv_size (8 bytes) — UV width/height in atlas (0.0–1.0)
;;   offset 32: vec4  color   (16 bytes)
;;   total: 48 bytes
;; ---------------------------------------------------------------------------

(def ^:private ^:const push-constant-bytes 48)

;; ---------------------------------------------------------------------------
;; Descriptor pool + set helpers (same pattern as sprite.clj)
;; ---------------------------------------------------------------------------

(defn- create-descriptor-pool! [^org.lwjgl.vulkan.VkDevice device]
  (let [stack (MemoryStack/stackPush)]
    (try
      (let [ps (.get (VkDescriptorPoolSize/calloc 1 ^MemoryStack stack) 0)]
        (.type ps VK10/VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
        (.descriptorCount ps 1)
        (let [sizes (VkDescriptorPoolSize/create (.address ps) 1)
              ci    (doto (VkDescriptorPoolCreateInfo/calloc stack)
                      (.sType VK10/VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                      (.pPoolSizes sizes)
                      (.maxSets 1))
              lp    (.mallocLong stack 1)
              r     (VK10/vkCreateDescriptorPool device ci nil lp)]
          (when (not= r VK10/VK_SUCCESS)
            (throw (RuntimeException. (str "vkCreateDescriptorPool failed: " r))))
          (.get lp 0)))
      (finally (MemoryStack/stackPop)))))

(defn- allocate-descriptor-set!
  [^org.lwjgl.vulkan.VkDevice device pool layout-handle]
  (let [stack (MemoryStack/stackPush)]
    (try
      (let [layouts (doto (.mallocLong stack 1)
                      (.put (long layout-handle))
                      (.flip))
            ai      (doto (VkDescriptorSetAllocateInfo/calloc stack)
                      (.sType VK10/VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                      (.descriptorPool (long pool))
                      (.pSetLayouts layouts))
            lp      (.mallocLong stack 1)
            r       (VK10/vkAllocateDescriptorSets device ai lp)]
        (when (not= r VK10/VK_SUCCESS)
          (throw (RuntimeException. (str "vkAllocateDescriptorSets failed: " r))))
        (.get lp 0))
      (finally (MemoryStack/stackPop)))))

(defn- write-descriptor-set!
  [^org.lwjgl.vulkan.VkDevice device desc-set image-view sampler]
  (let [stack (MemoryStack/stackPush)]
    (try
      (let [ii (.get (VkDescriptorImageInfo/calloc 1 ^MemoryStack stack) 0)]
        (.imageLayout ii VK10/VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
        (.imageView ii (long image-view))
        (.sampler ii (long sampler))
        (let [infos (VkDescriptorImageInfo/create (.address ii) 1)
              wr    (.get (VkWriteDescriptorSet/calloc 1 ^MemoryStack stack) 0)]
          (.sType wr VK10/VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
          (.dstSet wr (long desc-set))
          (.dstBinding wr 0)
          (.dstArrayElement wr 0)
          (.descriptorType wr VK10/VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
          (.descriptorCount wr 1)
          (.pImageInfo wr infos)
          (VK10/vkUpdateDescriptorSets device (VkWriteDescriptorSet/create (.address wr) 1) nil)))
      (finally (MemoryStack/stackPop)))))

;; ---------------------------------------------------------------------------
;; Renderable
;; ---------------------------------------------------------------------------

(defn- make-text-renderable [cfg _renderer]
  (let [font-path        (or (:font cfg) (throw (ex-info ":text renderable requires :font" {:cfg cfg})))
        def-text         (or (:text cfg) "")
        def-size         (double (or (:size cfg) 0.05))
        def-color        (vec (or (:color cfg) [1.0 1.0 1.0 1.0]))
        pipeline-atom    (atom nil)
        texture-atom     (atom nil)
        font-atom        (atom nil)
        desc-pool-atom   (atom nil)
        desc-set-atom    (atom nil)
        desc-layout-atom (atom nil)
        instances        (atom [])
        pc-buf           (doto (ByteBuffer/allocateDirect push-constant-bytes)
                           (.order ByteOrder/LITTLE_ENDIAN))]
    (with-meta
      (reify renderable/Renderable
        (draw [_this command-buffer _device _render-pass _extent]
          (let [pl   @pipeline-atom
                dset @desc-set-atom
                fnt  @font-atom]
            (when (and pl dset fnt)
              (let [{:keys [pipeline layout]} pl
                    {:keys [glyphs scale-w scale-h]} fnt]
                (VK10/vkCmdBindPipeline
                 ^org.lwjgl.vulkan.VkCommandBuffer command-buffer
                 VK10/VK_PIPELINE_BIND_POINT_GRAPHICS
                 (long pipeline))
                (let [stack (MemoryStack/stackPush)
                      dsp   (doto (.mallocLong stack 1)
                              (.put (long dset))
                              (.flip))]
                  (VK10/vkCmdBindDescriptorSets
                   ^org.lwjgl.vulkan.VkCommandBuffer command-buffer
                   VK10/VK_PIPELINE_BIND_POINT_GRAPHICS
                   (long layout) 0 dsp nil)
                  (MemoryStack/stackPop))
                (let [insts     @instances
                      draw-list (if (seq insts)
                                  insts
                                  [{:text  def-text
                                    :x     0.0
                                    :y     0.0
                                    :size  def-size
                                    :color def-color}])]
                  (doseq [{:keys [text x y size color]} draw-list]
                    (let [sz    (double (or size def-size))
                          c     (vec (or color def-color))
                          sw    (double scale-w)
                          sh    (double scale-h)
                          cur-x (atom (double (or x 0.0)))]
                      (doseq [ch (seq (str text))]
                        (let [cid   (int ch)
                              glyph (get glyphs cid (get glyphs (int \space)))]
                          (when glyph
                            (let [gw    (double (:width glyph))
                                  gh    (double (:height glyph))
                                  uv-x  (/ (double (:x glyph)) sw)
                                  uv-y  (/ (double (:y glyph)) sh)
                                  uv-w  (/ gw sw)
                                  uv-h  (/ gh sh)
                                  ndc-h sz
                                  ndc-w (* sz (/ gw (max 1.0 gh)))
                                  ndc-x @cur-x
                                  ndc-y (- (double (or y 0.0)) ndc-h)]
                              (.clear pc-buf)
                              (.putFloat pc-buf (float ndc-x))
                              (.putFloat pc-buf (float ndc-y))
                              (.putFloat pc-buf (float ndc-w))
                              (.putFloat pc-buf (float ndc-h))
                              (.putFloat pc-buf (float uv-x))
                              (.putFloat pc-buf (float uv-y))
                              (.putFloat pc-buf (float uv-w))
                              (.putFloat pc-buf (float uv-h))
                              (.putFloat pc-buf (float (nth c 0 1.0)))
                              (.putFloat pc-buf (float (nth c 1 1.0)))
                              (.putFloat pc-buf (float (nth c 2 1.0)))
                              (.putFloat pc-buf (float (nth c 3 1.0)))
                              (.flip pc-buf)
                              (pipeline/push-constants!
                               command-buffer
                               (long layout)
                               (bit-or VK10/VK_SHADER_STAGE_VERTEX_BIT
                                       VK10/VK_SHADER_STAGE_FRAGMENT_BIT)
                               pc-buf)
                              (VK10/vkCmdDraw
                               ^org.lwjgl.vulkan.VkCommandBuffer command-buffer
                               6 1 0 0)
                              (swap! cur-x + ndc-w)))))))))))

        (cleanup! [_this device]
          (when-let [dp @desc-pool-atom]
            (VK10/vkDestroyDescriptorPool device (long dp) nil)
            (reset! desc-pool-atom nil)
            (reset! desc-set-atom nil))
          (when-let [dl @desc-layout-atom]
            (pipeline/destroy-descriptor-set-layout! device dl)
            (reset! desc-layout-atom nil))
          (when-let [tx @texture-atom]
            (texture/destroy-texture! tx)
            (reset! texture-atom nil))
          (when-let [pl @pipeline-atom]
            (pipeline/destroy! pl)
            (reset! pipeline-atom nil))))

      {:pipeline-atom    pipeline-atom
       :texture-atom     texture-atom
       :font-atom        font-atom
       :desc-pool-atom   desc-pool-atom
       :desc-set-atom    desc-set-atom
       :desc-layout-atom desc-layout-atom
       :instances        instances
       :font-path        font-path
       :def-text         def-text
       :def-size         def-size
       :def-color        def-color})))

(defn build-pipeline!
  "Load font + atlas texture, create descriptor resources, build pipeline.
   Called by spock.edn after Vulkan init completes."
  [renderable renderer]
  (let [{:keys [pipeline-atom texture-atom font-atom
                desc-pool-atom desc-set-atom desc-layout-atom font-path]}
        (meta renderable)
        ^org.lwjgl.vulkan.VkDevice device (renderer/get-device renderer)
        rp (renderer/get-render-pass renderer)]

    ;; 1. Parse font
    (let [fnt (font/load-font font-path)]
      (reset! font-atom fnt)
      (log/log "text/build-pipeline! glyphs=" (count (:glyphs fnt)))

      ;; 2. Load atlas texture
      (let [tx (texture/load-texture! renderer (:image-path fnt))]
        (reset! texture-atom tx)

        ;; 3. Descriptor set layout
        (let [dl (pipeline/create-combined-image-sampler-layout! device)]
          (reset! desc-layout-atom dl)

          ;; 4. Descriptor pool + allocate + write
          (let [dp   (create-descriptor-pool! device)
                _    (reset! desc-pool-atom dp)
                dset (allocate-descriptor-set! device dp dl)]
            (write-descriptor-set! device dset (:image-view tx) (:sampler tx))
            (reset! desc-set-atom dset)

            ;; 5. Build pipeline with alpha blending
            (let [pl (-> (pipeline/builder device rp)
                         (pipeline/vert-path "src/shaders/text.vert")
                         (pipeline/frag-path "src/shaders/text.frag")
                         (pipeline/topology :triangle-list)
                         (pipeline/cull-mode :none)
                         (pipeline/descriptor-set-layout dl)
                         (pipeline/push-constant-size push-constant-bytes)
                         (pipeline/alpha-blend)
                         (pipeline/build!))]
              (reset! pipeline-atom pl))))))))

(defn instances
  "Return the instances atom for a text renderable.
   Reset it to a vector of {:text :x :y :size :color} maps each tick."
  [text-renderable]
  (:instances (meta text-renderable)))

;; Register on namespace load
(registry/register-renderable! :text make-text-renderable)
