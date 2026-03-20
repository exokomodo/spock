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
   Returns {:image :image-memory :image-view :sampler :width :height :device}."
  [path renderer]
  (require 'spock.renderer.core)
  (let [get-device         (resolve 'spock.renderer.core/get-device)
        get-command-pool   (resolve 'spock.renderer.core/get-command-pool)
        get-graphics-queue (resolve 'spock.renderer.core/get-graphics-queue)
        ^VkDevice device       (get-device renderer)
        ^long command-pool     (get-command-pool renderer)
        ^VkQueue gfx-queue     (get-graphics-queue renderer)
        ^VkPhysicalDevice pd   (.getPhysicalDevice device)]
    (log/info "load-texture! loading:" path)
    (let [^BufferedImage bi (ImageIO/read (File. ^String path))]
      (when-not bi (throw (RuntimeException. (str "Failed to load image: " path))))
      (let [width    (.getWidth bi)
            height   (.getHeight bi)
            img-size (int (* width height 4))
            ;; Convert to RGBA via Java2D
            rgba-img (doto (BufferedImage. width height BufferedImage/TYPE_INT_ARGB)
                       (-> .getGraphics (doto (.drawImage bi 0 0 nil) .dispose)))
            pixels   (int-array (* width height))
            _        (.getRGB rgba-img 0 0 width height pixels 0 width)
            ;; Build RGBA byte array
            rgb-arr  (byte-array img-size)
            _        (let [n (alength pixels)]
                       (loop [i 0 j 0]
                         (when (< i n)
                           (let [px (aget pixels i)]
                             (aset rgb-arr j       (byte (bit-and (bit-shift-right px 16) 0xFF)))
                             (aset rgb-arr (+ j 1) (byte (bit-and (bit-shift-right px  8) 0xFF)))
                             (aset rgb-arr (+ j 2) (byte (bit-and px 0xFF)))
                             (aset rgb-arr (+ j 3) (byte (bit-and (unsigned-bit-shift-right px 24) 0xFF))))
                           (recur (inc i) (+ j 4)))))
            ;; Staging buffer
            staging-host-props (bit-or VK10/VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                                       VK10/VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
            sb       (create-buffer! device pd img-size
                                     VK10/VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                                     staging-host-props)
            stg-buf  (:buffer sb)
            stg-mem  (:memory sb)
            ;; Map staging memory and copy pixels
            _        (let [stack (MemoryStack/stackPush)]
                       (try
                         (let [pp  (.mallocPointer stack 1)
                               _   (VK10/vkMapMemory device stg-mem 0 (long img-size) 0 pp)
                               dst (MemoryUtil/memByteBuffer (.get pp 0) img-size)]
                           (.put dst rgb-arr)
                           (VK10/vkUnmapMemory device stg-mem))
                         (finally (MemoryStack/stackPop))))
            ;; Device-local image
            VK_FORMAT_R8G8B8A8_SRGB 43
            img-map  (create-image! device pd width height
                                    VK_FORMAT_R8G8B8A8_SRGB
                                    VK10/VK_IMAGE_TILING_OPTIMAL
                                    (bit-or VK10/VK_IMAGE_USAGE_TRANSFER_DST_BIT
                                            VK10/VK_IMAGE_USAGE_SAMPLED_BIT)
                                    VK10/VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)
            image     (:image img-map)
            img-mem   (:image-memory img-map)
            cb        (begin-single-time-commands! device command-pool)]
        ;; Record: UNDEFINED → TRANSFER_DST, copy, TRANSFER_DST → SHADER_READ
        (transition-image-layout! cb image
                                  VK10/VK_IMAGE_LAYOUT_UNDEFINED
                                  VK10/VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
        (copy-buffer-to-image! cb stg-buf image width height)
        (transition-image-layout! cb image
                                  VK10/VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                                  VK10/VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
        (end-single-time-commands! device command-pool cb gfx-queue)
        ;; Free staging
        (VK10/vkDestroyBuffer device stg-buf nil)
        (VK10/vkFreeMemory    device stg-mem nil)
        ;; Image view
        (let [VK_IMAGE_ASPECT_COLOR_BIT 1
              ivc (doto (VkImageViewCreateInfo/calloc (MemoryStack/stackGet))
                    (.sType VK10/VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    (.image (long image))
                    (.viewType VK10/VK_IMAGE_VIEW_TYPE_2D)
                    (.format (int VK_FORMAT_R8G8B8A8_SRGB))
                    (-> .subresourceRange
                        (doto (.aspectMask VK_IMAGE_ASPECT_COLOR_BIT)
                          (.baseMipLevel 0)
                          (.levelCount 1)
                          (.baseArrayLayer 0)
                          (.layerCount 1))))
              stack  (MemoryStack/stackPush)
              lp     (.mallocLong stack 1)
              r      (VK10/vkCreateImageView device ivc nil lp)]
          (when (not= r VK10/VK_SUCCESS)
            (throw (RuntimeException. (str "vkCreateImageView failed: " r))))
          (let [image-view (.get lp 0)
                ;; Sampler
                sc  (doto (VkSamplerCreateInfo/calloc stack)
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
                      (.mipmapMode VK10/VK_SAMPLER_MIPMAP_MODE_LINEAR))
                r2  (VK10/vkCreateSampler device sc nil lp)]
            (when (not= r2 VK10/VK_SUCCESS)
              (throw (RuntimeException. (str "vkCreateSampler failed: " r2))))
            (MemoryStack/stackPop)
            (log/info "load-texture! done:" path width "x" height)
            {:image        (long image)
             :image-memory (long img-mem)
             :image-view   (long image-view)
             :sampler      (.get lp 0)
             :width        width
             :height       height
             :device       device}))))))

(defn destroy-texture!
  "Free all Vulkan resources associated with a texture map."
  [{:keys [^VkDevice device image image-memory image-view sampler]}]
  (when device
    (when sampler    (VK10/vkDestroySampler   device (long sampler)    nil))
    (when image-view (VK10/vkDestroyImageView device (long image-view) nil))
    (when image      (VK10/vkDestroyImage     device (long image)      nil))
    (when image-memory (VK10/vkFreeMemory     device (long image-memory) nil))))
