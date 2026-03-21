(ns spock.texture
  "Image loading and Vulkan texture upload.

   Provides:
     (load-texture! renderer path) — loads image at path, returns texture map
     (destroy-texture! texture)    — frees all Vulkan resources

   Texture map: {:image long :image-view long :sampler long :memory long
                 :width int :height int :device VkDevice}"
  (:require [spock.log :as log])
  (:import [javax.imageio ImageIO]
           [java.io File]
           [java.awt.image BufferedImage]
           [org.lwjgl.system MemoryStack MemoryUtil]
           [org.lwjgl.vulkan
            VK10
            VkBufferCreateInfo
            VkMemoryRequirements
            VkMemoryAllocateInfo
            VkPhysicalDeviceMemoryProperties
            VkImageCreateInfo
            VkImageViewCreateInfo
            VkSamplerCreateInfo
            VkImageMemoryBarrier
            VkBufferImageCopy
            VkCommandBufferAllocateInfo
            VkCommandBufferBeginInfo
            VkSubmitInfo]
           [java.nio ByteOrder ByteBuffer]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:private VK_FORMAT_R8G8B8A8_SRGB 43)
(def ^:private VK_IMAGE_LAYOUT_UNDEFINED 0)
(def ^:private VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL 6)
(def ^:private VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL 5)
(def ^:private VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT 0x00000001)
(def ^:private VK_PIPELINE_STAGE_TRANSFER_BIT 0x00001000)
(def ^:private VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT 0x00000080)
(def ^:private VK_ACCESS_TRANSFER_WRITE_BIT 0x00001000)
(def ^:private VK_ACCESS_SHADER_READ_BIT 0x00000020)

;; ---------------------------------------------------------------------------
;; Memory helpers
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

;; ---------------------------------------------------------------------------
;; Staging buffer
;; ---------------------------------------------------------------------------

(defn- create-staging-buffer!
  "Create a host-visible/coherent staging buffer of the given size.
   Returns {:buffer long :memory long :device device}."
  [^org.lwjgl.vulkan.VkDevice device
   ^org.lwjgl.vulkan.VkPhysicalDevice physical-device
   size]
  (let [stack (MemoryStack/stackPush)]
    (try
      (let [bci (doto (VkBufferCreateInfo/calloc stack)
                  (.sType VK10/VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                  (.size (long size))
                  (.usage VK10/VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                  (.sharingMode VK10/VK_SHARING_MODE_EXCLUSIVE))
            lp  (.mallocLong stack 1)
            r   (VK10/vkCreateBuffer device bci nil lp)]
        (when (not= r VK10/VK_SUCCESS)
          (throw (RuntimeException. (str "vkCreateBuffer (staging) failed: " r))))
        (let [buf          (.get lp 0)
              mr           (VkMemoryRequirements/malloc stack)
              _            (VK10/vkGetBufferMemoryRequirements device buf mr)
              host-props   (bit-or VK10/VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                                   VK10/VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
              mem-type-idx (find-memory-type physical-device (.memoryTypeBits mr) host-props)
              mai          (doto (VkMemoryAllocateInfo/calloc stack)
                             (.sType VK10/VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                             (.allocationSize (.size mr))
                             (.memoryTypeIndex (int mem-type-idx)))]
          (.rewind lp)
          (let [r2 (VK10/vkAllocateMemory device mai nil lp)]
            (when (not= r2 VK10/VK_SUCCESS)
              (VK10/vkDestroyBuffer device buf nil)
              (throw (RuntimeException. (str "vkAllocateMemory (staging) failed: " r2))))
            (let [mem (.get lp 0)]
              (VK10/vkBindBufferMemory device buf mem 0)
              {:buffer buf :memory mem :device device}))))
      (finally
        (MemoryStack/stackPop)))))

;; ---------------------------------------------------------------------------
;; One-shot command buffer
;; ---------------------------------------------------------------------------

(defn- begin-one-shot!
  "Allocate and begin a one-shot command buffer. Returns VkCommandBuffer."
  [^org.lwjgl.vulkan.VkDevice device command-pool]
  (let [stack (MemoryStack/stackPush)]
    (try
      (let [ai (doto (VkCommandBufferAllocateInfo/calloc stack)
                 (.sType VK10/VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                 (.commandPool (long command-pool))
                 (.level VK10/VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                 (.commandBufferCount 1))
            pp (.mallocPointer stack 1)
            r  (VK10/vkAllocateCommandBuffers device ai pp)]
        (when (not= r VK10/VK_SUCCESS)
          (throw (RuntimeException. (str "vkAllocateCommandBuffers (one-shot) failed: " r))))
        (let [cb (org.lwjgl.vulkan.VkCommandBuffer. (.get pp 0) device)
              bi (doto (VkCommandBufferBeginInfo/calloc stack)
                   (.sType VK10/VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                   (.flags VK10/VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT))
              r2 (VK10/vkBeginCommandBuffer cb bi)]
          (when (not= r2 VK10/VK_SUCCESS)
            (throw (RuntimeException. (str "vkBeginCommandBuffer failed: " r2))))
          cb))
      (finally
        (MemoryStack/stackPop)))))

(defn- end-and-submit-one-shot!
  "End and submit a one-shot command buffer, then free it."
  [^org.lwjgl.vulkan.VkDevice device
   ^org.lwjgl.vulkan.VkCommandBuffer cb
   command-pool
   ^org.lwjgl.vulkan.VkQueue graphics-queue]
  (VK10/vkEndCommandBuffer cb)
  (let [stack (MemoryStack/stackPush)]
    (try
      (let [pp  (doto (.mallocPointer stack 1) (.put (.address cb)) (.flip))
            si  (doto (VkSubmitInfo/calloc stack)
                  (.sType VK10/VK_STRUCTURE_TYPE_SUBMIT_INFO)
                  (.pCommandBuffers pp))
            r   (VK10/vkQueueSubmit graphics-queue si VK10/VK_NULL_HANDLE)]
        (when (not= r VK10/VK_SUCCESS)
          (throw (RuntimeException. (str "vkQueueSubmit (one-shot) failed: " r))))
        (VK10/vkQueueWaitIdle graphics-queue)
        ;; Free the command buffer
        (let [lp (doto (.mallocPointer stack 1) (.put (.address cb)) (.flip))]
          (VK10/vkFreeCommandBuffers device (long command-pool) lp)))
      (finally
        (MemoryStack/stackPop)))))

;; ---------------------------------------------------------------------------
;; Image layout transition
;; ---------------------------------------------------------------------------

(defn- transition-image-layout!
  [^org.lwjgl.vulkan.VkDevice device
   command-pool
   ^org.lwjgl.vulkan.VkQueue graphics-queue
   image old-layout new-layout]
  (let [cb (begin-one-shot! device command-pool)
        stack (MemoryStack/stackPush)]
    (try
      (let [[src-stage dst-stage src-access dst-access]
            (cond
              (and (= old-layout VK_IMAGE_LAYOUT_UNDEFINED)
                   (= new-layout VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL))
              [VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
               VK_PIPELINE_STAGE_TRANSFER_BIT
               0
               VK_ACCESS_TRANSFER_WRITE_BIT]

              (and (= old-layout VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                   (= new-layout VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL))
              [VK_PIPELINE_STAGE_TRANSFER_BIT
               VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
               VK_ACCESS_TRANSFER_WRITE_BIT
               VK_ACCESS_SHADER_READ_BIT]

              :else (throw (RuntimeException.
                            (str "Unsupported layout transition: "
                                 old-layout " -> " new-layout))))
            barrier (doto (VkImageMemoryBarrier/calloc 1 ^MemoryStack stack)
                      (.get 0))]
        (.sType barrier VK10/VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
        (.oldLayout barrier (int old-layout))
        (.newLayout barrier (int new-layout))
        (.srcQueueFamilyIndex barrier VK10/VK_QUEUE_FAMILY_IGNORED)
        (.dstQueueFamilyIndex barrier VK10/VK_QUEUE_FAMILY_IGNORED)
        (.image barrier (long image))
        (let [sr (.subresourceRange barrier)]
          (.aspectMask sr VK10/VK_IMAGE_ASPECT_COLOR_BIT)
          (.baseMipLevel sr 0)
          (.levelCount sr 1)
          (.baseArrayLayer sr 0)
          (.layerCount sr 1))
        (.srcAccessMask barrier (int src-access))
        (.dstAccessMask barrier (int dst-access))
        (let [barriers (VkImageMemoryBarrier/create (.address barrier) 1)]
          (VK10/vkCmdPipelineBarrier
           cb
           (int src-stage) (int dst-stage)
           0
           nil nil
           barriers)))
      (finally
        (MemoryStack/stackPop)))
    (end-and-submit-one-shot! device cb command-pool graphics-queue)))

;; ---------------------------------------------------------------------------
;; Buffer → Image copy
;; ---------------------------------------------------------------------------

(defn- copy-buffer-to-image!
  [^org.lwjgl.vulkan.VkDevice device
   command-pool
   ^org.lwjgl.vulkan.VkQueue graphics-queue
   buffer image width height]
  (let [cb    (begin-one-shot! device command-pool)
        stack (MemoryStack/stackPush)]
    (try
      (let [region (.get (VkBufferImageCopy/calloc 1 ^MemoryStack stack) 0)]
        (.bufferOffset region 0)
        (.bufferRowLength region 0)
        (.bufferImageHeight region 0)
        (let [isl (.imageSubresource region)]
          (.aspectMask isl VK10/VK_IMAGE_ASPECT_COLOR_BIT)
          (.mipLevel isl 0)
          (.baseArrayLayer isl 0)
          (.layerCount isl 1))
        (let [io (.imageOffset region)]
          (.x io 0) (.y io 0) (.z io 0))
        (let [ie (.imageExtent region)]
          (.width ie (int width))
          (.height ie (int height))
          (.depth ie 1))
        (let [regions (VkBufferImageCopy/create (.address region) 1)]
          (VK10/vkCmdCopyBufferToImage
           cb
           (long buffer)
           (long image)
           VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
           regions)))
      (finally
        (MemoryStack/stackPop)))
    (end-and-submit-one-shot! device cb command-pool graphics-queue)))

;; ---------------------------------------------------------------------------
;; load-texture!
;; ---------------------------------------------------------------------------

(defn load-texture!
  "Load image at path and upload to a Vulkan device-local texture.
   renderer must be a VulkanRenderer (or any record with a :state atom).
   Returns {:image long :image-view long :sampler long :memory long
            :width int :height int :device VkDevice}."
  [renderer path]
  (log/log "texture/load-texture! path=" path)
  (let [state           (:state renderer)
        ^org.lwjgl.vulkan.VkDevice device  (:device @state)
        ^org.lwjgl.vulkan.VkPhysicalDevice pd (:physical-device @state)
        command-pool    (:command-pool @state)
        graphics-queue  (:graphics-queue @state)

        ;; 1. Load image pixels (ARGB → RGBA bytes)
        ^BufferedImage bi (ImageIO/read (File. ^String path))
        _  (when-not bi (throw (RuntimeException. (str "Failed to load image: " path))))
        w  (.getWidth bi)
        h  (.getHeight bi)
        argb (int-array (* w h))
        _  (.getRGB bi 0 0 w h argb 0 w)
        rgba-size (* w h 4)
        pixel-buf (ByteBuffer/allocateDirect rgba-size)]
    (.order pixel-buf ByteOrder/LITTLE_ENDIAN)
    (doseq [px argb]
      (.put pixel-buf (byte (bit-and (bit-shift-right px 16) 0xFF)))  ; R
      (.put pixel-buf (byte (bit-and (bit-shift-right px 8) 0xFF)))   ; G
      (.put pixel-buf (byte (bit-and px 0xFF)))                        ; B
      (.put pixel-buf (byte (bit-and (bit-shift-right px 24) 0xFF)))) ; A
    (.flip pixel-buf)

    ;; 2. Create staging buffer and upload pixels
    (let [staging (create-staging-buffer! device pd rgba-size)
          stack   (MemoryStack/stackPush)]
      (try
        (let [pp (.mallocPointer stack 1)
              _  (VK10/vkMapMemory device (:memory staging) 0 (long rgba-size) 0 pp)
              mapped (MemoryUtil/memByteBuffer (.get pp 0) rgba-size)]
          (.put mapped pixel-buf)
          (VK10/vkUnmapMemory device (:memory staging)))
        (finally
          (MemoryStack/stackPop)))

      ;; 3. Create VkImage (device-local)
      (let [stack2 (MemoryStack/stackPush)
            image-handle
            (try
              (let [ici (doto (VkImageCreateInfo/calloc stack2)
                          (.sType VK10/VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                          (.imageType VK10/VK_IMAGE_TYPE_2D)
                          (.format (int VK_FORMAT_R8G8B8A8_SRGB))
                          (.mipLevels 1)
                          (.arrayLayers 1)
                          (.samples VK10/VK_SAMPLE_COUNT_1_BIT)
                          (.tiling VK10/VK_IMAGE_TILING_OPTIMAL)
                          (.usage (bit-or VK10/VK_IMAGE_USAGE_TRANSFER_DST_BIT
                                          VK10/VK_IMAGE_USAGE_SAMPLED_BIT))
                          (.sharingMode VK10/VK_SHARING_MODE_EXCLUSIVE)
                          (.initialLayout (int VK_IMAGE_LAYOUT_UNDEFINED)))]
                (let [ext (.extent ici)]
                  (.width ext (int w))
                  (.height ext (int h))
                  (.depth ext 1))
                (let [lp (.mallocLong stack2 1)
                      r  (VK10/vkCreateImage device ici nil lp)]
                  (when (not= r VK10/VK_SUCCESS)
                    (throw (RuntimeException. (str "vkCreateImage failed: " r))))
                  (.get lp 0)))
              (finally
                (MemoryStack/stackPop)))

            ;; 4. Allocate device-local memory for image
            stack3 (MemoryStack/stackPush)
            image-memory
            (try
              (let [mr           (VkMemoryRequirements/malloc stack3)
                    _            (VK10/vkGetImageMemoryRequirements device image-handle mr)
                    mem-type-idx (find-memory-type pd (.memoryTypeBits mr)
                                                   VK10/VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)
                    mai          (doto (VkMemoryAllocateInfo/calloc stack3)
                                   (.sType VK10/VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                                   (.allocationSize (.size mr))
                                   (.memoryTypeIndex (int mem-type-idx)))
                    lp           (.mallocLong stack3 1)
                    r2           (VK10/vkAllocateMemory device mai nil lp)]
                (when (not= r2 VK10/VK_SUCCESS)
                  (VK10/vkDestroyImage device image-handle nil)
                  (throw (RuntimeException. (str "vkAllocateMemory (image) failed: " r2))))
                (let [mem (.get lp 0)]
                  (VK10/vkBindImageMemory device image-handle mem 0)
                  mem))
              (finally
                (MemoryStack/stackPop)))]

        ;; 5. Transition UNDEFINED → TRANSFER_DST_OPTIMAL
        (transition-image-layout!
         device command-pool graphics-queue
         image-handle
         VK_IMAGE_LAYOUT_UNDEFINED
         VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)

        ;; 6. Copy staging buffer to image
        (copy-buffer-to-image!
         device command-pool graphics-queue
         (:buffer staging) image-handle w h)

        ;; 7. Transition TRANSFER_DST_OPTIMAL → SHADER_READ_ONLY_OPTIMAL
        (transition-image-layout!
         device command-pool graphics-queue
         image-handle
         VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
         VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)

        ;; 8. Create VkImageView
        (let [stack4    (MemoryStack/stackPush)
              image-view
              (try
                (let [ivci (doto (VkImageViewCreateInfo/calloc stack4)
                             (.sType VK10/VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                             (.image image-handle)
                             (.viewType VK10/VK_IMAGE_VIEW_TYPE_2D)
                             (.format (int VK_FORMAT_R8G8B8A8_SRGB)))
                      sr   (.subresourceRange ivci)]
                  (.aspectMask sr VK10/VK_IMAGE_ASPECT_COLOR_BIT)
                  (.baseMipLevel sr 0)
                  (.levelCount sr 1)
                  (.baseArrayLayer sr 0)
                  (.layerCount sr 1)
                  (let [lp (.mallocLong stack4 1)
                        r  (VK10/vkCreateImageView device ivci nil lp)]
                    (when (not= r VK10/VK_SUCCESS)
                      (throw (RuntimeException. (str "vkCreateImageView failed: " r))))
                    (.get lp 0)))
                (finally
                  (MemoryStack/stackPop)))

              ;; 9. Create VkSampler
              stack5  (MemoryStack/stackPush)
              sampler
              (try
                (let [sci (doto (VkSamplerCreateInfo/calloc stack5)
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
                      lp  (.mallocLong stack5 1)
                      r   (VK10/vkCreateSampler device sci nil lp)]
                  (when (not= r VK10/VK_SUCCESS)
                    (throw (RuntimeException. (str "vkCreateSampler failed: " r))))
                  (.get lp 0))
                (finally
                  (MemoryStack/stackPop)))]

          ;; 10. Destroy staging buffer + memory
          (VK10/vkDestroyBuffer device (long (:buffer staging)) nil)
          (VK10/vkFreeMemory device (long (:memory staging)) nil)

          (log/log "texture/load-texture! OK image=" image-handle
                   "view=" image-view "sampler=" sampler)
          {:image      image-handle
           :image-view image-view
           :sampler    sampler
           :memory     image-memory
           :width      w
           :height     h
           :device     device})))))

;; ---------------------------------------------------------------------------
;; destroy-texture!
;; ---------------------------------------------------------------------------

(defn destroy-texture!
  "Free all Vulkan resources held by a texture map."
  [{:keys [^org.lwjgl.vulkan.VkDevice device image image-view sampler memory]}]
  (when device
    (when sampler    (VK10/vkDestroySampler   device (long sampler)    nil))
    (when image-view (VK10/vkDestroyImageView device (long image-view) nil))
    (when image      (VK10/vkDestroyImage     device (long image)      nil))
    (when memory     (VK10/vkFreeMemory       device (long memory)     nil))))
