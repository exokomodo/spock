(ns spock.renderable.mesh
  "Built-in :mesh renderable — draws an indexed OBJ mesh with Phong shading.

   Configuration:
     {:type  :mesh
      :file  \"path/to/model.obj\"
      :color [r g b a]}          ;; default tint (optional, default warm orange)

   Scripts update transforms each tick via (mesh/instances renderable):
     (reset! (mesh/instances r) [{:model float-array   ; 16-element column-major
                                   :vp    float-array   ; 16-element column-major
                                   :color [r g b a]}])

   Registers itself with spock.renderable.registry on namespace load."
  (:require [spock.renderable.core     :as renderable]
            [spock.renderable.registry :as registry]
            [spock.pipeline.core       :as pipeline]
            [spock.renderer.core       :as renderer]
            [spock.obj                 :as obj]
            [warpaint.compiler         :as compiler]
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
;; Push constant layout:
;;   mat4 model  = 64 bytes
;;   mat4 vp     = 64 bytes
;;   vec4 color  = 16 bytes
;;   total       = 144 bytes
;; ---------------------------------------------------------------------------

(def ^:private ^:const push-constant-bytes 144)

;; Vertex stride: vec3 pos + vec3 normal + vec2 uv = 32 bytes
(def ^:private ^:const vertex-stride 32)

;; VK_FORMAT_R32G32B32_SFLOAT = 106
(def ^:private ^:const vk-fmt-rgb32f 106)
;; VK_FORMAT_R32G32_SFLOAT = 103
(def ^:private ^:const vk-fmt-rg32f  103)

;; ---------------------------------------------------------------------------
;; GPU memory helpers (same pattern as polygon)
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

(defn- create-buffer!
  [^org.lwjgl.vulkan.VkDevice device
   ^org.lwjgl.vulkan.VkPhysicalDevice pd
   size usage]
  (let [stack (MemoryStack/stackPush)]
    (try
      (let [bci (doto (VkBufferCreateInfo/calloc stack)
                  (.sType VK10/VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                  (.size  (long size))
                  (.usage (int usage))
                  (.sharingMode VK10/VK_SHARING_MODE_EXCLUSIVE))
            lp  (.mallocLong stack 1)
            r   (VK10/vkCreateBuffer device bci nil lp)]
        (when (not= r VK10/VK_SUCCESS)
          (throw (RuntimeException. (str "vkCreateBuffer failed: " r))))
        (let [buf (long (.get lp 0))
              mr  (VkMemoryRequirements/malloc stack)
              _   (VK10/vkGetBufferMemoryRequirements device buf mr)
              mti (find-memory-type pd (.memoryTypeBits mr)
                                    (bit-or VK10/VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                                            VK10/VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))
              mai (doto (VkMemoryAllocateInfo/calloc stack)
                    (.sType VK10/VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    (.allocationSize (.size mr))
                    (.memoryTypeIndex (int mti)))
              _   (.rewind lp)
              r2  (VK10/vkAllocateMemory device mai nil lp)]
          (when (not= r2 VK10/VK_SUCCESS)
            (VK10/vkDestroyBuffer device buf nil)
            (throw (RuntimeException. (str "vkAllocateMemory failed: " r2))))
          (let [mem (long (.get lp 0))]
            (VK10/vkBindBufferMemory device buf mem 0)
            {:buffer buf :memory mem :device device})))
      (finally (MemoryStack/stackPop)))))

(defn- upload-float-buffer!
  "Upload a float-array into a host-visible vertex buffer."
  [device pd ^floats data usage]
  (let [size (long (* (alength data) 4))
        buf  (create-buffer! device pd size usage)
        stack (MemoryStack/stackPush)]
    (try
      (let [pp     (.mallocPointer stack 1)
            _      (VK10/vkMapMemory device (long (:memory buf)) 0 size 0 pp)
            mapped (MemoryUtil/memByteBuffer (.get pp 0) (int size))]
        (.order mapped ByteOrder/LITTLE_ENDIAN)
        (let [fb (.asFloatBuffer mapped)]
          (doseq [f data] (.put fb (float f))))
        (VK10/vkUnmapMemory device (long (:memory buf))))
      (finally (MemoryStack/stackPop)))
    buf))

(defn- upload-int-buffer!
  "Upload an int-array into a host-visible index buffer."
  [device pd ^ints data usage]
  (let [size (long (* (alength data) 4))
        buf  (create-buffer! device pd size usage)
        stack (MemoryStack/stackPush)]
    (try
      (let [pp     (.mallocPointer stack 1)
            _      (VK10/vkMapMemory device (long (:memory buf)) 0 size 0 pp)
            mapped (MemoryUtil/memByteBuffer (.get pp 0) (int size))]
        (.order mapped ByteOrder/LITTLE_ENDIAN)
        (let [ib (.asIntBuffer mapped)]
          (doseq [i data] (.put ib (int i))))
        (VK10/vkUnmapMemory device (long (:memory buf))))
      (finally (MemoryStack/stackPop)))
    buf))

(defn- identity-matrix ^floats []
  (float-array [1 0 0 0  0 1 0 0  0 0 1 0  0 0 0 1]))

;; ---------------------------------------------------------------------------
;; Renderable
;; ---------------------------------------------------------------------------

(defn- make-mesh-renderable [cfg _renderer]
  (let [obj-path      (or (:file cfg) (throw (ex-info ":mesh requires :file" {:cfg cfg})))
        def-color     (vec (or (:color cfg) [0.8 0.6 0.3 1.0]))
        pipeline-atom (atom nil)
        vbuf-atom     (atom nil)
        ibuf-atom     (atom nil)
        index-count   (atom 0)
        instances     (atom [])
        ;; Allocate push-constant buffer once — must be direct for vkCmdPushConstants
        pc-buf        (doto (ByteBuffer/allocateDirect push-constant-bytes)
                        (.order ByteOrder/LITTLE_ENDIAN))]
    (with-meta
      (reify renderable/Renderable
        (draw [_this command-buffer _device _render-pass _extent]
          (let [pl @pipeline-atom
                vb @vbuf-atom
                ib @ibuf-atom
                ic @index-count]
            (when (and pl vb ib (pos? ic))
              (let [{:keys [pipeline layout]} pl]
                ;; Bind pipeline
                (VK10/vkCmdBindPipeline
                 ^org.lwjgl.vulkan.VkCommandBuffer command-buffer
                 VK10/VK_PIPELINE_BIND_POINT_GRAPHICS
                 (long pipeline))
                ;; Bind vertex + index buffers
                (let [stack (MemoryStack/stackPush)
                      vbp   (doto (.mallocLong stack 1) (.put (long (:buffer vb))) (.flip))
                      offp  (doto (.mallocLong stack 1) (.put 0) (.flip))]
                  (VK10/vkCmdBindVertexBuffers command-buffer 0 vbp offp)
                  (VK10/vkCmdBindIndexBuffer command-buffer (long (:buffer ib))
                                             0 VK10/VK_INDEX_TYPE_UINT32)
                  (MemoryStack/stackPop))
                ;; Draw each instance
                (let [insts (let [is @instances]
                              (if (seq is)
                                is
                                [{:model (identity-matrix)
                                  :vp    (identity-matrix)
                                  :color def-color}]))]
                  (doseq [{:keys [model vp color]} insts]
                    (let [m (or model (identity-matrix))
                          v (or vp    (identity-matrix))
                          c (vec (or color def-color))]
                      (.clear pc-buf)
                      (doseq [f m] (.putFloat pc-buf (float f)))
                      (doseq [f v] (.putFloat pc-buf (float f)))
                      (.putFloat pc-buf (float (nth c 0 1.0)))
                      (.putFloat pc-buf (float (nth c 1 1.0)))
                      (.putFloat pc-buf (float (nth c 2 1.0)))
                      (.putFloat pc-buf (float (nth c 3 1.0)))
                      (.flip pc-buf)
                      (pipeline/push-constants!
                       command-buffer (long layout)
                       (bit-or VK10/VK_SHADER_STAGE_VERTEX_BIT
                               VK10/VK_SHADER_STAGE_FRAGMENT_BIT)
                       pc-buf)
                      (VK10/vkCmdDrawIndexed
                       ^org.lwjgl.vulkan.VkCommandBuffer command-buffer
                       (int ic) 1 0 0 0))))))))

        (cleanup! [_this device]
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

      {:pipeline-atom pipeline-atom
       :vbuf-atom     vbuf-atom
       :ibuf-atom     ibuf-atom
       :index-count   index-count
       :instances     instances
       :obj-path      obj-path
       :def-color     def-color})))

;; ---------------------------------------------------------------------------
;; build-pipeline! — called by spock.edn after Vulkan init
;; ---------------------------------------------------------------------------

(defn build-pipeline!
  "Load OBJ, upload GPU buffers, compile shaders, build pipeline."
  [renderable renderer]
  (let [{:keys [pipeline-atom vbuf-atom ibuf-atom index-count obj-path]} (meta renderable)
        ^org.lwjgl.vulkan.VkDevice dev (renderer/get-device renderer)
        pd (.getPhysicalDevice dev)
        rp (renderer/get-render-pass renderer)
        _ (spock.util.logging/log-with-context! "mesh/build-pipeline! loading OBJ:" obj-path)
        mesh (obj/load-obj obj-path)]
    (spock.util.logging/log-with-context! "mesh/build-pipeline! loaded" obj-path
              "verts=" (:vertex-count mesh) "indices=" (:index-count mesh))

    (spock.util.logging/log-with-context! "mesh/build-pipeline! uploading vertex buffer...")
    (reset! vbuf-atom
            (upload-float-buffer! dev pd (:vertices mesh)
                                  VK10/VK_BUFFER_USAGE_VERTEX_BUFFER_BIT))

    (spock.util.logging/log-with-context! "mesh/build-pipeline! uploading index buffer...")
    (reset! ibuf-atom
            (upload-int-buffer! dev pd (:indices mesh)
                                VK10/VK_BUFFER_USAGE_INDEX_BUFFER_BIT))

    (reset! index-count (:index-count mesh))

    (spock.util.logging/log-with-context! "mesh/build-pipeline! compiling shaders...")
    (let [_ (compiler/compile-glsl "src/shaders/mesh.vert")
          vert-spv (compiler/load-spirv "src/shaders/mesh.vert.spv")
          _ (spock.util.logging/log-with-context! "mesh/build-pipeline! vert-spv:" (some? vert-spv))
          _ (compiler/compile-glsl "src/shaders/mesh.frag")
          frag-spv (compiler/load-spirv "src/shaders/mesh.frag.spv")
          _ (spock.util.logging/log-with-context! "mesh/build-pipeline! frag-spv:" (some? frag-spv))
          _ (spock.util.logging/log-with-context! "mesh/build-pipeline! building pipeline...")
          pl (-> (pipeline/builder dev rp)
                 (pipeline/vert-spv vert-spv)
                 (pipeline/frag-spv frag-spv)
                 (pipeline/topology :triangle-list)
                 (pipeline/cull-mode :none)
                 (pipeline/depth-test)
                 (pipeline/vertex-input vertex-stride
                                        [{:location 0 :format vk-fmt-rgb32f :offset 0}
                                         {:location 1 :format vk-fmt-rgb32f :offset 12}
                                         {:location 2 :format vk-fmt-rg32f  :offset 24}])
                 (pipeline/push-constant-size push-constant-bytes)
                 (pipeline/build!))]
      (spock.util.logging/log-with-context! "mesh/build-pipeline! OK")
      (reset! pipeline-atom pl))))

;; ---------------------------------------------------------------------------
;; Public helpers
;; ---------------------------------------------------------------------------

(defn instances
  "Return the instances atom. Reset each tick to a vector of:
   {:model float-array   ; 16-element column-major model matrix
    :vp    float-array   ; 16-element column-major view-projection matrix
    :color [r g b a]}"
  [mesh-renderable]
  (:instances (meta mesh-renderable)))

;; Register on namespace load
(registry/register-renderable! :mesh make-mesh-renderable)
