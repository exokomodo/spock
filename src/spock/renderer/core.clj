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
    "Render one frame. renderables is a seq of Renderable.
     If recording is active, captures the frame automatically.")
  (cleanup! [this]
    "Wait for GPU idle and release all resources.")
  (get-clear-color [this]
    "Return current clear color as [r g b a] floats.")
  (set-clear-color! [this color]
    "Set the clear color. color is [r g b a].")
  (get-extent [this]
    "Return the swapchain extent as {:width int :height int}.")
  (get-render-pass [this]
    "Return the VkRenderPass handle (long).")
  (get-device [this]
    "Return the VkDevice.")
  (start-recording! [this path fps]
    "Begin recording frames to an MP4 file at path.
     fps controls the playback frame rate of the output.
     Allocates a host-visible staging image for readback.
     Call on the render thread (after init!).")
  (stop-recording! [this]
    "Finalise the recording and free the staging image.
     Call on the render thread."))
