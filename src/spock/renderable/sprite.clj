(ns spock.renderable.sprite
  "Built-in :sprite renderable — draws a textured quad using push constants
   for transform + tint and a descriptor set for the texture sampler.

   Configuration:
     {:type   :sprite
      :image  \"path/to/image.png\"
      :color  [r g b a]   ;; default tint (default [1 1 1 1])
      :width  0.3         ;; NDC width  (default 0.3)
      :height 0.3}        ;; NDC height (default 0.3)

   The renderable maintains an instance pool — a vector of maps:
     {:x float :y float :rotation float :color [r g b a] :width float :height float}
   Scripts update it via (sprite/instances renderable) each tick.

   Registers itself with spock.renderable.registry on namespace load."
  (:require [spock.renderable.core     :as renderable]
            [spock.renderable.registry :as registry]
            [spock.pipeline.core       :as pipeline]
            [spock.renderer.core       :as renderer]
            [spock.texture             :as texture]
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
;; Push constant layout (std430)
;;   offset  0: vec2  translation (8 bytes)
;;   offset  8: float rotation    (4 bytes)
;;   offset 12: float _pad        (4 bytes)
;;   offset 16: vec2  scale       (8 bytes)
;;   offset 24: vec2  _pad2       (8 bytes)
;;   offset 32: vec4  color       (16 bytes)
;;   total: 48 bytes
;; ---------------------------------------------------------------------------

(def ^:private ^:const push-constant-bytes 48)

;; ---------------------------------------------------------------------------
;; Descriptor pool + set helpers
;; ---------------------------------------------------------------------------

(defn- create-descriptor-pool!
  [^org.lwjgl.vulkan.VkDevice device]
  (let [stack (MemoryStack/stackPush)]
    (try
      (let [pool-size (.get (VkDescriptorPoolSize/calloc 1 ^MemoryStack stack) 0)]
        (.type pool-size VK10/VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
        (.descriptorCount pool-size 1)
        (let [sizes (VkDescriptorPoolSize/create (.address pool-size) 1)
              ci    (doto (VkDescriptorPoolCreateInfo/calloc stack)
                      (.sType VK10/VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                      (.pPoolSizes sizes)
                      (.maxSets 1))
              lp    (.mallocLong stack 1)
              r     (VK10/vkCreateDescriptorPool device ci nil lp)]
          (when (not= r VK10/VK_SUCCESS)
            (throw (RuntimeException. (str "vkCreateDescriptorPool failed: " r))))
          (.get lp 0)))
      (finally
        (MemoryStack/stackPop)))))

(defn- allocate-descriptor-set!
  [^org.lwjgl.vulkan.VkDevice device descriptor-pool layout-handle]
  (let [stack (MemoryStack/stackPush)]
    (try
      (let [layouts (doto (.mallocLong stack 1)
                      (.put (long layout-handle))
                      (.flip))
            ai      (doto (VkDescriptorSetAllocateInfo/calloc stack)
                      (.sType VK10/VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                      (.descriptorPool (long descriptor-pool))
                      (.pSetLayouts layouts))
            lp      (.mallocLong stack 1)
            r       (VK10/vkAllocateDescriptorSets device ai lp)]
        (when (not= r VK10/VK_SUCCESS)
          (throw (RuntimeException. (str "vkAllocateDescriptorSets failed: " r))))
        (.get lp 0))
      (finally
        (MemoryStack/stackPop)))))

(defn- write-descriptor-set!
  [^org.lwjgl.vulkan.VkDevice device descriptor-set image-view sampler]
  (let [stack (MemoryStack/stackPush)]
    (try
      (let [img-info (.get (VkDescriptorImageInfo/calloc 1 ^MemoryStack stack) 0)]
        (.imageLayout img-info VK10/VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
        (.imageView img-info (long image-view))
        (.sampler img-info (long sampler))
        (let [img-infos (VkDescriptorImageInfo/create (.address img-info) 1)
              write     (.get (VkWriteDescriptorSet/calloc 1 ^MemoryStack stack) 0)]
          (.sType write VK10/VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
          (.dstSet write (long descriptor-set))
          (.dstBinding write 0)
          (.dstArrayElement write 0)
          (.descriptorType write VK10/VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
          (.descriptorCount write 1)
          (.pImageInfo write img-infos)
          (let [writes (VkWriteDescriptorSet/create (.address write) 1)]
            (VK10/vkUpdateDescriptorSets device writes nil))))
      (finally
        (MemoryStack/stackPop)))))

;; ---------------------------------------------------------------------------
;; Renderable
;; ---------------------------------------------------------------------------

(defn- make-sprite-renderable [cfg _renderer]
  (let [image-path  (or (:image cfg) (throw (ex-info "sprite :image required" {:cfg cfg})))
        def-color   (vec (or (:color cfg) [1.0 1.0 1.0 1.0]))
        def-width   (double (or (:width cfg) 0.3))
        def-height  (double (or (:height cfg) 0.3))
        pipeline-atom     (atom nil)
        texture-atom      (atom nil)
        desc-pool-atom    (atom nil)
        desc-set-atom     (atom nil)
        desc-layout-atom  (atom nil)
        instances         (atom [])
        pc-buf (doto (ByteBuffer/allocateDirect push-constant-bytes)
                 (.order ByteOrder/LITTLE_ENDIAN))]
    (with-meta
      (reify renderable/Renderable
        (draw [_this command-buffer _device _render-pass _extent]
          (let [pl   @pipeline-atom
                dset @desc-set-atom]
            (when (and pl dset)
              (let [{:keys [pipeline layout]} pl]
                (VK10/vkCmdBindPipeline
                 ^org.lwjgl.vulkan.VkCommandBuffer command-buffer
                 VK10/VK_PIPELINE_BIND_POINT_GRAPHICS
                 (long pipeline))
                ;; Bind descriptor set (texture sampler)
                (let [stack (MemoryStack/stackPush)
                      dsp   (doto (.mallocLong stack 1)
                              (.put (long dset))
                              (.flip))]
                  (VK10/vkCmdBindDescriptorSets
                   ^org.lwjgl.vulkan.VkCommandBuffer command-buffer
                   VK10/VK_PIPELINE_BIND_POINT_GRAPHICS
                   (long layout)
                   0
                   dsp
                   nil)
                  (MemoryStack/stackPop))
                (let [insts     @instances
                      draw-list (if (seq insts)
                                  insts
                                  [{:x 0.0 :y 0.0 :rotation 0.0
                                    :color def-color
                                    :width def-width :height def-height}])]
                  (doseq [{:keys [x y rotation color width height]} draw-list]
                    (let [c (vec (or color def-color))
                          w (double (or width def-width))
                          h (double (or height def-height))]
                      (.clear pc-buf)
                      (.putFloat pc-buf (float (or x 0.0)))    ; translation.x
                      (.putFloat pc-buf (float (or y 0.0)))    ; translation.y
                      (.putFloat pc-buf (float (or rotation 0.0))) ; rotation
                      (.putFloat pc-buf 0.0)                   ; _pad
                      (.putFloat pc-buf (float w))             ; scale.x
                      (.putFloat pc-buf (float h))             ; scale.y
                      (.putFloat pc-buf 0.0)                   ; _pad2.x
                      (.putFloat pc-buf 0.0)                   ; _pad2.y
                      (.putFloat pc-buf (float (nth c 0 1.0))) ; color.r
                      (.putFloat pc-buf (float (nth c 1 1.0))) ; color.g
                      (.putFloat pc-buf (float (nth c 2 1.0))) ; color.b
                      (.putFloat pc-buf (float (nth c 3 1.0))) ; color.a
                      (.flip pc-buf)
                      (pipeline/push-constants!
                       command-buffer
                       (long layout)
                       (bit-or VK10/VK_SHADER_STAGE_VERTEX_BIT
                               VK10/VK_SHADER_STAGE_FRAGMENT_BIT)
                       pc-buf))
                    (VK10/vkCmdDraw
                     ^org.lwjgl.vulkan.VkCommandBuffer command-buffer
                     6 1 0 0)))))))

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
       :desc-pool-atom   desc-pool-atom
       :desc-set-atom    desc-set-atom
       :desc-layout-atom desc-layout-atom
       :instances        instances
       :image-path       image-path
       :def-color        def-color
       :def-width        def-width
       :def-height       def-height})))

(defn build-pipeline!
  "Build the Vulkan pipeline, load texture, and set up descriptor resources.
   Called by spock.edn after Vulkan init completes."
  [renderable renderer]
  (let [{:keys [pipeline-atom texture-atom desc-pool-atom
                desc-set-atom desc-layout-atom image-path]}
        (meta renderable)
        ^org.lwjgl.vulkan.VkDevice device (renderer/get-device renderer)
        rp (renderer/get-render-pass renderer)]

    ;; 1. Create descriptor set layout
    (let [dl (pipeline/create-combined-image-sampler-layout! device)]
      (reset! desc-layout-atom dl)

      ;; 2. Load texture
      (let [tx (texture/load-texture! renderer image-path)]
        (reset! texture-atom tx)

        ;; 3. Create descriptor pool
        (let [dp (create-descriptor-pool! device)]
          (reset! desc-pool-atom dp)

          ;; 4. Allocate + write descriptor set
          (let [dset (allocate-descriptor-set! device dp dl)]
            (write-descriptor-set! device dset (:image-view tx) (:sampler tx))
            (reset! desc-set-atom dset)

            ;; 5. Build pipeline
            (let [pl (-> (pipeline/builder device rp)
                         (pipeline/vert-path "src/shaders/sprite.vert")
                         (pipeline/frag-path "src/shaders/sprite.frag")
                         (pipeline/topology :triangle-list)
                         (pipeline/cull-mode :none)
                         (pipeline/descriptor-set-layout dl)
                         (pipeline/push-constant-size push-constant-bytes)
                         (pipeline/build!))]
              (reset! pipeline-atom pl))))))))

(defn instances
  "Return the instances atom for a sprite renderable.
   Reset it to a vector of {:x :y :rotation :color :width :height} maps each tick."
  [sprite-renderable]
  (:instances (meta sprite-renderable)))

;; Register on namespace load
(registry/register-renderable! :sprite make-sprite-renderable)
