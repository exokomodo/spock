(ns spock.renderer.core)

;; Mirrors drakon::Renderer
(defprotocol Renderer
  (init!      [this window-handle width height]
    "Initialize the renderer against the given native window handle.")
  (render!    [this renderables]
    "Render a frame. renderables is a seq of Renderable.")
  (cleanup!   [this]
    "Release all GPU resources.")
  (get-clear-color [this]
    "Return the current clear color as [r g b a] floats.")
  (set-clear-color! [this color]
    "Set the clear color. color is [r g b a]."))
