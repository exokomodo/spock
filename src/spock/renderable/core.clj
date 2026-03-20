(ns spock.renderable.core)

;; Mirrors drakon::Renderable
;; Anything drawable implements this protocol.
(defprotocol Renderable
  (draw [this command-buffer device render-pass extent]
    "Record draw commands into command-buffer for this frame.
     command-buffer — VkCommandBuffer (long handle)
     device         — VkDevice (long handle)
     render-pass    — VkRenderPass (long handle)
     extent         — VkExtent2D")
  (cleanup! [this device]
    "Release any GPU resources owned by this renderable.
     Called by the renderer during shutdown, before vkDestroyDevice.
     device — VkDevice"))
