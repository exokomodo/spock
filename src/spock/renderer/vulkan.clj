(ns spock.renderer.vulkan
  "Vulkan backend implementing spock.renderer.core/Renderer."
  (:require [spock.renderer.core :as renderer]
            [spock.renderable.core :as renderable])
  (:import [org.lwjgl.system MemoryStack MemoryUtil]
           [org.lwjgl PointerBuffer]
           [org.lwjgl.vulkan
            VK13 VK10
            VkInstance VkInstanceCreateInfo VkApplicationInfo
            VkPhysicalDevice VkPhysicalDeviceFeatures VkQueueFamilyProperties
            VkDeviceCreateInfo VkDeviceQueueCreateInfo
            VkDevice VkQueue
            VkSurfaceCapabilitiesKHR VkSurfaceFormatKHR
            VkSwapchainCreateInfoKHR
            VkImageViewCreateInfo
            VkImageCreateInfo VkMemoryAllocateInfo VkMemoryRequirements
            VkPhysicalDeviceMemoryProperties
            VkImageMemoryBarrier
            VkImageBlit VkImageSubresourceLayers VkOffset3D
            VkRenderPassCreateInfo
            VkAttachmentDescription VkAttachmentReference
            VkSubpassDescription VkSubpassDependency
            VkFramebufferCreateInfo
            VkCommandPoolCreateInfo VkCommandBufferAllocateInfo VkCommandBuffer
            VkCommandBufferBeginInfo VkRenderPassBeginInfo
            VkSemaphoreCreateInfo VkFenceCreateInfo
            VkSubmitInfo VkPresentInfoKHR
            VkClearValue VkOffset2D VkExtent2D VkViewport VkRect2D
            KHRSurface KHRSwapchain]
           [org.lwjgl.glfw GLFW GLFWVulkan]
           [org.jcodec.api.awt AWTSequenceEncoder]
           [java.awt.image BufferedImage]
           [java.io File])
)

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------
(def ^:private MAX-FRAMES 2)
(def ^:private VK_NULL 0)
(def ^:private DEVICE-EXTENSIONS ["VK_KHR_swapchain"])

;; VkPresentModeKHR values (not exposed as constants in LWJGL 3.3.4)
(def ^:private VK_PRESENT_MODE_IMMEDIATE_KHR 0)
(def ^:private VK_PRESENT_MODE_MAILBOX_KHR   1)
(def ^:private VK_PRESENT_MODE_FIFO_KHR      2)

