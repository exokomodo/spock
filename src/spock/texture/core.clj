(ns spock.texture.core
  "Vulkan texture loading and management.

   load-texture!   — load a PNG/JPEG from disk, upload to device-local VkImage,
                     create VkImageView and VkSampler.
                     Returns a texture record:
                       {:image       long
                        :image-memory long
                        :image-view  long
                        :sampler     long
                        :width       int
                        :height      int
                        :device      VkDevice}

   destroy-texture! — free all Vulkan resources for a texture record."
  (:require [spock.log :as log])
  (:import [javax.imageio ImageIO]
           [java.io File]
           [java.awt.image BufferedImage]
           [org.lwjgl.system MemoryStack MemoryUtil]
           [org.lwjgl.vulkan
            VK10
            VkDevice
            VkPhysicalDevice
            VkQueue
            VkCommandBuffer
            VkBufferCreateInfo
            VkMemoryRequirements
            VkMemoryAllocateInfo
            VkPhysicalDeviceMemoryProperties
            VkImageCreateInfo
            VkImageViewCreateInfo
            VkSamplerCreateInfo
            VkCommandBufferAllocateInfo
            VkCommandBufferBeginInfo
            VkSubmitInfo
            VkBufferImageCopy
            VkImageMemoryBarrier
            VkImageSubresourceLayers
            VkImageSubresourceRange]))

;; ---------------------------------------------------------------------------
;; Memory helpers
;; ---------------------------------------------------------------------------

