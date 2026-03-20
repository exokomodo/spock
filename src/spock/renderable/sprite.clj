(ns spock.renderable.sprite
  "Built-in :sprite renderable — draws a textured quad with per-instance
   push constants for translation, rotation, scale, and tint color.

   Configuration:
     {:type   :sprite
      :image  \"path/to/image.png\"
      :width  0.2    ;; NDC width  (default 0.2)
      :height 0.2    ;; NDC height (default 0.2)
      :color  [r g b a]}  ;; default tint (default white)

   Scripts update instances via (sprite/instances renderable) each tick.
   Each instance map: {:x :y :rotation :scale-x :scale-y :color}

   Registers itself with spock.renderable.registry on namespace load."
  (:require [spock.renderable.core     :as renderable]
            [spock.renderable.registry :as registry]
            [spock.pipeline.core       :as pipeline]
            [spock.texture.core        :as texture]
            [spock.renderer.core       :as renderer]
            [spock.log                 :as log])
  (:import [org.lwjgl.vulkan
            VK10
            VkBufferCreateInfo
            VkMemoryRequirements
            VkMemoryAllocateInfo
            VkPhysicalDeviceMemoryProperties]
           [org.lwjgl.system MemoryStack MemoryUtil]
           [java.nio ByteBuffer ByteOrder]))

;; ---------------------------------------------------------------------------
;; Push constant layout (std430)
;;   offset  0: vec2  translation  (8 bytes)
;;   offset  8: float rotation     (4 bytes)
;;   offset 12: float _pad0        (4 bytes)
;;   offset 16: vec2  scale        (8 bytes)
;;   offset 24: vec2  _pad1        (8 bytes)
;;   offset 32: vec4  color        (16 bytes)
;;   total: 48 bytes
;; ---------------------------------------------------------------------------

(def ^:private ^:const push-constant-bytes 48)

;; VK_FORMAT_R32G32_SFLOAT = 103
(def ^:private ^:const vk-format-r32g32-sfloat 103)

;; ---------------------------------------------------------------------------
;; Geometry helpers
;; ---------------------------------------------------------------------------

(defn- make-vertices
  "4 vertices × (pos.x pos.y uv.x uv.y) = 16 floats, centered at origin."
  [w h]
  (let [hw (/ (float w) 2.0)
        hh (/ (float h) 2.0)]
    (float-array
     [-hw -hh  0.0 0.0
       hw -hh  1.0 0.0
       hw  hh  1.0 1.0
      -hw  hh  0.0 1.0])))

(defn- make-indices []
  (short-array [0 1 2  2 3 0]))

;; ---------------------------------------------------------------------------
;; GPU buffer helpers
;; ---------------------------------------------------------------------------

(defn- find-memory-type
  [^org.lwjgl.vulkan.VkPhysicalDevice physical-device type-filter required-props]
  (let [stack (MemoryStack/stackPush)]
    (try
      (let [props (VkPhysicalDeviceMemoryProperties/malloc stack)]
        (VK10/vkGetPhysicalDeviceMemoryProperties physical-device props)
        (loop [i 0]
          (if (>= i (.memoryTypeCount props))
            (throw (RuntimeException. "Failed to find suitable memory type"))
            (let [mem-type (.get (.memoryTypes props) i)
                  flags    (.propertyFlags mem-type)]
              (if (and (not= 0 (bit-and (int type-filter) (bit-shift-left 1 i)))
                       (= (int required-props) (bit-and (int flags) (int required-props))))
                i
                (recur (inc i)))))))
      (finally
        (MemoryStack/stackPop)))))

