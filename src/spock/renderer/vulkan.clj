(ns spock.renderer.vulkan
  "Vulkan backend implementing spock.renderer.core/Renderer.
   Wraps LWJGL Vulkan bindings.

   State is held in an atom so Clojure's immutable semantics don't fight
   the inherently stateful Vulkan lifecycle."
  (:require [spock.renderer.core :as renderer])
  (:import [org.lwjgl.vulkan VK13]))

;; ---------------------------------------------------------------------------
;; Internal state map keys (all handle values are longs, VK_NULL_HANDLE = 0)
;; ---------------------------------------------------------------------------
(def ^:private initial-state
  {:instance           0
   :surface            0
   :physical-device    0
   :device             0
   :graphics-queue     0
   :present-queue      0
   :swapchain          0
   :swapchain-images   []
   :swapchain-views    []
   :swapchain-format   nil
   :swapchain-extent   nil
   :render-pass        0
   :framebuffers       []
   :command-pool       0
   :command-buffers    []
   :image-available    []   ; semaphores
   :render-finished    []   ; semaphores
   :in-flight-fences   []
   :current-frame      0
   :clear-color        [0.1 0.12 0.18 1.0]
   :window-handle      nil
   :width              1280
   :height             720})

;; ---------------------------------------------------------------------------
;; VulkanRenderer record
;; ---------------------------------------------------------------------------
(defrecord VulkanRenderer [state]
  renderer/Renderer

  (init! [this window-handle width height]
    ;; TODO #2 — full Vulkan init sequence
    (swap! state assoc
           :window-handle window-handle
           :width width
           :height height)
    (println "[VulkanRenderer] init! — TODO: full Vulkan init")
    false)

  (render! [this renderables]
    ;; TODO #2 — frame render loop
    (println "[VulkanRenderer] render! — TODO")
    false)

  (cleanup! [this]
    ;; TODO #2 — destroy all resources in reverse-init order
    (println "[VulkanRenderer] cleanup! — TODO")
    nil)

  (get-clear-color [this]
    (:clear-color @state))

  (set-clear-color! [this color]
    (swap! state assoc :clear-color color)))

(defn make-vulkan-renderer []
  (->VulkanRenderer (atom initial-state)))