;; ---------------------------------------------------------------------------
;; Initial state
;; ---------------------------------------------------------------------------
(def ^:private initial-state
  {:instance         VK_NULL   ; VkInstance
   :surface          VK_NULL   ; long handle
   :physical-device  nil       ; VkPhysicalDevice
   :device           nil       ; VkDevice
   :graphics-queue   nil       ; VkQueue
   :present-queue    nil       ; VkQueue
   :graphics-family  nil       ; int
   :present-family   nil       ; int
   :swapchain        VK_NULL   ; long handle
   :swapchain-images []        ; [long]
   :swapchain-views  []        ; [long]
   :swapchain-format nil       ; int
   :swapchain-extent nil       ; {:width int :height int}
   :render-pass      VK_NULL   ; long handle
   :framebuffers     []        ; [long]
   :command-pool     VK_NULL   ; long handle
   :command-buffers  []        ; [VkCommandBuffer]
   :image-available  []        ; [long semaphore]
   :render-finished  []        ; [long semaphore]
   :in-flight-fences []        ; [long fence]
   :current-frame    0
   :clear-color      [0.1 0.12 0.18 1.0]
   :window-handle    nil       ; long (GLFW window)
   :width            1280
   :height           720
   :title            "Spock"
   ;; Recording state (nil when not recording)
   :recording        nil})

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------
(defmacro ^:private vk-check [expr msg]
  `(let [r# ~expr]
     (when (not= r# VK10/VK_SUCCESS)
       (throw (RuntimeException. (str ~msg " (VkResult=" r# ")"))))
     r#))

(defn- utf8 [^MemoryStack stack ^String s]
  (.UTF8 stack s true))

(defn- strings->pp
  "Allocate a PointerBuffer on stack populated with null-terminated UTF-8 strings."
  [^MemoryStack stack strings]
  (let [^PointerBuffer buf (.mallocPointer stack (count strings))]
    (doseq [^String s strings]
      (.put buf ^java.nio.ByteBuffer (utf8 stack s)))
    (.flip buf)
    buf))

;; ---------------------------------------------------------------------------
;; Instance
;; ---------------------------------------------------------------------------
(defn- create-instance! [state]
  (let [^MemoryStack stack (MemoryStack/stackPush)
        app  (doto (VkApplicationInfo/calloc stack)
               (.sType VK10/VK_STRUCTURE_TYPE_APPLICATION_INFO)
               (.pApplicationName (utf8 stack ^String (:title @state)))
               (.applicationVersion (VK10/VK_MAKE_VERSION 1 0 0))
               (.pEngineName (utf8 stack "Spock"))
               (.engineVersion (VK10/VK_MAKE_VERSION 0 1 0))
               (.apiVersion VK13/VK_API_VERSION_1_3))
        exts (GLFWVulkan/glfwGetRequiredInstanceExtensions)
        ci   (doto (VkInstanceCreateInfo/calloc stack)
               (.sType VK10/VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
               (.pApplicationInfo app)
               (.ppEnabledExtensionNames exts))
        pp   (.mallocPointer stack 1)]
    (vk-check (VK10/vkCreateInstance ci nil pp) "vkCreateInstance failed")
    (let [inst (VkInstance. (.get pp 0) ci)]
      (swap! state assoc :instance inst)
      (MemoryStack/stackPop)
      inst)))

;; ---------------------------------------------------------------------------
;; Surface
;; ---------------------------------------------------------------------------
(defn- create-surface! [state]
  (let [^MemoryStack stack (MemoryStack/stackPush)
        lp  (.mallocLong stack 1)]
    (vk-check (GLFWVulkan/glfwCreateWindowSurface
                ^VkInstance (:instance @state)
                (long (:window-handle @state))
                nil lp)
               "glfwCreateWindowSurface failed")
    (let [s (.get lp 0)]
      (swap! state assoc :surface s)
      (MemoryStack/stackPop)
      s)))

;; ---------------------------------------------------------------------------
;; Physical device + queue families
;; ---------------------------------------------------------------------------
(defn- find-queue-families
  "Returns {:graphics-family int :present-family int} (partial on failure)."
  [^VkPhysicalDevice pd ^long surface]
  (let [^MemoryStack stack (MemoryStack/stackPush)
        ip  (.mallocInt stack 1)
        _   (VK10/vkGetPhysicalDeviceQueueFamilyProperties pd ip nil)
        cnt (.get ip 0)
        buf (VkQueueFamilyProperties/mallocStack cnt stack)
        _   (VK10/vkGetPhysicalDeviceQueueFamilyProperties pd ip buf)
        pb  (.mallocInt stack 1)
        res (reduce
              (fn [acc ^long i]
                (let [^VkQueueFamilyProperties p (.get buf (int i))
                      gfx?  (not= 0 (bit-and (.queueFlags p) VK10/VK_QUEUE_GRAPHICS_BIT))
                      _     (KHRSurface/vkGetPhysicalDeviceSurfaceSupportKHR pd i surface pb)
                      pres? (= 1 (.get pb 0))
                      acc'  (cond-> acc
                              gfx?  (assoc :graphics-family (int i))
                              pres? (assoc :present-family  (int i)))]
                  (if (and (:graphics-family acc') (:present-family acc'))
                    (reduced acc')
                    acc')))
              {}
              (range cnt))]
    (MemoryStack/stackPop)
    res))

(defn- pick-physical-device! [state]
  (let [^MemoryStack stack (MemoryStack/stackPush)
        ip  (.mallocInt stack 1)
        _   (VK10/vkEnumeratePhysicalDevices ^VkInstance (:instance @state) ip nil)
        cnt (.get ip 0)
        _   (when (zero? cnt) (throw (RuntimeException. "No Vulkan GPUs found")))
        pp  (.mallocPointer stack cnt)
        _   (VK10/vkEnumeratePhysicalDevices ^VkInstance (:instance @state) ip pp)
        surf (long (:surface @state))
        [pd fam] (some (fn [^long i]
                         (let [d   (VkPhysicalDevice. (.get pp i) ^VkInstance (:instance @state))
                               fam (find-queue-families d surf)]
                           (when (and (:graphics-family fam) (:present-family fam))
                             [d fam])))
                       (range cnt))]
    (when-not pd (throw (RuntimeException. "No suitable Vulkan physical device")))
    (swap! state assoc
           :physical-device pd
           :graphics-family (:graphics-family fam)
           :present-family  (:present-family fam))
    (MemoryStack/stackPop)
    pd))

;; ---------------------------------------------------------------------------
;; Logical device
;; ---------------------------------------------------------------------------
(defn- create-logical-device! [state]
  (let [^MemoryStack stack (MemoryStack/stackPush)
        gf   (int (:graphics-family @state))
        pf   (int (:present-family @state))
        uq   (distinct [gf pf])
  prio (doto (.mallocFloat stack 1) (.put 1.0) (.flip))
        qcis (VkDeviceQueueCreateInfo/callocStack (count uq) stack)
        _    (dorun (map-indexed
                      (fn [idx fam]
                        (doto ^VkDeviceQueueCreateInfo (.get qcis (int idx))
                          (.sType VK10/VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                          (.queueFamilyIndex (int fam))
                          (.pQueuePriorities prio)))
                      uq))
        feat (VkPhysicalDeviceFeatures/calloc stack)
        exts (strings->pp stack DEVICE-EXTENSIONS)
        ci   (doto (VkDeviceCreateInfo/calloc stack)
               (.sType VK10/VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
               (.pQueueCreateInfos qcis)
               (.pEnabledFeatures feat)
               (.ppEnabledExtensionNames exts))
        pp   (.mallocPointer stack 1)
        _    (vk-check (VK10/vkCreateDevice ^VkPhysicalDevice (:physical-device @state) ci nil pp)
                        "vkCreateDevice failed")
        dev  (VkDevice. (.get pp 0) ^VkPhysicalDevice (:physical-device @state) ci)
        gqp  (.mallocPointer stack 1)
        pqp  (.mallocPointer stack 1)
        _    (VK10/vkGetDeviceQueue dev gf 0 gqp)
        _    (VK10/vkGetDeviceQueue dev pf 0 pqp)
        gq   (VkQueue. (.get gqp 0) dev)
        pq   (VkQueue. (.get pqp 0) dev)]
    (swap! state assoc :device dev :graphics-queue gq :present-queue pq)
    (MemoryStack/stackPop)
    dev))

;; ---------------------------------------------------------------------------
;; Swapchain
;; ---------------------------------------------------------------------------
(defn- choose-format [fmts]
  (or (some (fn [^long i]
              (let [^VkSurfaceFormatKHR f (.get fmts (int i))]
                (when (and (= (.format f) VK10/VK_FORMAT_B8G8R8A8_SRGB)
                           (= (.colorSpace f) KHRSurface/VK_COLOR_SPACE_SRGB_NONLINEAR_KHR))
                  f)))
            (range (.remaining fmts)))
      (.get fmts 0)))

(defn- choose-present-mode [^java.nio.IntBuffer pm-buf]
  (or (some #(when (= (.get pm-buf (int %)) VK_PRESENT_MODE_MAILBOX_KHR)
               VK_PRESENT_MODE_MAILBOX_KHR)
            (range (.limit pm-buf)))
      VK_PRESENT_MODE_FIFO_KHR))

(defn- clamp ^long [^long v ^long lo ^long hi] (max lo (min hi v)))

(defn- choose-extent [^VkSurfaceCapabilitiesKHR caps w h]
  (let [cur (.currentExtent caps)]
    ;; Vulkan uses UINT32_MAX (0xFFFFFFFF, exposed as -1 in signed int) for undefined extent.
    (if (not= (int (.width cur)) -1)
      {:width (.width cur) :height (.height cur)}
      {:width  (clamp w (.width  (.minImageExtent caps)) (.width  (.maxImageExtent caps)))
       :height (clamp h (.height (.minImageExtent caps)) (.height (.maxImageExtent caps)))})))

(defn- create-swapchain! [state]
  (let [^MemoryStack stack (MemoryStack/stackPush)
        ^VkPhysicalDevice pd   (:physical-device @state)
        ^VkDevice         dev  (:device @state)
        surf  (long (:surface @state))
        ip    (.mallocInt stack 1)
        caps  (VkSurfaceCapabilitiesKHR/malloc stack)
        _     (KHRSurface/vkGetPhysicalDeviceSurfaceCapabilitiesKHR pd surf caps)
        ;; Formats
        _     (KHRSurface/vkGetPhysicalDeviceSurfaceFormatsKHR pd surf ip nil)
        fmts  (VkSurfaceFormatKHR/mallocStack (.get ip 0) stack)
        _     (KHRSurface/vkGetPhysicalDeviceSurfaceFormatsKHR pd surf ip fmts)
        ;; Present modes
        _     (KHRSurface/vkGetPhysicalDeviceSurfacePresentModesKHR pd surf ip nil)
        pm    (.mallocInt stack (.get ip 0))
        _     (KHRSurface/vkGetPhysicalDeviceSurfacePresentModesKHR pd surf ip pm)
        fmt   (choose-format fmts)
        mode  (choose-present-mode pm)
        ext   (choose-extent caps (:width @state) (:height @state))
        min-c (.minImageCount caps)
        max-c (.maxImageCount caps)
        img-c (if (zero? max-c) (inc min-c) (min (inc min-c) max-c))
        gf    (int (:graphics-family @state))
        pf    (int (:present-family @state))
        ci    (doto (VkSwapchainCreateInfoKHR/calloc stack)
                (.sType KHRSwapchain/VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                (.surface surf)
                (.minImageCount img-c)
                (.imageFormat (.format fmt))
                (.imageColorSpace (.colorSpace fmt))
                (.imageArrayLayers 1)
                (.imageUsage VK10/VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                (.preTransform (.currentTransform caps))
                (.compositeAlpha KHRSurface/VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                (.presentMode mode)
                (.clipped true)
                (.oldSwapchain VK_NULL))
        _     (doto (.imageExtent ci)
                (.width  (int (:width ext)))
                (.height (int (:height ext))))
        _     (if (= gf pf)
                (.imageSharingMode ci VK10/VK_SHARING_MODE_EXCLUSIVE)
                (let [qfb (doto (.mallocInt stack 2)
                            (.put gf) (.put pf) (.flip))]
                  (doto ci
                    (.imageSharingMode VK10/VK_SHARING_MODE_CONCURRENT)
                    (.pQueueFamilyIndices qfb))))
        lp    (.mallocLong stack 1)
        _     (vk-check (KHRSwapchain/vkCreateSwapchainKHR dev ci nil lp)
                         "vkCreateSwapchainKHR failed")
        sc    (.get lp 0)
        _     (KHRSwapchain/vkGetSwapchainImagesKHR dev sc ip nil)
        ib    (.mallocLong stack (.get ip 0))
        _     (KHRSwapchain/vkGetSwapchainImagesKHR dev sc ip ib)
        imgs  (mapv #(.get ib (int %)) (range (.limit ib)))]
    (swap! state assoc
           :swapchain sc
           :swapchain-images imgs
           :swapchain-format (.format fmt)
           :swapchain-extent ext)
    (MemoryStack/stackPop)
    sc))

;; ---------------------------------------------------------------------------
;; Image views
;; ---------------------------------------------------------------------------
(defn- create-image-views! [state]
  (let [^MemoryStack stack (MemoryStack/stackPush)
        ^VkDevice dev (:device @state)
        fmt  (int (:swapchain-format @state))
        lp   (.mallocLong stack 1)
        views (mapv (fn [img]
                      (let [ci (doto (VkImageViewCreateInfo/calloc stack)
                                 (.sType VK10/VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                                 (.image (long img))
                                 (.viewType VK10/VK_IMAGE_VIEW_TYPE_2D)
                                 (.format fmt))]
                        (doto (.components ci)
                          (.r VK10/VK_COMPONENT_SWIZZLE_IDENTITY)
                          (.g VK10/VK_COMPONENT_SWIZZLE_IDENTITY)
                          (.b VK10/VK_COMPONENT_SWIZZLE_IDENTITY)
                          (.a VK10/VK_COMPONENT_SWIZZLE_IDENTITY))
                        (doto (.subresourceRange ci)
                          (.aspectMask VK10/VK_IMAGE_ASPECT_COLOR_BIT)
                          (.baseMipLevel 0)
                          (.levelCount 1)
                          (.baseArrayLayer 0)
                          (.layerCount 1))
                        (.rewind lp)
                        (vk-check (VK10/vkCreateImageView dev ci nil lp)
                                   "vkCreateImageView failed")
                        (.get lp 0)))
                    (:swapchain-images @state))]
    (swap! state assoc :swapchain-views views)
    (MemoryStack/stackPop)
    views))

;; ---------------------------------------------------------------------------
;; Render pass
;; ---------------------------------------------------------------------------
(defn- create-render-pass! [state]
  (let [^MemoryStack stack (MemoryStack/stackPush)
        ^VkDevice dev (:device @state)
        fmt  (int (:swapchain-format @state))
        att  (doto (VkAttachmentDescription/callocStack 1 stack)
               (-> (.get 0)
                   (doto (.format fmt)
                         (.samples VK10/VK_SAMPLE_COUNT_1_BIT)
                         (.loadOp VK10/VK_ATTACHMENT_LOAD_OP_CLEAR)
                         (.storeOp VK10/VK_ATTACHMENT_STORE_OP_STORE)
                         (.stencilLoadOp VK10/VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                         (.stencilStoreOp VK10/VK_ATTACHMENT_STORE_OP_DONT_CARE)
                         (.initialLayout VK10/VK_IMAGE_LAYOUT_UNDEFINED)
                         (.finalLayout KHRSwapchain/VK_IMAGE_LAYOUT_PRESENT_SRC_KHR))))
        cref (doto (VkAttachmentReference/callocStack stack)
               (.attachment 0)
               (.layout VK10/VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL))
        cref-buf (doto (VkAttachmentReference/callocStack 1 stack)
                   (-> (.get 0)
                       (doto (.attachment 0)
                             (.layout VK10/VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL))))
        sub  (doto (VkSubpassDescription/callocStack 1 stack)
               (-> (.get 0)
                   (doto (.pipelineBindPoint VK10/VK_PIPELINE_BIND_POINT_GRAPHICS)
                         (.colorAttachmentCount 1)
                         (.pColorAttachments cref-buf))))
        dep  (doto (VkSubpassDependency/callocStack 1 stack)
               (-> (.get 0)
                   (doto (.srcSubpass VK10/VK_SUBPASS_EXTERNAL)
                         (.dstSubpass 0)
                         (.srcStageMask VK10/VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                         (.srcAccessMask 0)
                         (.dstStageMask VK10/VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                         (.dstAccessMask VK10/VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT))))
        ci   (doto (VkRenderPassCreateInfo/calloc stack)
               (.sType VK10/VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
               (.pAttachments att)
               (.pSubpasses sub)
               (.pDependencies dep))
        lp   (.mallocLong stack 1)
        _    (vk-check (VK10/vkCreateRenderPass dev ci nil lp) "vkCreateRenderPass failed")]
    (swap! state assoc :render-pass (.get lp 0))
    (MemoryStack/stackPop)
    (:render-pass @state)))

;; ---------------------------------------------------------------------------
;; Framebuffers
;; ---------------------------------------------------------------------------
(defn- create-framebuffers! [state]
  (let [^MemoryStack stack (MemoryStack/stackPush)
        ^VkDevice dev (:device @state)
        rp   (long (:render-pass @state))
        ext  (:swapchain-extent @state)
        lp   (.mallocLong stack 1)
        ab   (.mallocLong stack 1)
        fbs  (mapv (fn [view]
                     (.put ab 0 (long view)) (.rewind ab)
                     (.rewind lp)
                     (let [ci (doto (VkFramebufferCreateInfo/calloc stack)
                                (.sType VK10/VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                                (.renderPass rp)
                                (.attachmentCount 1)
                                (.pAttachments ab)
                                (.width  (int (:width ext)))
                                (.height (int (:height ext)))
                                (.layers 1))]
                       (vk-check (VK10/vkCreateFramebuffer dev ci nil lp)
                                  "vkCreateFramebuffer failed")
                       (.get lp 0)))
                   (:swapchain-views @state))]
    (swap! state assoc :framebuffers fbs)
    (MemoryStack/stackPop)
    fbs))

;; ---------------------------------------------------------------------------
;; Command pool + buffers
;; ---------------------------------------------------------------------------
(defn- create-command-pool! [state]
  (let [^MemoryStack stack (MemoryStack/stackPush)
        ^VkDevice dev (:device @state)
        ci (doto (VkCommandPoolCreateInfo/calloc stack)
             (.sType VK10/VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
             (.flags VK10/VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
             (.queueFamilyIndex (int (:graphics-family @state))))
        lp (.mallocLong stack 1)
        _  (vk-check (VK10/vkCreateCommandPool dev ci nil lp) "vkCreateCommandPool failed")]
    (swap! state assoc :command-pool (.get lp 0))
    (MemoryStack/stackPop)))

(defn- create-command-buffers! [state]
  (let [^MemoryStack stack (MemoryStack/stackPush)
        ^VkDevice dev (:device @state)
        ai (doto (VkCommandBufferAllocateInfo/calloc stack)
             (.sType VK10/VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
             (.commandPool (long (:command-pool @state)))
             (.level VK10/VK_COMMAND_BUFFER_LEVEL_PRIMARY)
             (.commandBufferCount MAX-FRAMES))
        pp (.mallocPointer stack MAX-FRAMES)
        _  (vk-check (VK10/vkAllocateCommandBuffers dev ai pp)
                      "vkAllocateCommandBuffers failed")
        cbs (mapv #(VkCommandBuffer. (.get pp (int %)) dev) (range MAX-FRAMES))]
    (swap! state assoc :command-buffers cbs)
    (MemoryStack/stackPop)
    cbs))

;; ---------------------------------------------------------------------------
;; Sync objects
;; ---------------------------------------------------------------------------
(defn- create-sync-objects! [state]
  (let [^MemoryStack stack (MemoryStack/stackPush)
        ^VkDevice dev (:device @state)
        sci (doto (VkSemaphoreCreateInfo/calloc stack)
              (.sType VK10/VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO))
        fci (doto (VkFenceCreateInfo/calloc stack)
              (.sType VK10/VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
              (.flags VK10/VK_FENCE_CREATE_SIGNALED_BIT))
        lp  (.mallocLong stack 1)
        mk-sem   #(do (vk-check (VK10/vkCreateSemaphore dev sci nil lp) "vkCreateSemaphore failed")
                      (.get lp 0))
        mk-fence #(do (vk-check (VK10/vkCreateFence dev fci nil lp) "vkCreateFence failed")
                      (.get lp 0))]
    (swap! state assoc
           :image-available  (mapv (fn [_] (mk-sem))   (range MAX-FRAMES))
           :render-finished  (mapv (fn [_] (mk-sem))   (range MAX-FRAMES))
           :in-flight-fences (mapv (fn [_] (mk-fence)) (range MAX-FRAMES)))
    (MemoryStack/stackPop)))

;; ---------------------------------------------------------------------------
;; Command buffer recording
;; ---------------------------------------------------------------------------
(defn- record-command-buffer! [state ^VkCommandBuffer cb ^long img-idx renderables]
  (require 'spock.log)
  ((resolve 'spock.log/log) "record-command-buffer! renderables=" (count renderables) "img=" img-idx)
  (let [^MemoryStack stack (MemoryStack/stackPush)
        ^VkDevice dev (:device @state)
        rp   (long (:render-pass @state))
        fb   (long (nth (:framebuffers @state) img-idx))
        ext  (:swapchain-extent @state)
        cc   (:clear-color @state)
        bi   (doto (VkCommandBufferBeginInfo/calloc stack)
               (.sType VK10/VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO))
        _    (vk-check (VK10/vkBeginCommandBuffer cb bi) "vkBeginCommandBuffer failed")
        cv   (VkClearValue/callocStack 1 stack)
        _    (doto (.color (.get cv 0))
               (.float32 0 (float (nth cc 0)))
               (.float32 1 (float (nth cc 1)))
               (.float32 2 (float (nth cc 2)))
               (.float32 3 (float (nth cc 3))))
        rbi  (doto (VkRenderPassBeginInfo/calloc stack)
               (.sType VK10/VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
               (.renderPass rp)
               (.framebuffer fb)
               (.pClearValues cv))
        _    (doto (.renderArea rbi)
               (.offset (doto (VkOffset2D/calloc stack) (.set 0 0)))
               (.extent (doto (VkExtent2D/calloc stack)
                          (.width  (int (:width ext)))
                          (.height (int (:height ext))))))
        _    (VK10/vkCmdBeginRenderPass cb rbi VK10/VK_SUBPASS_CONTENTS_INLINE)
        w    (int (:width ext))
        h    (int (:height ext))
        ;; Set dynamic viewport and scissor
        vp   (doto (VkViewport/calloc 1 stack)
               (-> (.get 0)
                   (doto (.x 0.0) (.y 0.0)
                         (.width (float w)) (.height (float h))
                         (.minDepth 0.0) (.maxDepth 1.0))))
        sc   (doto (VkRect2D/calloc 1 stack)
               (-> (.get 0)
                   (doto (-> .offset (.set 0 0))
                         (-> .extent (.set w h)))))
        _    (VK10/vkCmdSetViewport cb 0 vp)
        _    (VK10/vkCmdSetScissor  cb 0 sc)
        vkext (doto (VkExtent2D/malloc stack)
                (.width  w)
                (.height h))]
    (doseq [r renderables]
      (renderable/draw r cb dev rp vkext))
    (VK10/vkCmdEndRenderPass cb)
    (vk-check (VK10/vkEndCommandBuffer cb) "vkEndCommandBuffer failed")
    (MemoryStack/stackPop)))

;; ---------------------------------------------------------------------------
;; Recording — staging image + jcodec
;; ---------------------------------------------------------------------------

(defn- find-memory-type
  "Return the memory-type index satisfying type-filter bits and required properties."
  [^VkPhysicalDevice pd ^long type-filter ^long props]
  (let [^MemoryStack stack (MemoryStack/stackPush)
        mp (VkPhysicalDeviceMemoryProperties/calloc stack)]
    (VK10/vkGetPhysicalDeviceMemoryProperties pd mp)
    (let [result (some (fn [^long i]
                         (when (and (not= 0 (bit-and type-filter (bit-shift-left 1 i)))
                                    (= props (bit-and (.propertyFlags (.memoryTypes mp i)) props)))
                           i))
                       (range (.memoryTypeCount mp)))]
      (MemoryStack/stackPop)
      (or result (throw (RuntimeException. "No suitable memory type found"))))))

(defn- alloc-staging-image!
  "Allocate a LINEAR, HOST_VISIBLE image for readback.
   Returns {:image long :memory long :width int :height int}."
  [state]
  (let [^MemoryStack stack  (MemoryStack/stackPush)
        ^VkDevice    dev    (:device @state)
        ^VkPhysicalDevice pd (:physical-device @state)
        ext   (:swapchain-extent @state)
        w     (int (:width ext))
        h     (int (:height ext))
        fmt   (int (:swapchain-format @state))
        ci    (doto (VkImageCreateInfo/calloc stack)
                (.sType VK10/VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                (.imageType VK10/VK_IMAGE_TYPE_2D)
                (.format fmt)
                (.mipLevels 1)
                (.arrayLayers 1)
                (.samples VK10/VK_SAMPLE_COUNT_1_BIT)
                (.tiling VK10/VK_IMAGE_TILING_LINEAR)
                (.usage VK10/VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                (.sharingMode VK10/VK_SHARING_MODE_EXCLUSIVE)
                (.initialLayout VK10/VK_IMAGE_LAYOUT_UNDEFINED))
        _     (doto (.extent ci) (.width w) (.height h) (.depth 1))
        lp    (.mallocLong stack 1)
        _     (vk-check (VK10/vkCreateImage dev ci nil lp) "vkCreateImage (staging) failed")
        img   (.get lp 0)
        mr    (VkMemoryRequirements/calloc stack)
        _     (VK10/vkGetImageMemoryRequirements dev img mr)
        mt    (find-memory-type pd
                (.memoryTypeBits mr)
                (bit-or VK10/VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                        VK10/VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))
        ai    (doto (VkMemoryAllocateInfo/calloc stack)
                (.sType VK10/VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                (.allocationSize (.size mr))
                (.memoryTypeIndex (int mt)))
        _     (vk-check (VK10/vkAllocateMemory dev ai nil lp) "vkAllocateMemory (staging) failed")
        mem   (.get lp 0)
        _     (vk-check (VK10/vkBindImageMemory dev img mem 0) "vkBindImageMemory (staging) failed")]
    (MemoryStack/stackPop)
    {:image img :memory mem :width w :height h}))

(defn- free-staging-image! [state {:keys [image memory]}]
  (let [^VkDevice dev (:device @state)]
    (VK10/vkDestroyImage  dev (long image)  nil)
    (VK10/vkFreeMemory    dev (long memory) nil)))

(defn- transition-image-layout!
  "Insert a pipeline barrier to transition image layout."
  [^VkCommandBuffer cb image
   old-layout new-layout
   src-stage dst-stage
   src-access dst-access]
  (let [^MemoryStack stack (MemoryStack/stackPush)
        barrier (doto (VkImageMemoryBarrier/callocStack 1 stack)
                  (-> (.get 0)
                      (doto (.sType VK10/VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                            (.oldLayout old-layout)
                            (.newLayout new-layout)
                            (.srcQueueFamilyIndex VK10/VK_QUEUE_FAMILY_IGNORED)
                            (.dstQueueFamilyIndex VK10/VK_QUEUE_FAMILY_IGNORED)
                            (.image image))))]
    (doto (-> barrier (.get 0) .subresourceRange)
      (.aspectMask VK10/VK_IMAGE_ASPECT_COLOR_BIT)
      (.baseMipLevel 0)
      (.levelCount 1)
      (.baseArrayLayer 0)
      (.layerCount 1))
    (-> barrier (.get 0) (.srcAccessMask src-access))
    (-> barrier (.get 0) (.dstAccessMask dst-access))
    (VK10/vkCmdPipelineBarrier cb src-stage dst-stage 0 nil nil barrier)
    (MemoryStack/stackPop)))

(defn- blit-swapchain-to-staging!
  "Record commands to blit the current swapchain image into the staging image.
   Both images must already be transitioned to the correct layouts before this call."
  [^VkCommandBuffer cb src-image dst-image w h]
  (let [^MemoryStack stack (MemoryStack/stackPush)
        blit (VkImageBlit/callocStack 1 stack)
        b    (.get blit 0)]
    (doto (.srcSubresource b)
      (.aspectMask VK10/VK_IMAGE_ASPECT_COLOR_BIT)
      (.mipLevel 0)
      (.baseArrayLayer 0)
      (.layerCount 1))
    (doto (.srcOffsets b)
      (-> (.get 0) (.set 0 0 0))
      (-> (.get 1) (.set w h 1)))
    (doto (.dstSubresource b)
      (.aspectMask VK10/VK_IMAGE_ASPECT_COLOR_BIT)
      (.mipLevel 0)
      (.baseArrayLayer 0)
      (.layerCount 1))
    (doto (.dstOffsets b)
      (-> (.get 0) (.set 0 0 0))
      (-> (.get 1) (.set w h 1)))
    (VK10/vkCmdBlitImage
      cb
      src-image VK10/VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
      dst-image VK10/VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
      blit
      VK10/VK_FILTER_NEAREST)
    (MemoryStack/stackPop)))

(defn- read-staging-pixels!
  "Map the staging image memory and read pixels into a BufferedImage.
   Handles BGRA → RGB conversion. Uses heap allocations (not MemoryStack)
   since this is called from within an existing stack frame."
  [state {:keys [image memory width height]}]
  (let [^VkDevice dev (:device @state)
        ;; Heap-allocated structs — safe to use inside a nested call
        sl (org.lwjgl.vulkan.VkSubresourceLayout/calloc)
        sr (doto (org.lwjgl.vulkan.VkImageSubresource/calloc)
             (.aspectMask VK10/VK_IMAGE_ASPECT_COLOR_BIT)
             (.mipLevel 0)
             (.arrayLayer 0))
        _  (VK10/vkGetImageSubresourceLayout dev (long image) sr sl)
        row-pitch (long (.rowPitch sl))
        total     (long (.size sl))
        pp        (MemoryUtil/memAllocPointer 1)]
    (try
      (vk-check (VK10/vkMapMemory dev (long memory) 0 total 0 pp) "vkMapMemory failed")
      (let [ptr  (.get pp 0)
            bimg (BufferedImage. width height BufferedImage/TYPE_INT_RGB)
            ;; Copy all pixels into a Java int[] first, then set in bulk.
            ;; Avoids per-pixel JNI calls after unmap.
            pixels (int-array (* width height))]
        (dotimes [y height]
          (dotimes [x width]
            (let [off (+ (* (long y) row-pitch) (* (long x) 4))
                  b   (bit-and 0xFF (MemoryUtil/memGetByte (+ ptr off)))
                  g   (bit-and 0xFF (MemoryUtil/memGetByte (+ ptr (+ off 1))))
                  r   (bit-and 0xFF (MemoryUtil/memGetByte (+ ptr (+ off 2))))
                  rgb (bit-or (bit-shift-left r 16)
                              (bit-shift-left g 8)
                              b)]
              (aset pixels (+ (* y width) x) (int rgb)))))
        (VK10/vkUnmapMemory dev (long memory))
        (.setRGB bimg 0 0 width height pixels 0 width)
        bimg)
      (finally
        (MemoryUtil/memFree pp)
        (.free sl)
        (.free sr)))))

(defn- capture-frame-to-encoder!
  "Blit the current swapchain image to the staging image, read pixels,
   and encode one frame. img-idx is the swapchain image index just rendered.
   No-ops if recording has already been stopped."
  [state img-idx]
  (when-let [{:keys [encoder staging]} (:recording @state)]
    (let [^MemoryStack stack (MemoryStack/stackPush)
          ^VkDevice dev      (:device @state)
          gq  ^org.lwjgl.vulkan.VkQueue (:graphics-queue @state)
          pool (long (:command-pool @state))
          src-image (long (nth (:swapchain-images @state) img-idx))
          {:keys [image width height]} staging
          dst-image (long image)

          ;; Allocate a one-shot command buffer
          ai  (doto (VkCommandBufferAllocateInfo/calloc stack)
                (.sType VK10/VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                (.commandPool pool)
                (.level VK10/VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                (.commandBufferCount 1))
          pp  (.mallocPointer stack 1)
          _   (vk-check (VK10/vkAllocateCommandBuffers dev ai pp) "alloc capture cb failed")
          cb  (VkCommandBuffer. (.get pp 0) dev)
          bi  (doto (VkCommandBufferBeginInfo/calloc stack)
                (.sType VK10/VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                (.flags VK10/VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT))
          _   (VK10/vkBeginCommandBuffer cb bi)]

      ;; Transition swapchain image: PRESENT_SRC → TRANSFER_SRC
      (transition-image-layout! cb src-image
        KHRSwapchain/VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
        VK10/VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
        VK10/VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT
        VK10/VK_PIPELINE_STAGE_TRANSFER_BIT
        0
        VK10/VK_ACCESS_TRANSFER_READ_BIT)

      ;; Transition staging image: UNDEFINED → TRANSFER_DST
      (transition-image-layout! cb dst-image
        VK10/VK_IMAGE_LAYOUT_UNDEFINED
        VK10/VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
        VK10/VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
        VK10/VK_PIPELINE_STAGE_TRANSFER_BIT
        0
        VK10/VK_ACCESS_TRANSFER_WRITE_BIT)

      ;; Blit
      (blit-swapchain-to-staging! cb src-image dst-image width height)

      ;; Transition staging: TRANSFER_DST → GENERAL (host readable)
      (transition-image-layout! cb dst-image
        VK10/VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
        VK10/VK_IMAGE_LAYOUT_GENERAL
        VK10/VK_PIPELINE_STAGE_TRANSFER_BIT
        VK10/VK_PIPELINE_STAGE_HOST_BIT
        VK10/VK_ACCESS_TRANSFER_WRITE_BIT
        VK10/VK_ACCESS_HOST_READ_BIT)

      ;; Transition swapchain back: TRANSFER_SRC → PRESENT_SRC
      (transition-image-layout! cb src-image
        VK10/VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
        KHRSwapchain/VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
        VK10/VK_PIPELINE_STAGE_TRANSFER_BIT
        VK10/VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT
        VK10/VK_ACCESS_TRANSFER_READ_BIT
        0)

      (vk-check (VK10/vkEndCommandBuffer cb) "end capture cb failed")

      ;; Submit and wait
      (let [cbp (doto (.mallocPointer stack 1) (.put (.address cb)) (.flip))
            si  (doto (VkSubmitInfo/calloc stack)
                  (.sType VK10/VK_STRUCTURE_TYPE_SUBMIT_INFO)
                  (.pCommandBuffers cbp))]
        (vk-check (VK10/vkQueueSubmit gq si VK_NULL) "submit capture cb failed")
        (VK10/vkQueueWaitIdle gq))

      ;; Read pixels and post to async encode queue (non-blocking offer).
      ;; If the queue is full we drop the frame rather than stall the render thread.
      (let [bimg (read-staging-pixels! state staging)
            q    (:frame-q (:recording @state))]
        (when q (.offer ^java.util.concurrent.LinkedBlockingQueue q bimg)))

      ;; Free one-shot command buffer
      (let [cbp (doto (.mallocPointer stack 1) (.put (.address cb)) (.flip))]
        (VK10/vkFreeCommandBuffers dev pool cbp))

      (MemoryStack/stackPop))))


;; ---------------------------------------------------------------------------
;; Frame render
;; ---------------------------------------------------------------------------
(defn- render-frame! [state renderables]
  (let [^MemoryStack stack (MemoryStack/stackPush)
        ^VkDevice dev  (:device @state)
        frame  (int (:current-frame @state))
        fence  (long (nth (:in-flight-fences @state) frame))
        ia-sem (long (nth (:image-available @state) frame))
        rf-sem (long (nth (:render-finished @state) frame))
        ^VkCommandBuffer cb (nth (:command-buffers @state) frame)
  fl     (doto (.mallocLong stack 1) (.put fence) (.flip))
        ip     (.mallocInt stack 1)]
    (VK10/vkWaitForFences dev fl true Long/MAX_VALUE)
    (let [res (KHRSwapchain/vkAcquireNextImageKHR
                dev (long (:swapchain @state)) Long/MAX_VALUE ia-sem VK_NULL ip)]
      (when (= res KHRSwapchain/VK_ERROR_OUT_OF_DATE_KHR)
        (MemoryStack/stackPop)
        (throw (ex-info "Swapchain out of date" {:type :swapchain-recreate})))
      (let [img-idx (long (.get ip 0))]
        (VK10/vkResetFences dev fence)
        (VK10/vkResetCommandBuffer cb 0)
        (record-command-buffer! state cb img-idx renderables)
          (let [ws  (doto (.mallocLong stack 1) (.put ia-sem) (.flip))
            ss  (doto (.mallocLong stack 1) (.put rf-sem) (.flip))
              wst (doto (.mallocInt  stack 1)
              (.put VK10/VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT) (.flip))
            cbp (doto (.mallocPointer stack 1) (.put (.address cb)) (.flip))
              si  (doto (VkSubmitInfo/calloc stack)
                    (.sType VK10/VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    (.waitSemaphoreCount 1)
                    (.pWaitSemaphores ws)
                    (.pWaitDstStageMask wst)
                    (.pCommandBuffers cbp)
                    (.pSignalSemaphores ss))]
          (vk-check (VK10/vkQueueSubmit ^VkQueue (:graphics-queue @state) si fence)
                     "vkQueueSubmit failed")
            (let [scb (doto (.mallocLong stack 1) (.put (long (:swapchain @state))) (.flip))
              iib (doto (.mallocInt  stack 1) (.put (int img-idx)) (.flip))
                pi  (doto (VkPresentInfoKHR/calloc stack)
                      (.sType KHRSwapchain/VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                      (.pWaitSemaphores ss)
                      (.swapchainCount 1)
                      (.pSwapchains scb)
                      (.pImageIndices iib))]
            (KHRSwapchain/vkQueuePresentKHR ^VkQueue (:present-queue @state) pi)
            ;; Capture frame for recording (if active).
            ;; Check inside the present call so we don't encode after stop-recording!.
            (when-let [rec (:recording @state)]
              (when (:encoder rec)
                (capture-frame-to-encoder! state img-idx)))))))
    (swap! state update :current-frame #(mod (inc %) MAX-FRAMES))
    (MemoryStack/stackPop)))


;; ---------------------------------------------------------------------------
;; Cleanup
;; ---------------------------------------------------------------------------
(defn- destroy-swapchain! [state]
  (let [^VkDevice dev (:device @state)]
    (doseq [fb (:framebuffers @state)]   (VK10/vkDestroyFramebuffer  dev (long fb) nil))
    (doseq [iv (:swapchain-views @state)] (VK10/vkDestroyImageView   dev (long iv) nil))
    (KHRSwapchain/vkDestroySwapchainKHR dev (long (:swapchain @state)) nil)
    (swap! state assoc :framebuffers [] :swapchain-views [] :swapchain VK_NULL)))

(defn- full-cleanup! [state]
  (let [^VkDevice   dev  (:device @state)
        ^VkInstance inst (:instance @state)
        surf (long (:surface @state))]
    (VK10/vkDeviceWaitIdle dev)
    (doseq [s (:image-available @state)]  (VK10/vkDestroySemaphore dev (long s) nil))
    (doseq [s (:render-finished @state)]  (VK10/vkDestroySemaphore dev (long s) nil))
    (doseq [f (:in-flight-fences @state)] (VK10/vkDestroyFence     dev (long f) nil))
    (VK10/vkDestroyCommandPool dev (long (:command-pool @state)) nil)
    (destroy-swapchain! state)
    (VK10/vkDestroyRenderPass dev (long (:render-pass @state)) nil)
    (VK10/vkDestroyDevice dev nil)
    (KHRSurface/vkDestroySurfaceKHR inst surf nil)
    (VK10/vkDestroyInstance inst nil)
    (reset! state initial-state)))

;; ---------------------------------------------------------------------------
;; VulkanRenderer record
;; ---------------------------------------------------------------------------
(defrecord VulkanRenderer [state]
  renderer/Renderer

  (create-surface! [_this window-handle]
    ;; Called on the main thread. Instance must already exist.
    ;; If instance doesn't exist yet, create it here (it's thread-safe to do so).
    (swap! state assoc :window-handle window-handle)
    (try
      (when (= VK_NULL (:instance @state))
        (create-instance! state))
      (create-surface! state)
      true
      (catch Exception e
        (println "[VulkanRenderer] create-surface! failed:" (.getMessage e))
        false)))

  (init! [_this window-handle width height]
    ;; Called on the render thread. Surface already exists.
    (swap! state assoc :window-handle window-handle :width width :height height)
    (try
      ;; Instance + surface may already be set from create-surface! call
      (when (= VK_NULL (:instance @state))
        (create-instance! state))
      (when (= VK_NULL (:surface @state))
        (create-surface! state))
      (pick-physical-device! state)
      (create-logical-device! state)
      (create-swapchain! state)
      (create-image-views! state)
      (create-render-pass! state)
      (create-framebuffers! state)
      (create-command-pool! state)
      (create-command-buffers! state)
      (create-sync-objects! state)
      true
      (catch Exception e
        (println "[VulkanRenderer] init! failed:" (.getMessage e))
        (.printStackTrace e)
        false)))

  (render! [_this renderables]
    (try
      (render-frame! state renderables)
      true
      (catch clojure.lang.ExceptionInfo e
        (when-not (= :swapchain-recreate (:type (ex-data e)))
          (println "[VulkanRenderer] ExceptionInfo:" (.getMessage e)))
        false)
      (catch Exception e
        ;; Suppress benign "muxer track has finished" noise from jcodec
        ;; when the encoder races ahead of the last rendered frame.
        (when-not (.contains (.getMessage e) "muxer track has finished")
          (println "[VulkanRenderer] render! failed:" (.getMessage e)))
        false)))

  (cleanup! [_this]
    (try (full-cleanup! state)
         (catch Exception e
           (println "[VulkanRenderer] cleanup! failed:" (.getMessage e)))))

  (get-clear-color [_this] (:clear-color @state))
  (set-clear-color! [_this color] (swap! state assoc :clear-color color))
  (get-extent [_this] (:swapchain-extent @state))
  (get-render-pass [_this] (:render-pass @state))
  (get-device [_this] (:device @state))

  (start-recording! [_this path fps]
    (when (:recording @state)
      (println "[VulkanRenderer] already recording; ignoring start-recording!"))
    (when-not (:recording @state)
      (try
        (VK10/vkDeviceWaitIdle ^VkDevice (:device @state))
        (let [staging (alloc-staging-image! state)
              encoder (AWTSequenceEncoder/createSequenceEncoder
                        (File. ^String path)
                        (int fps))
              ;; Async encode queue: render thread pushes BufferedImages here,
              ;; encode thread drains it. Keeps render loop from blocking on jcodec.
              frame-q  (java.util.concurrent.LinkedBlockingQueue. 8)
              running? (volatile! true)
              enc-thread (Thread.
                           ^Runnable
                           (fn []
                             (loop []
                               (let [frame (.poll frame-q 100 java.util.concurrent.TimeUnit/MILLISECONDS)]
                                 (cond
                                   (instance? BufferedImage frame)
                                   (do (try (.encodeImage encoder frame)
                                            (catch Exception _ nil))
                                       (recur))
                                   @running?
                                   (recur)))))
                           "spock-encode")]
          (.start enc-thread)
          (swap! state assoc :recording {:encoder  encoder
                                         :staging  staging
                                         :frame-q  frame-q
                                         :running? running?
                                         :enc-thread enc-thread})
          (println "[VulkanRenderer] recording started →" path))
        (catch Exception e
          (println "[VulkanRenderer] start-recording! failed:" (.getMessage e))
          (.printStackTrace e)))))

  (stop-recording! [_this]
    (when-let [{:keys [^AWTSequenceEncoder encoder staging running? enc-thread]} (:recording @state)]
      (try
        ;; Signal render thread to stop posting frames, then drain the encode queue.
        (vreset! running? false)
        (.join ^Thread enc-thread 5000)
        (VK10/vkDeviceWaitIdle ^VkDevice (:device @state))
        (.finish encoder)
        (free-staging-image! state staging)
        (swap! state assoc :recording nil)
        (println "[VulkanRenderer] recording stopped")
        (catch Exception e
          (println "[VulkanRenderer] stop-recording! failed:" (.getMessage e))
          (.printStackTrace e))))))

(defn make-vulkan-renderer
  ([]          (make-vulkan-renderer "Spock"))
  ([title]     (->VulkanRenderer (atom (assoc initial-state :title title)))))