(defn- create-buffer!
  "Create and populate a host-visible buffer (vertex or index).
   elem-bytes: bytes per element. put-fn: (fn [java-buffer data])."
  [^org.lwjgl.vulkan.VkDevice device
   ^org.lwjgl.vulkan.VkPhysicalDevice physical-device
   usage data elem-bytes put-fn]
  (let [stack (MemoryStack/stackPush)
        cnt   (count data)
        size  (long (* cnt elem-bytes))
        bci   (VkBufferCreateInfo/calloc stack)]
    (try
      (.sType bci VK10/VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
      (.size  bci size)
      (.usage bci (int usage))
      (.sharingMode bci VK10/VK_SHARING_MODE_EXCLUSIVE)
      (let [lp (.mallocLong stack 1)
            r  (VK10/vkCreateBuffer device bci nil lp)]
        (when (not= r VK10/VK_SUCCESS)
          (throw (RuntimeException. (str "vkCreateBuffer failed: " r))))
        (let [buf          (.get lp 0)
              mr           (VkMemoryRequirements/malloc stack)
              _            (VK10/vkGetBufferMemoryRequirements device buf mr)
              host-visible (bit-or VK10/VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                                   VK10/VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
              mem-type-idx (find-memory-type physical-device (.memoryTypeBits mr) host-visible)
              mai          (VkMemoryAllocateInfo/calloc stack)]
          (.sType           mai VK10/VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
          (.allocationSize  mai (.size mr))
          (.memoryTypeIndex mai (int mem-type-idx))
          (.rewind lp)
          (let [r2 (VK10/vkAllocateMemory device mai nil lp)]
            (when (not= r2 VK10/VK_SUCCESS)
              (VK10/vkDestroyBuffer device buf nil)
              (throw (RuntimeException. (str "vkAllocateMemory failed: " r2))))
            (let [mem    (.get lp 0)
                  _      (VK10/vkBindBufferMemory device buf mem 0)
                  pp     (.mallocPointer stack 1)
                  _      (VK10/vkMapMemory device mem 0 size 0 pp)
                  mapped (MemoryUtil/memByteBuffer (.get pp 0) (int size))]
              (.order mapped ByteOrder/LITTLE_ENDIAN)
              (put-fn mapped data)
              (VK10/vkUnmapMemory device mem)
              {:buffer buf :memory mem :device device}))))
      (finally
        (MemoryStack/stackPop)))))

(defn- create-vertex-buffer! [device pd float-data]
  (create-buffer! device pd
                  VK10/VK_BUFFER_USAGE_VERTEX_BUFFER_BIT
                  float-data 4
                  (fn [mapped data]
                    (let [fb (.asFloatBuffer mapped)]
                      (doseq [f data] (.put fb (float f)))))))

(defn- create-index-buffer! [device pd short-data]
  (create-buffer! device pd
                  VK10/VK_BUFFER_USAGE_INDEX_BUFFER_BIT
                  short-data 2
                  (fn [mapped data]
                    (let [sb (.asShortBuffer mapped)]
                      (doseq [s data] (.put sb (short s)))))))

;; ---------------------------------------------------------------------------
;; Renderable
;; ---------------------------------------------------------------------------

(defn- make-sprite-renderable [cfg _renderer]
  (let [image-path     (or (:image cfg)
                           (throw (ex-info ":sprite requires :image path" {:cfg cfg})))
        width          (double (or (:width  cfg) 0.2))
        height         (double (or (:height cfg) 0.2))
        def-color      (vec (or (:color cfg) [1.0 1.0 1.0 1.0]))
        pipeline-atom  (atom nil)
        vbuf-atom      (atom nil)
        ibuf-atom      (atom nil)
        texture-atom   (atom nil)
        desc-pool-atom (atom nil)
        desc-set-atom  (atom nil)
        instances      (atom [])
        pc-buf         (doto (ByteBuffer/allocateDirect push-constant-bytes)
                         (.order ByteOrder/LITTLE_ENDIAN))]
    (with-meta
      (reify renderable/Renderable
        (draw [_this command-buffer _device _render-pass _extent]
          (let [pl @pipeline-atom
                vb @vbuf-atom
                ib @ibuf-atom
                ds @desc-set-atom]
            (when (and pl vb ib ds)
              (let [{:keys [pipeline layout]} pl]
                (VK10/vkCmdBindPipeline
                 ^org.lwjgl.vulkan.VkCommandBuffer command-buffer
                 VK10/VK_PIPELINE_BIND_POINT_GRAPHICS
                 (long pipeline))
                (let [stack (MemoryStack/stackPush)
                      vbp   (doto (.mallocLong stack 1) (.put (:buffer vb)) (.flip))
                      offp  (doto (.mallocLong stack 1) (.put 0) (.flip))]
                  (VK10/vkCmdBindVertexBuffers command-buffer 0 vbp offp)
                  (MemoryStack/stackPop))
                (VK10/vkCmdBindIndexBuffer
                 ^org.lwjgl.vulkan.VkCommandBuffer command-buffer
                 (long (:buffer ib))
                 0
                 VK10/VK_INDEX_TYPE_UINT16)
                (let [stack (MemoryStack/stackPush)
                      dsp   (doto (.mallocLong stack 1) (.put (long ds)) (.flip))]
                  (VK10/vkCmdBindDescriptorSets
                   ^org.lwjgl.vulkan.VkCommandBuffer command-buffer
                   VK10/VK_PIPELINE_BIND_POINT_GRAPHICS
                   (long layout)
                   0 dsp nil)
                  (MemoryStack/stackPop))
                (let [insts     @instances
                      draw-list (if (seq insts)
                                  insts
                                  [{:x 0.0 :y 0.0 :rotation 0.0
                                    :scale-x 1.0 :scale-y 1.0
                                    :color def-color}])]
                  (doseq [{:keys [x y rotation scale-x scale-y color]} draw-list]
                    (let [c  (vec (or color def-color))
                          sx (float (or scale-x 1.0))
                          sy (float (or scale-y 1.0))]
                      (.clear pc-buf)
                      (.putFloat pc-buf (float (or x 0.0)))
                      (.putFloat pc-buf (float (or y 0.0)))
                      (.putFloat pc-buf (float (or rotation 0.0)))
                      (.putFloat pc-buf 0.0)                        ; _pad0
                      (.putFloat pc-buf sx)
                      (.putFloat pc-buf sy)
                      (.putFloat pc-buf 0.0)                        ; _pad1.x
                      (.putFloat pc-buf 0.0)                        ; _pad1.y
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
                       pc-buf))
                    (VK10/vkCmdDrawIndexed
                     ^org.lwjgl.vulkan.VkCommandBuffer command-buffer
                     6 1 0 0 0)))))))
        (cleanup! [_this device]
          (when-let [dp @desc-pool-atom]
            (VK10/vkDestroyDescriptorPool device (long dp) nil)
            (reset! desc-pool-atom nil)
            (reset! desc-set-atom nil))
          (when-let [tx @texture-atom]
            (texture/destroy-texture! tx)
            (reset! texture-atom nil))
          (when-let [pl @pipeline-atom]
            (pipeline/destroy! pl)
            (reset! pipeline-atom nil))
          (when-let [vb @vbuf-atom]
            (VK10/vkDestroyBuffer device (long (:buffer vb)) nil)
            (VK10/vkFreeMemory    device (long (:memory vb)) nil)
            (reset! vbuf-atom nil))
          (when-let [ib @ibuf-atom]
            (VK10/vkDestroyBuffer device (long (:buffer ib)) nil)
            (VK10/vkFreeMemory    device (long (:memory ib)) nil)
            (reset! ibuf-atom nil))))
      {:pipeline-atom  pipeline-atom
       :vbuf-atom      vbuf-atom
       :ibuf-atom      ibuf-atom
       :texture-atom   texture-atom
       :desc-pool-atom desc-pool-atom
       :desc-set-atom  desc-set-atom
       :instances      instances
       :image-path     image-path
       :width          width
       :height         height})))

(defn build-pipeline!
  "Build all GPU resources for a sprite renderable.
   Called by spock.edn after Vulkan init completes."
  [renderable renderer]
  (let [{:keys [pipeline-atom vbuf-atom ibuf-atom texture-atom
                desc-pool-atom desc-set-atom image-path width height]} (meta renderable)
        ^org.lwjgl.vulkan.VkDevice dev (renderer/get-device renderer)
        pd  (.getPhysicalDevice dev)
        rp  (renderer/get-render-pass renderer)
        vb  (create-vertex-buffer! dev pd (make-vertices width height))
        ib  (create-index-buffer!  dev pd (make-indices))
        pl  (-> (pipeline/builder dev rp)
                (pipeline/vert-path "src/shaders/sprite.vert")
                (pipeline/frag-path "src/shaders/sprite.frag")
                (pipeline/topology  :triangle-list)
                (pipeline/cull-mode :none)
                (pipeline/vertex-input 16 [{:location 0
                                            :format   vk-format-r32g32-sfloat
                                            :offset   0}
                                           {:location 1
                                            :format   vk-format-r32g32-sfloat
                                            :offset   8}])
                (pipeline/push-constant-size push-constant-bytes)
                (pipeline/descriptor-layout [{:binding 0
                                              :type    :combined-image-sampler
                                              :stage   :fragment}])
                (pipeline/build!))
        tex (texture/load-texture! image-path renderer)
        dp  (pipeline/create-descriptor-pool! dev 1)
        ds  (pipeline/allocate-descriptor-set! pl dp tex)]
    (reset! vbuf-atom      vb)
    (reset! ibuf-atom      ib)
    (reset! pipeline-atom  pl)
    (reset! texture-atom   tex)
    (reset! desc-pool-atom dp)
    (reset! desc-set-atom  ds)))

(defn instances
  "Return the instances atom for a sprite renderable.
   Reset it to a vector of {:x :y :rotation :scale-x :scale-y :color} maps each tick."
  [sprite-renderable]
  (:instances (meta sprite-renderable)))

;; Register on namespace load
(registry/register-renderable! :sprite make-sprite-renderable)
