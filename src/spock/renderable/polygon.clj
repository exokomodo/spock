(ns spock.renderable.polygon
  "Built-in :polygon renderable — draws a regular N-gon using a host-visible
   vertex buffer and per-instance push constants.

   Configuration:
     {:type   :polygon
      :sides  N           ;; number of sides (default 6)
      :radius R           ;; NDC radius (default 0.1)
      :color  [r g b a]} ;; default instance color

   The renderable maintains an instance pool — a vector of maps:
     {:x float :y float :rotation float :color [r g b a]}
   Scripts update it via (polygon/instances renderable) each tick.

   Registers itself with spock.renderable.registry on namespace load."
  (:require [spock.renderable.core     :as renderable]
            [spock.renderable.registry :as registry]
            [spock.pipeline.core       :as pipeline]
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
;; Push constant layout (std430 / packed)
;;   offset 0:  vec2  translation (8 bytes)
;;   offset 8:  float rotation    (4 bytes)
;;   offset 12: float padding     (4 bytes — aligns vec4 to 16 bytes)
;;   offset 16: vec4  color       (16 bytes)
;;   total: 32 bytes
;; ---------------------------------------------------------------------------

(def ^:private ^:const push-constant-bytes 32)

;; VK_FORMAT_R32G32_SFLOAT = 103
(def ^:private ^:const vk-format-r32g32-sfloat 103)

;; ---------------------------------------------------------------------------
;; Vertex geometry
;; ---------------------------------------------------------------------------

(defn- compute-vertices
  "Flat float array of (sides * 2) values for a regular N-gon, CCW winding."
  [sides radius]
  (let [n    (int sides)
        step (/ (* 2.0 Math/PI) n)]
    (float-array
     (for [i (range n)
           coord [:x :y]]
       (let [angle (* i step)]
         (case coord
           :x (* (double radius) (Math/cos angle))
           :y (* (double radius) (- (Math/sin angle)))))))))   ; flip Y for Vulkan

;; ---------------------------------------------------------------------------
;; GPU memory helpers
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

(defn- create-vertex-buffer!
  "Upload float-data to a host-visible vertex buffer.
   Returns {:buffer long :memory long :device device}."
  [^org.lwjgl.vulkan.VkDevice device
   ^org.lwjgl.vulkan.VkPhysicalDevice physical-device
   float-data]
  (let [stack (MemoryStack/stackPush)
        floats (count float-data)
        size   (long (* floats 4))
        bci    (VkBufferCreateInfo/calloc stack)]
    (try
      (.sType bci VK10/VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
      (.size  bci size)
      (.usage bci VK10/VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
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
              (let [fb (.asFloatBuffer mapped)]
                (doseq [f float-data] (.put fb (float f))))
              (VK10/vkUnmapMemory device mem)
              {:buffer buf
               :memory mem
               :device device}))))
      (finally
        (MemoryStack/stackPop)))))

;; ---------------------------------------------------------------------------
;; Renderable
;; ---------------------------------------------------------------------------

(defn- make-polygon-renderable [cfg _renderer]
  (let [sides     (int (or (:sides cfg) 6))
        radius    (double (or (:radius cfg) 0.1))
        def-color (vec (or (:color cfg) [1.0 1.0 1.0 1.0]))
        pipeline-atom (atom nil)
        vbuf-atom     (atom nil)
        instances     (atom [])
        pc-buf (doto (ByteBuffer/allocateDirect push-constant-bytes)
                 (.order ByteOrder/LITTLE_ENDIAN))]
    (with-meta
      (reify renderable/Renderable
        (draw [_this command-buffer _device _render-pass _extent]
          (let [pl @pipeline-atom
                vb @vbuf-atom]
            (when (and pl vb)
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
                (let [insts     @instances
                      draw-list (if (seq insts)
                                  insts
                                  [{:x 0.0 :y 0.0 :rotation 0.0 :color def-color}])]
                  (doseq [{:keys [x y rotation color]} draw-list]
                    (let [c (vec (or color def-color))]
                      (.clear pc-buf)
                      (.putFloat pc-buf (float (or x 0.0)))
                      (.putFloat pc-buf (float (or y 0.0)))
                      (.putFloat pc-buf (float (or rotation 0.0)))
                      (.putFloat pc-buf 0.0)                     ; padding
                      (.putFloat pc-buf (float (nth c 0 1.0)))
                      (.putFloat pc-buf (float (nth c 1 1.0)))
                      (.putFloat pc-buf (float (nth c 2 1.0)))
                      (.putFloat pc-buf (float (nth c 3 1.0)))
                      (.flip pc-buf)
                      (pipeline/push-constants!
                       command-buffer
                       (long layout)
                       VK10/VK_SHADER_STAGE_VERTEX_BIT
                       pc-buf))
                    (VK10/vkCmdDraw
                     ^org.lwjgl.vulkan.VkCommandBuffer command-buffer
                     (int sides) 1 0 0)))))))
        (cleanup! [_this device]
          (when-let [pl @pipeline-atom]
            (pipeline/destroy! pl)
            (reset! pipeline-atom nil))
          (when-let [vb @vbuf-atom]
            (VK10/vkDestroyBuffer device (long (:buffer vb)) nil)
            (VK10/vkFreeMemory    device (long (:memory vb)) nil)
            (reset! vbuf-atom nil))))
      {:pipeline-atom pipeline-atom
       :vbuf-atom     vbuf-atom
       :instances     instances
       :sides         sides
       :radius        radius})))

(defn build-pipeline!
  "Build the Vulkan pipeline and vertex buffer for a polygon renderable.
   Called by spock.edn after Vulkan init completes."
  [renderable renderer]
  (let [{:keys [pipeline-atom vbuf-atom sides radius]} (meta renderable)
        ^org.lwjgl.vulkan.VkDevice dev (renderer/get-device renderer)
        pd  (.getPhysicalDevice dev)
        rp  (renderer/get-render-pass renderer)
        floats (compute-vertices sides radius)
        vb     (create-vertex-buffer! dev pd floats)
        pl     (-> (pipeline/builder dev rp)
                   (pipeline/vert-path "src/shaders/polygon.vert")
                   (pipeline/frag-path "src/shaders/polygon.frag")
                   (pipeline/topology :triangle-fan)
                   (pipeline/cull-mode :none)
                   (pipeline/vertex-input 8 [{:location 0
                                              :format   vk-format-r32g32-sfloat
                                              :offset   0}])
                   (pipeline/push-constant-size push-constant-bytes)
                   (pipeline/build!))]
    (reset! vbuf-atom vb)
    (reset! pipeline-atom pl)))

(defn instances
  "Return the instances atom for a polygon renderable.
   Reset it to a vector of {:x :y :rotation :color} maps each tick."
  [polygon-renderable]
  (:instances (meta polygon-renderable)))

;; Register on namespace load
(registry/register-renderable! :polygon make-polygon-renderable)
