(ns spock.renderable.text
  "Built-in :text renderable — draws bitmap font text using AngelCode .fnt.

   Configuration:
     {:type  :text
      :font  \"assets/fonts/mono.fnt\"
      :text  \"Hello\"
      :size  0.05          ;; NDC height of one line
      :color [1 1 1 1]}

   The renderable maintains an instances atom — vector of maps:
     {:text string :x float :y float :color [r g b a] :size float}
   Scripts update it via (text/instances renderable) each tick.

   Registers itself with spock.renderable.registry on namespace load."
  (:require [spock.renderable.core     :as renderable]
            [spock.renderable.registry :as registry]
            [spock.pipeline.core       :as pipeline]
            [spock.renderer.core       :as renderer]
            [spock.texture.core        :as texture]
            [spock.font                :as font]
            [spock.log                 :as log])
  (:import [org.lwjgl.vulkan
            VK10
            VkBufferCreateInfo
            VkMemoryRequirements
            VkMemoryAllocateInfo
            VkPhysicalDeviceMemoryProperties
            VkDescriptorPoolCreateInfo
            VkDescriptorPoolSize
            VkDescriptorSetAllocateInfo
            VkWriteDescriptorSet
            VkDescriptorImageInfo]
           [org.lwjgl.system MemoryStack MemoryUtil]
           [java.nio ByteBuffer ByteOrder]))

;; ---------------------------------------------------------------------------
;; Vertex layout:
;;   vec2 pos  (8 bytes)
;;   vec2 uv   (8 bytes)
;;   vec4 color (16 bytes)
;;   total: 32 bytes per vertex, 6 vertices per quad
;; ---------------------------------------------------------------------------

(def ^:private ^:const bytes-per-vertex 32)
(def ^:private ^:const verts-per-quad 6)
(def ^:private ^:const max-chars 256)
(def ^:private ^:const vbuf-bytes (* max-chars verts-per-quad bytes-per-vertex))

;; VK_FORMAT_R32G32_SFLOAT = 103
(def ^:private ^:const vk-fmt-rg32f 103)
;; VK_FORMAT_R32G32B32A32_SFLOAT = 109
(def ^:private ^:const vk-fmt-rgba32f 109)

;; ---------------------------------------------------------------------------
;; GPU helpers (mirrors polygon.clj)
;; ---------------------------------------------------------------------------

(defn- find-memory-type
  [^org.lwjgl.vulkan.VkPhysicalDevice pd type-filter required-props]
  (let [stack (MemoryStack/stackPush)]
    (try
      (let [props (VkPhysicalDeviceMemoryProperties/malloc stack)]
        (VK10/vkGetPhysicalDeviceMemoryProperties pd props)
        (loop [i 0]
          (if (>= i (.memoryTypeCount props))
            (throw (RuntimeException. "No suitable memory type"))
            (let [flags (.propertyFlags (.get (.memoryTypes props) i))]
              (if (and (not= 0 (bit-and (int type-filter) (bit-shift-left 1 i)))
                       (= (int required-props) (bit-and (int flags) (int required-props))))
                i
                (recur (inc i)))))))
      (finally (MemoryStack/stackPop)))))

