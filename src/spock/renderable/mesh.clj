(ns spock.renderable.mesh
  "Built-in :mesh renderable — draws an indexed OBJ mesh with Phong shading.

   Configuration:
     {:type  :mesh
      :file  \"examples/teapot/teapot.obj\"
      :color [0.8 0.6 0.3 1.0]}

   The renderable exposes an `instances` atom — a vector of maps:
     {:model float-array   ; 16-element column-major model matrix
      :vp    float-array   ; 16-element column-major view-projection matrix
      :color [r g b a]}    ; tint
   Scripts update it via (mesh/instances renderable) each tick.

   Registers itself with spock.renderable.registry on namespace load."
  (:require [spock.renderable.core     :as renderable]
            [spock.renderable.registry :as registry]
            [spock.pipeline.core       :as pipeline]
            [spock.renderer.core       :as renderer]
            [spock.obj                 :as obj]
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
;; Push constant layout: mat4 model + mat4 vp + vec4 color = 144 bytes
;; ---------------------------------------------------------------------------
(def ^:private ^:const push-constant-bytes 144)

;; Vertex format: vec3 pos + vec3 normal + vec2 uv = 32 bytes
(def ^:private ^:const vertex-stride 32)
;; VK_FORMAT_R32G32B32_SFLOAT = 106
(def ^:private ^:const vk-fmt-rgb32f  106)
;; VK_FORMAT_R32G32_SFLOAT = 103
(def ^:private ^:const vk-fmt-rg32f   103)

;; ---------------------------------------------------------------------------
;; GPU memory helpers
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
   size usage memory-props]
  (let [stack (MemoryStack/stackPush)]
    (try
      (let [bci (doto (VkBufferCreateInfo/calloc stack)
                  (.sType VK10/VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                  (.size size)
                  (.usage (int usage))
                  (.sharingMode VK10/VK_SHARING_MODE_EXCLUSIVE))
            lp  (.mallocLong stack 1)
            r   (VK10/vkCreateBuffer device bci nil lp)]
        (when (not= r VK10/VK_SUCCESS)
          (throw (RuntimeException. (str "vkCreateBuffer failed: " r))))
        (let [buf (long (.get lp 0))
              mr  (VkMemoryRequirements/malloc stack)
              _   (VK10/vkGetBufferMemoryRequirements device buf mr)
              mti (find-memory-type pd (.memoryTypeBits mr) memory-props)
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

(defn- upload-buffer!
  "Create a host-visible buffer and upload data into it."
  [device pd ^bytes data usage]
  (let [size  (long (alength data))
        buf   (create-buffer! device pd size usage
                              (bit-or VK10/VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                                      VK10/VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))
        stack (MemoryStack/stackPush)]
    (try
      (let [pp     (.mallocPointer stack 1)
            _      (VK10/vkMapMemory device (long (:memory buf)) 0 (long size) 0 pp)
            mapped (MemoryUtil/memByteBuffer (.get pp 0) (int size))]
        (.put mapped data)
        (VK10/vkUnmapMemory device (long (:memory buf))))
      (finally (MemoryStack/stackPop)))
    buf))

(defn- identity-matrix []
  (float-array [1 0 0 0
                0 1 0 0
                0 0 1 0
                0 0 0 1]))

;; ---------------------------------------------------------------------------
;; Renderable
;; ---------------------------------------------------------------------------

(defn- make-mesh-renderable [cfg _renderer]
  (let [obj-path   (or (:file cfg)  (throw (ex-info ":mesh renderable requires :file" {:cfg cfg})))
        def-color  (vec (or (:color cfg) [0.8 0.6 0.3 1.0]))
        pipeline-atom (atom nil)
        vbuf-atom     (atom nil)
        ibuf-atom     (atom nil)
        index-count   (atom 0)
        instances     (atom [])]
    (with-meta
      (reify renderable/Renderable
        (draw [_this command-buffer _device _render-pass _extent]
          (let [pl  @pipeline-atom
                vb  @vbuf-atom
                ib  @ibuf-atom
                ic  @index-count]
            (when (and pl vb ib (pos? ic))
              (let [{:keys [pipeline layout]} pl
                    pc-buf (doto (ByteBuffer/allocateDirect push-constant-bytes)
                             (.order ByteOrder/LITTLE_ENDIAN))
                    insts  (let [is @instances]
                             (if (seq is)
                               is
                               [{:model (identity-matrix)
                                 :vp    (identity-matrix)
                                 :color def-color}]))]
                (VK10/vkCmdBindPipeline
                 ^org.lwjgl.vulkan.VkCommandBuffer command-buffer
                 VK10/VK_PIPELINE_BIND_POINT_GRAPHICS
                 (long pipeline))
                (let [stack (MemoryStack/stackPush)
                      vbp   (doto (.mallocLong stack 1) (.put (:buffer vb)) (.flip))
                      offp  (doto (.mallocLong stack 1) (.put 0) (.flip))]
                  (VK10/vkCmdBindVertexBuffers command-buffer 0 vbp offp)
                  (VK10/vkCmdBindIndexBuffer command-buffer (long (:buffer ib)) 0
                                             VK10/VK_INDEX_TYPE_UINT32)
                  (MemoryStack/stackPop))
                (doseq [{:keys [model vp color]} insts]
                  (let [c (vec (or color def-color))
                        m (or model (identity-matrix))
                        v (or vp    (identity-matrix))]
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
                     pc-buf))
                  (VK10/vkCmdDrawIndexed
                   ^org.lwjgl.vulkan.VkCommandBuffer command-buffer
                   (int ic) 1 0 0 0))))))

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

(defn build-pipeline!
  "Load OBJ, upload vertex+index buffers, build pipeline."
  [renderable renderer]
  (let [{:keys [pipeline-atom vbuf-atom ibuf-atom index-count obj-path]} (meta renderable)
        ^org.lwjgl.vulkan.VkDevice device (renderer/get-device renderer)
        pd (.. device getPhysicalDevice)
        rp (renderer/get-render-pass renderer)
        mesh (obj/load-obj obj-path)]
    (log/info "mesh: loaded" obj-path
              "verts=" (:vertex-count mesh)
              "indices=" (:index-count mesh))
    ;; Upload vertex buffer
    (let [vdata (let [fa (:vertices mesh)
                      bb (ByteBuffer/allocateDirect (* (alength fa) 4))]
                  (.order bb ByteOrder/LITTLE_ENDIAN)
                  (doseq [f fa] (.putFloat bb (float f)))
                  (.array (.rewind bb)))]
      (reset! vbuf-atom (upload-buffer! device pd vdata VK10/VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)))
    ;; Upload index buffer
    (let [idata (let [ia (:indices mesh)
                      bb (ByteBuffer/allocateDirect (* (alength ia) 4))]
                  (.order bb ByteOrder/LITTLE_ENDIAN)
                  (doseq [i ia] (.putInt bb (int i)))
                  (.array (.rewind bb)))]
      (reset! ibuf-atom (upload-buffer! device pd idata VK10/VK_BUFFER_USAGE_INDEX_BUFFER_BIT)))
    (reset! index-count (:index-count mesh))
    ;; Build pipeline
    (let [pl (-> (pipeline/builder device rp)
                 (pipeline/vert-path "src/shaders/mesh.vert")
                 (pipeline/frag-path "src/shaders/mesh.frag")
                 (pipeline/topology :triangle-list)
                 (pipeline/cull-mode :back)
                 (pipeline/depth-test)
                 (pipeline/vertex-input vertex-stride
                                        [{:location 0 :format vk-fmt-rgb32f :offset 0}
                                         {:location 1 :format vk-fmt-rgb32f :offset 12}
                                         {:location 2 :format vk-fmt-rg32f  :offset 24}])
                 (pipeline/push-constant-size push-constant-bytes)
                 (pipeline/build!))]
      (reset! pipeline-atom pl))))

(defn instances
  "Return the instances atom for a mesh renderable.
   Reset to [{:model float-array :vp float-array :color [r g b a]}] each tick."
  [mesh-renderable]
  (:instances (meta mesh-renderable)))

(registry/register-renderable! :mesh make-mesh-renderable)
