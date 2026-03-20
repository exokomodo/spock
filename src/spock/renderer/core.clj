(ns spock.renderer.core)

;; Mirrors drakon::Renderer, split for multi-threading:
;;
;;  create-surface! — called on the main thread (GLFW requires it)
;;  init!           — called on the render thread (everything after surface)
;;  render!         — called on the render thread
;;  cleanup!        — called on the render thread
;;
(defprotocol Renderer
  (create-surface! [this window-handle]
    "Create the Vulkan window surface. Must be called on the main thread.")
  (init! [this window-handle width height]
    "Complete Vulkan init (device → sync objects). Called on the render thread.
     Surface must already exist. Returns true on success.")
  (render! [this renderables]
    "Render one frame. renderables is a seq of Renderable.")
  (cleanup! [this]
    "Wait for GPU idle and release all Vulkan resources.
     Callers are responsible for cleaning up renderables before calling this.")
  (get-clear-color [this]
    "Return current clear color as [r g b a] floats.")
  (set-clear-color! [this color]
    "Set the clear color. color is [r g b a].")
  (get-extent [this]
    "Return the swapchain extent as {:width int :height int}.")
  (get-render-pass [this]
    "Return the VkRenderPass handle (long).")
  (get-device [this]
    "Return the VkDevice."))