(defn- create-host-buffer!
  "Create a host-visible buffer of given size. Returns {:buffer :memory :mapped :device}."
  [^org.lwjgl.vulkan.VkDevice device
   ^org.lwjgl.vulkan.VkPhysicalDevice pd
   ^long size]
  (let [stack (MemoryStack/stackPush)]
    (try
      (let [bci (doto (VkBufferCreateInfo/calloc stack)
                  (.sType VK10/VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                  (.size size)
                  (.usage VK10/VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
                  (.sharingMode VK10/VK_SHARING_MODE_EXCLUSIVE))
            lp  (.mallocLong stack 1)
            r   (VK10/vkCreateBuffer device bci nil lp)]
        (when (not= r VK10/VK_SUCCESS)
          (throw (RuntimeException. (str "vkCreateBuffer failed: " r))))
        (let [buf (long (.get lp 0))
              mr  (VkMemoryRequirements/malloc stack)
              _   (VK10/vkGetBufferMemoryRequirements device buf mr)
              hv  (bit-or VK10/VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                          VK10/VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
              mti (find-memory-type pd (.memoryTypeBits mr) hv)
              mai (doto (VkMemoryAllocateInfo/calloc stack)
                    (.sType VK10/VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    (.allocationSize (.size mr))
                    (.memoryTypeIndex (int mti)))
              _   (.rewind lp)
              r2  (VK10/vkAllocateMemory device mai nil lp)]
          (when (not= r2 VK10/VK_SUCCESS)
            (VK10/vkDestroyBuffer device buf nil)
            (throw (RuntimeException. (str "vkAllocateMemory failed: " r2))))
          (let [mem (long (.get lp 0))
                _   (VK10/vkBindBufferMemory device buf mem 0)
                pp  (.mallocPointer stack 1)
                _   (VK10/vkMapMemory device mem 0 size 0 pp)
                mapped (MemoryUtil/memByteBuffer (.get pp 0) (int size))]
            (.order mapped ByteOrder/LITTLE_ENDIAN)
            {:buffer buf :memory mem :mapped mapped :device device})))
      (finally (MemoryStack/stackPop)))))

;; ---------------------------------------------------------------------------
;; Descriptor pool + set helpers (same pattern as sprite.clj)
;; ---------------------------------------------------------------------------

(defn- create-descriptor-pool! [^org.lwjgl.vulkan.VkDevice device]
  (let [stack (MemoryStack/stackPush)]
    (try
      (let [ps  (.get (VkDescriptorPoolSize/calloc 1 ^MemoryStack stack) 0)]
        (.type ps VK10/VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
        (.descriptorCount ps 1)
        (let [sizes (VkDescriptorPoolSize/create (.address ps) 1)
              ci    (doto (VkDescriptorPoolCreateInfo/calloc stack)
                      (.sType VK10/VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                      (.pPoolSizes sizes)
                      (.maxSets 1))
              lp (.mallocLong stack 1)
              r  (VK10/vkCreateDescriptorPool device ci nil lp)]
          (when (not= r VK10/VK_SUCCESS)
            (throw (RuntimeException. (str "vkCreateDescriptorPool failed: " r))))
          (.get lp 0)))
      (finally (MemoryStack/stackPop)))))

(defn- allocate-descriptor-set!
  [^org.lwjgl.vulkan.VkDevice device pool layout-handle]
  (let [stack (MemoryStack/stackPush)]
    (try
      (let [layouts (doto (.mallocLong stack 1) (.put (long layout-handle)) (.flip))
            ai      (doto (VkDescriptorSetAllocateInfo/calloc stack)
                      (.sType VK10/VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                      (.descriptorPool (long pool))
                      (.pSetLayouts layouts))
            lp (.mallocLong stack 1)
            r  (VK10/vkAllocateDescriptorSets device ai lp)]
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
        (let [infos  (VkDescriptorImageInfo/create (.address ii) 1)
              wr     (.get (VkWriteDescriptorSet/calloc 1 ^MemoryStack stack) 0)]
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
;; Vertex buffer fill
;; ---------------------------------------------------------------------------

(defn- fill-vertex-buffer!
  "Write glyph quads into the mapped vertex ByteBuffer. Returns vertex count."
  [^ByteBuffer buf instances font]
  (let [{:keys [line-height]} font]
    (.clear buf)
    (loop [insts   instances
           vcount  0]
      (if (or (nil? insts) (>= vcount (* (- max-chars 1) verts-per-quad)))
        vcount
        (let [{:keys [text x y color size]} (first insts)
              ndc-scale (double (/ (double (or size 0.05)) (double line-height)))
              c         (vec (or color [1.0 1.0 1.0 1.0]))
              quads     (font/text->quads font (or text ""))
              n-glyphs  (min (count quads) (- max-chars 1))]
          (loop [qs     (take n-glyphs quads)
                 vcount vcount]
            (if (or (nil? qs) (empty? qs))
              (recur (next insts) vcount)
              (let [{:keys [u0 v0 u1 v1 bx by bw bh]} (first qs)
                    px0 (+ (double x) (* bx ndc-scale))
                    py0 (+ (double y) (* by ndc-scale))
                    px1 (+ px0 (* bw ndc-scale))
                    py1 (+ py0 (* bh ndc-scale))
                    r   (float (nth c 0 1.0))
                    g   (float (nth c 1 1.0))
                    b   (float (nth c 2 1.0))
                    a   (float (nth c 3 1.0))
                    ;; two triangles: TL TR BR  TL BR BL
                    vertices [[px0 py0 u0 v0]
                              [px1 py0 u1 v0]
                              [px1 py1 u1 v1]
                              [px0 py0 u0 v0]
                              [px1 py1 u1 v1]
                              [px0 py1 u0 v1]]]
                (doseq [[vx vy vu vv] vertices]
                  (.putFloat buf (float vx))
                  (.putFloat buf (float vy))
                  (.putFloat buf (float vu))
                  (.putFloat buf (float vv))
                  (.putFloat buf r)
                  (.putFloat buf g)
                  (.putFloat buf b)
                  (.putFloat buf a))
                (recur (next qs) (+ vcount verts-per-quad))))))))))

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
        vbuf-atom        (atom nil)
        desc-pool-atom   (atom nil)
        desc-set-atom    (atom nil)
        desc-layout-atom (atom nil)
        instances        (atom [])]
    (with-meta
      (reify renderable/Renderable
        (draw [_this command-buffer _device _render-pass _extent]
          (let [pl    @pipeline-atom
                dset  @desc-set-atom
                vb    @vbuf-atom
                fnt   @font-atom]
            (when (and pl dset vb fnt)
              (let [{:keys [pipeline layout]} pl
                    {:keys [buffer mapped]} vb
                    insts (let [is @instances]
                            (if (seq is)
                              is
                              [{:text def-text :x -0.9 :y -0.9
                                :color def-color :size def-size}]))
                    vcount (fill-vertex-buffer! mapped insts fnt)]
                (.flip mapped)
                (when (pos? vcount)
                  (VK10/vkCmdBindPipeline
                   ^org.lwjgl.vulkan.VkCommandBuffer command-buffer
                   VK10/VK_PIPELINE_BIND_POINT_GRAPHICS
                   (long pipeline))
                  (let [stack (MemoryStack/stackPush)
                        dsp   (doto (.mallocLong stack 1)
                                (.put (long dset)) (.flip))
                        vbp   (doto (.mallocLong stack 1)
                                (.put buffer) (.flip))
                        offp  (doto (.mallocLong stack 1)
                                (.put 0) (.flip))]
                    (VK10/vkCmdBindDescriptorSets
                     ^org.lwjgl.vulkan.VkCommandBuffer command-buffer
                     VK10/VK_PIPELINE_BIND_POINT_GRAPHICS
                     (long layout) 0 dsp nil)
                    (VK10/vkCmdBindVertexBuffers command-buffer 0 vbp offp)
                    (MemoryStack/stackPop))
                  (VK10/vkCmdDraw
                   ^org.lwjgl.vulkan.VkCommandBuffer command-buffer
                   (int vcount) 1 0 0))))))

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
          (when-let [vb @vbuf-atom]
            (VK10/vkDestroyBuffer device (long (:buffer vb)) nil)
            (VK10/vkFreeMemory device (long (:memory vb)) nil)
            (reset! vbuf-atom nil))
          (when-let [pl @pipeline-atom]
            (pipeline/destroy! pl)
            (reset! pipeline-atom nil))))

      {:pipeline-atom    pipeline-atom
       :texture-atom     texture-atom
       :font-atom        font-atom
       :vbuf-atom        vbuf-atom
       :desc-pool-atom   desc-pool-atom
       :desc-set-atom    desc-set-atom
       :desc-layout-atom desc-layout-atom
       :instances        instances
       :font-path        font-path
       :def-text         def-text
       :def-size         def-size
       :def-color        def-color})))

(defn build-pipeline!
  "Load font + texture, create descriptor resources, build pipeline."
  [renderable renderer]
  (let [{:keys [pipeline-atom texture-atom font-atom vbuf-atom
                desc-pool-atom desc-set-atom desc-layout-atom font-path]}
        (meta renderable)
        ^org.lwjgl.vulkan.VkDevice device (renderer/get-device renderer)
        pd  (.getPhysicalDevice device)
        rp  (renderer/get-render-pass renderer)]

    ;; 1. Load font metadata
    (let [fnt (font/load! font-path)]
      (reset! font-atom fnt)

      ;; 2. Load atlas texture
      (let [tx (texture/load-texture! renderer (:atlas-path fnt))]
        (reset! texture-atom tx)

        ;; 3. Descriptor set layout
        (let [dl (pipeline/create-combined-image-sampler-layout! device)]
          (reset! desc-layout-atom dl)

          ;; 4. Descriptor pool + set
          (let [dp   (create-descriptor-pool! device)
                _    (reset! desc-pool-atom dp)
                dset (allocate-descriptor-set! device dp dl)]
            (write-descriptor-set! device dset (:image-view tx) (:sampler tx))
            (reset! desc-set-atom dset)

            ;; 5. Vertex buffer (host-visible, updated each frame)
            (let [vb (create-host-buffer! device pd vbuf-bytes)]
              (reset! vbuf-atom vb)

              ;; 6. Build pipeline
              (let [pl (-> (pipeline/builder device rp)
                           (pipeline/vert-path "src/shaders/text.vert")
                           (pipeline/frag-path "src/shaders/text.frag")
                           (pipeline/topology :triangle-list)
                           (pipeline/cull-mode :none)
                           (pipeline/descriptor-set-layout dl)
                           (pipeline/vertex-input bytes-per-vertex
                                                  [{:location 0
                                                    :format vk-fmt-rg32f
                                                    :offset 0}
                                                   {:location 1
                                                    :format vk-fmt-rg32f
                                                    :offset 8}
                                                   {:location 2
                                                    :format vk-fmt-rgba32f
                                                    :offset 16}])
                           (pipeline/build!))]
                (reset! pipeline-atom pl)))))))))

(defn instances
  "Return the instances atom. Reset to [{:text :x :y :color :size}] each tick."
  [text-renderable]
  (:instances (meta text-renderable)))

(registry/register-renderable! :text make-text-renderable)