(defn- find-memory-type
  [^VkPhysicalDevice physical-device type-filter required-props]
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

;; ---------------------------------------------------------------------------
;; Single-time command buffer helpers
;; ---------------------------------------------------------------------------

(defn- begin-single-time-commands!
  "Allocate and begin a one-shot command buffer from pool."
  [^VkDevice device ^long command-pool]
  (let [stack (MemoryStack/stackPush)]
    (try
      (let [ai (doto (VkCommandBufferAllocateInfo/calloc stack)
                 (.sType VK10/VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                 (.level VK10/VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                 (.commandPool command-pool)
                 (.commandBufferCount 1))
            pp (.mallocPointer stack 1)
            r  (VK10/vkAllocateCommandBuffers device ai pp)]
        (when (not= r VK10/VK_SUCCESS)
          (throw (RuntimeException. (str "vkAllocateCommandBuffers (single) failed: " r))))
        (let [cb (VkCommandBuffer. (.get pp 0) device)
              bi (doto (VkCommandBufferBeginInfo/calloc stack)
                   (.sType VK10/VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                   (.flags VK10/VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT))]
          (VK10/vkBeginCommandBuffer cb bi)
          cb))
      (finally
        (MemoryStack/stackPop)))))

(defn- end-single-time-commands!
  "End, submit, wait for, and free a one-shot command buffer."
  [^VkDevice device ^long command-pool ^VkCommandBuffer cb ^VkQueue graphics-queue]
  (VK10/vkEndCommandBuffer cb)
  (let [stack (MemoryStack/stackPush)]
    (try
      (let [cbp (doto (.mallocPointer stack 1)
                  (.put (.address cb))
                  (.flip))
            si  (doto (VkSubmitInfo/calloc stack)
                  (.sType VK10/VK_STRUCTURE_TYPE_SUBMIT_INFO)
                  (.pCommandBuffers cbp))]
        (VK10/vkQueueSubmit graphics-queue si VK10/VK_NULL_HANDLE)
        (VK10/vkQueueWaitIdle graphics-queue))
      (finally
        (MemoryStack/stackPop))))
  (let [stack (MemoryStack/stackPush)]
    (try
      (let [cbp (doto (.mallocPointer stack 1)
                  (.put (.address cb))
                  (.flip))]
        (VK10/vkFreeCommandBuffers device command-pool cbp))
      (finally
        (MemoryStack/stackPop)))))

;; ---------------------------------------------------------------------------
;; Buffer helpers
;; ---------------------------------------------------------------------------

(defn- create-buffer!
  "Create a VkBuffer with the given usage and memory properties.
   Returns {:buffer long :memory long}."
  [^VkDevice device ^VkPhysicalDevice physical-device size usage mem-props]
  (let [stack (MemoryStack/stackPush)]
    (try
      (let [bci (doto (VkBufferCreateInfo/calloc stack)
                  (.sType VK10/VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                  (.size (long size))
                  (.usage (int usage))
                  (.sharingMode VK10/VK_SHARING_MODE_EXCLUSIVE))
            lp  (.mallocLong stack 1)
            r   (VK10/vkCreateBuffer device bci nil lp)]
        (when (not= r VK10/VK_SUCCESS)
          (throw (RuntimeException. (str "vkCreateBuffer failed: " r))))
        (let [buf (long (.get lp 0))
              mr  (VkMemoryRequirements/malloc stack)
              _   (VK10/vkGetBufferMemoryRequirements device buf mr)
              mti (find-memory-type physical-device (.memoryTypeBits mr) (int mem-props))
              mai (doto (VkMemoryAllocateInfo/calloc stack)
                    (.sType VK10/VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    (.allocationSize (.size mr))
                    (.memoryTypeIndex (int mti)))]
          (.rewind lp)
          (let [r2 (VK10/vkAllocateMemory device mai nil lp)]
            (when (not= r2 VK10/VK_SUCCESS)
              (VK10/vkDestroyBuffer device buf nil)
              (throw (RuntimeException. (str "vkAllocateMemory (buffer) failed: " r2))))
            (let [mem (long (.get lp 0))]
              (VK10/vkBindBufferMemory device buf mem 0)
              {:buffer buf :memory mem}))))
      (finally
        (MemoryStack/stackPop)))))

;; ---------------------------------------------------------------------------
;; Image helpers
;; ---------------------------------------------------------------------------

(defn- create-image!
  "Create a VkImage and allocate device-local memory for it.
   Returns {:image long :memory long}."
  [^VkDevice device ^VkPhysicalDevice physical-device width height format tiling usage mem-props]
  (let [stack (MemoryStack/stackPush)]
    (try
      (let [ici (doto (VkImageCreateInfo/calloc stack)
                  (.sType VK10/VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                  (.imageType VK10/VK_IMAGE_TYPE_2D)
                  (.mipLevels 1)
                  (.arrayLayers 1)
                  (.format (int format))
                  (.tiling (int tiling))
                  (.initialLayout VK10/VK_IMAGE_LAYOUT_UNDEFINED)
                  (.usage (int usage))
                  (.sharingMode VK10/VK_SHARING_MODE_EXCLUSIVE)
                  (.samples VK10/VK_SAMPLE_COUNT_1_BIT))]
        (doto (.extent ici)
          (.width (int width))
          (.height (int height))
          (.depth 1))
        (let [lp (.mallocLong stack 1)
              r  (VK10/vkCreateImage device ici nil lp)]
          (when (not= r VK10/VK_SUCCESS)
            (throw (RuntimeException. (str "vkCreateImage failed: " r))))
          (let [img (long (.get lp 0))
                mr  (VkMemoryRequirements/malloc stack)
                _   (VK10/vkGetImageMemoryRequirements device img mr)
                mti (find-memory-type physical-device (.memoryTypeBits mr) (int mem-props))
                mai (doto (VkMemoryAllocateInfo/calloc stack)
                      (.sType VK10/VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                      (.allocationSize (.size mr))
                      (.memoryTypeIndex (int mti)))]
            (.rewind lp)
            (let [r2 (VK10/vkAllocateMemory device mai nil lp)]
              (when (not= r2 VK10/VK_SUCCESS)
                (VK10/vkDestroyImage device img nil)
                (throw (RuntimeException. (str "vkAllocateMemory (image) failed: " r2))))
              (let [mem (long (.get lp 0))]
                (VK10/vkBindImageMemory device img mem 0)
                {:image img :memory mem})))))
      (finally
        (MemoryStack/stackPop)))))

(defn- transition-image-layout!
  "Record a pipeline barrier to transition image layout."
  [^VkCommandBuffer cb ^long image old-layout new-layout]
  (let [stack (MemoryStack/stackPush)]
    (try
      (let [barrier (doto (VkImageMemoryBarrier/calloc 1 stack)
                      (-> (.get 0)
                          (doto
                           (.sType VK10/VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                            (.oldLayout (int old-layout))
                            (.newLayout (int new-layout))
                            (.srcQueueFamilyIndex VK10/VK_QUEUE_FAMILY_IGNORED)
                            (.dstQueueFamilyIndex VK10/VK_QUEUE_FAMILY_IGNORED)
                            (.image image))))
            b0 (.get barrier 0)]
        (doto (.subresourceRange b0)
          (.aspectMask VK10/VK_IMAGE_ASPECT_COLOR_BIT)
          (.baseMipLevel 0)
          (.levelCount 1)
          (.baseArrayLayer 0)
          (.layerCount 1))
        ;; Determine access masks and pipeline stages based on layout transition
        (let [[src-access dst-access src-stage dst-stage]
              (cond
                (and (= old-layout VK10/VK_IMAGE_LAYOUT_UNDEFINED)
                     (= new-layout VK10/VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL))
                [0
                 VK10/VK_ACCESS_TRANSFER_WRITE_BIT
                 VK10/VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
                 VK10/VK_PIPELINE_STAGE_TRANSFER_BIT]

                (and (= old-layout VK10/VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                     (= new-layout VK10/VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL))
                [VK10/VK_ACCESS_TRANSFER_WRITE_BIT
                 VK10/VK_ACCESS_SHADER_READ_BIT
                 VK10/VK_PIPELINE_STAGE_TRANSFER_BIT
                 VK10/VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT]

                :else
                (throw (RuntimeException.
                        (str "Unsupported layout transition: " old-layout " -> " new-layout))))]
          (.srcAccessMask b0 (int src-access))
          (.dstAccessMask b0 (int dst-access))
          (VK10/vkCmdPipelineBarrier cb
                                     (int src-stage) (int dst-stage)
                                     0 nil nil barrier)))
      (finally
        (MemoryStack/stackPop)))))

(defn- copy-buffer-to-image!
  "Record a vkCmdCopyBufferToImage command."
  [^VkCommandBuffer cb src-buffer dst-image width height]
  (let [stack (MemoryStack/stackPush)]
    (try
      (let [region (doto (VkBufferImageCopy/calloc 1 stack)
                     (-> (.get 0)
                         (doto
                          (.bufferOffset 0)
                           (.bufferRowLength 0)
                           (.bufferImageHeight 0))))]
        (let [r0 (.get region 0)]
          (doto (.imageSubresource r0)
            (.aspectMask VK10/VK_IMAGE_ASPECT_COLOR_BIT)
            (.mipLevel 0)
            (.baseArrayLayer 0)
            (.layerCount 1))
          (doto (.imageOffset r0)
            (.x 0) (.y 0) (.z 0))
          (doto (.imageExtent r0)
            (.width (int width))
            (.height (int height))
            (.depth 1)))
        (VK10/vkCmdCopyBufferToImage
         cb src-buffer dst-image
         VK10/VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
         region))
      (finally
        (MemoryStack/stackPop)))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn load-texture!
  "Load an image from path and upload it as a device-local VkImage texture.
   renderer must implement spock.renderer.core/Renderer (get-device,
   get-command-pool, get-graphics-queue).
   Returns a texture map:
     {:image        long
      :image-memory long
      :image-view   long
      :sampler      long
      :width        int
      :height       int
      :device       VkDevice}"
  [path renderer]
  (require 'spock.renderer.core)
  (let [get-device        (resolve 'spock.renderer.core/get-device)
        get-command-pool  (resolve 'spock.renderer.core/get-command-pool)
        get-graphics-queue (resolve 'spock.renderer.core/get-graphics-queue)
        ^VkDevice device      (get-device renderer)
        ^long command-pool    (get-command-pool renderer)
        ^VkQueue gfx-queue    (get-graphics-queue renderer)
        ^VkPhysicalDevice pd  (.getPhysicalDevice device)]

    (log/info "load-texture! loading:" path)
    (println "[texture] A - before ImageIO/read")
    (let [^BufferedImage bi (ImageIO/read (File. ^String path))]
      (println "[texture] B - image read, nil?" (nil? bi))
      (when-not bi (throw (RuntimeException. (str "Failed to load image: " path))))
      (println "[texture] C - starting pixel conversion"))
    ;; --- Load image pixels via Java2D ---
    (let [^BufferedImage bi (ImageIO/read (File. ^String path))]
      (when-not bi
        (throw (RuntimeException. (str "Failed to load image: " path))))
      (let [width  (.getWidth bi)
            height (.getHeight bi)
            ;; Convert to RGBA (4 bytes/pixel) regardless of source format
            rgba-img (doto (BufferedImage. width height BufferedImage/TYPE_INT_ARGB)
                       (-> .getGraphics
                           (doto (.drawImage bi 0 0 nil)
                             (.dispose))))
            img-size (* width height 4)

            ;; Extract ARGB int array and convert to RGBA byte order for Vulkan
            pixels    (int-array (* width height))
            _         (.getRGB rgba-img 0 0 width height pixels 0 width)
            _         (println "[texture] D - got" (alength pixels) "pixels, allocating" img-size "bytes")
            pixel-buf (MemoryUtil/memAlloc (int img-size))
            _         (let [n (alength pixels)]
                        (loop [i 0]
                          (when (< i n)
                            (let [px (aget pixels i)
                                  r  (bit-and (bit-shift-right px 16) 0xFF)
                                  g  (bit-and (bit-shift-right px  8) 0xFF)
                                  b  (bit-and px 0xFF)
                                  a  (bit-and (unsigned-bit-shift-right px 24) 0xFF)]
                              (.put pixel-buf (byte r))
                              (.put pixel-buf (byte g))
                              (.put pixel-buf (byte b))
                              (.put pixel-buf (byte a)))
                            (recur (inc i))))
                        (.flip pixel-buf)
                        (println "[texture] E - pixel conversion done"))

            ;; --- Staging buffer ---
            staging-host-props (bit-or VK10/VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                                       VK10/VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
            staging-usage      (bit-or VK10/VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
            _         (println "[texture] F - creating staging buffer")
            {:keys [buffer staging-memory]}
            (let [sb (create-buffer! device pd img-size staging-usage staging-host-props)]
              (println "[texture] G - staging buffer created")
              {:buffer (:buffer sb) :staging-memory (:memory sb)})

            ;; Map and copy pixel data into staging buffer
            _  (let [stack (MemoryStack/stackPush)]
                 (try
                   (let [pp (.mallocPointer stack 1)]
                     (VK10/vkMapMemory device staging-memory 0 (long img-size) 0 pp)
                     (let [dst (MemoryUtil/memByteBuffer (.get pp 0) (int img-size))]
                       (.rewind pixel-buf)
                       (.put dst pixel-buf))
                     (VK10/vkUnmapMemory device staging-memory))
                   (finally
                     (MemoryStack/stackPop))))
            _  (do (println "[texture] H - map/copy done") (MemoryUtil/memFree pixel-buf))

            ;; --- Device-local image ---
            VK_FORMAT_R8G8B8A8_SRGB 43
            image-usage (bit-or VK10/VK_IMAGE_USAGE_TRANSFER_DST_BIT
                                VK10/VK_IMAGE_USAGE_SAMPLED_BIT)
            _           (println "[texture] I - creating device image")
            {:keys [image image-memory]}
            (create-image! device pd width height
                           VK_FORMAT_R8G8B8A8_SRGB
                           VK10/VK_IMAGE_TILING_OPTIMAL
                           image-usage
                           VK10/VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)
            _           (println "[texture] J - image created, beginning cmd buffer")

            ;; --- Upload via one-shot command buffer ---
            cb (begin-single-time-commands! device command-pool)]

        (println "[texture] K - recording transitions")
        ;; Transition: UNDEFINED → TRANSFER_DST_OPTIMAL
        (transition-image-layout! cb image
                                  VK10/VK_IMAGE_LAYOUT_UNDEFINED
                                  VK10/VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
        (println "[texture] L - copy buffer to image")
        ;; Copy buffer → image
        (copy-buffer-to-image! cb buffer image width height)
        (println "[texture] M - final transition")
        ;; Transition: TRANSFER_DST_OPTIMAL → SHADER_READ_ONLY_OPTIMAL
        (transition-image-layout! cb image
                                  VK10/VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                                  VK10/VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)

        (println "[texture] N - submitting queue")
        (end-single-time-commands! device command-pool cb gfx-queue)
        (println "[texture] O - done")

        ;; Free staging buffer
        (VK10/vkDestroyBuffer device buffer nil)
        (VK10/vkFreeMemory    device staging-memory nil)

        ;; --- Image view ---
        (let [VK_FORMAT_R8G8B8A8_SRGB 43
              image-view
              (let [stack (MemoryStack/stackPush)]
                (try
                  (let [vci (doto (VkImageViewCreateInfo/calloc stack)
                              (.sType VK10/VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                              (.image image)
                              (.viewType VK10/VK_IMAGE_VIEW_TYPE_2D)
                              (.format VK_FORMAT_R8G8B8A8_SRGB))
                        _   (doto (.components vci)
                              (.r VK10/VK_COMPONENT_SWIZZLE_IDENTITY)
                              (.g VK10/VK_COMPONENT_SWIZZLE_IDENTITY)
                              (.b VK10/VK_COMPONENT_SWIZZLE_IDENTITY)
                              (.a VK10/VK_COMPONENT_SWIZZLE_IDENTITY))
                        _   (doto (.subresourceRange vci)
                              (.aspectMask VK10/VK_IMAGE_ASPECT_COLOR_BIT)
                              (.baseMipLevel 0)
                              (.levelCount 1)
                              (.baseArrayLayer 0)
                              (.layerCount 1))
                        lp  (.mallocLong stack 1)
                        r   (VK10/vkCreateImageView device vci nil lp)]
                    (when (not= r VK10/VK_SUCCESS)
                      (throw (RuntimeException. (str "vkCreateImageView (texture) failed: " r))))
                    (.get lp 0))
                  (finally
                    (MemoryStack/stackPop))))

              ;; --- Sampler ---
              sampler
              (let [stack (MemoryStack/stackPush)]
                (try
                  (let [sci (doto (VkSamplerCreateInfo/calloc stack)
                              (.sType VK10/VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                              (.magFilter VK10/VK_FILTER_LINEAR)
                              (.minFilter VK10/VK_FILTER_LINEAR)
                              (.addressModeU VK10/VK_SAMPLER_ADDRESS_MODE_REPEAT)
                              (.addressModeV VK10/VK_SAMPLER_ADDRESS_MODE_REPEAT)
                              (.addressModeW VK10/VK_SAMPLER_ADDRESS_MODE_REPEAT)
                              (.anisotropyEnable false)
                              (.maxAnisotropy 1.0)
                              (.borderColor VK10/VK_BORDER_COLOR_INT_OPAQUE_BLACK)
                              (.unnormalizedCoordinates false)
                              (.compareEnable false)
                              (.compareOp VK10/VK_COMPARE_OP_ALWAYS)
                              (.mipmapMode VK10/VK_SAMPLER_MIPMAP_MODE_LINEAR)
                              (.mipLodBias 0.0)
                              (.minLod 0.0)
                              (.maxLod 0.0))
                        lp  (.mallocLong stack 1)
                        r   (VK10/vkCreateSampler device sci nil lp)]
                    (when (not= r VK10/VK_SUCCESS)
                      (throw (RuntimeException. (str "vkCreateSampler failed: " r))))
                    (.get lp 0))
                  (finally
                    (MemoryStack/stackPop))))]

          (log/info "load-texture! OK width=" width "height=" height)
          {:image        image
           :image-memory image-memory
           :image-view   image-view
           :sampler      sampler
           :width        width
           :height       height
           :device       device})))))

(defn destroy-texture!
  "Free all Vulkan resources associated with a texture map."
  [{:keys [^VkDevice device image image-memory image-view sampler]}]
  (when device
    (when sampler    (VK10/vkDestroySampler   device (long sampler)    nil))
    (when image-view (VK10/vkDestroyImageView device (long image-view) nil))
    (when image      (VK10/vkDestroyImage     device (long image)      nil))
    (when image-memory (VK10/vkFreeMemory     device (long image-memory) nil))))
