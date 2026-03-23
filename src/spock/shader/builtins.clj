(ns spock.shader.builtins
  "Pre-compiled built-in shaders for spock renderables.

   Shaders are defined as EDN descriptor maps in src/shaders/*.edn and compiled
   to SPIR-V ByteBuffers at namespace load time via warpaint.dsl/load-edn.

   Use with (pipeline/vert-spv cfg polygon-vert) instead of (pipeline/vert-path ...)."
  (:require [warpaint.dsl :as dsl]))

;; ---------------------------------------------------------------------------
;; Triangle shaders (hardcoded RGB triangle — default for :triangle renderable)
;; ---------------------------------------------------------------------------

(def triangle-vert (dsl/load-edn "src/shaders/triangle.vert.edn"))
(def triangle-frag (dsl/load-edn "src/shaders/triangle.frag.edn"))

;; ---------------------------------------------------------------------------
;; Polygon shaders (:polygon renderable)
;; ---------------------------------------------------------------------------

(def polygon-vert (dsl/load-edn "src/shaders/polygon.vert.edn"))
(def polygon-frag (dsl/load-edn "src/shaders/polygon.frag.edn"))

;; ---------------------------------------------------------------------------
;; Sprite shaders (:sprite renderable)
;; ---------------------------------------------------------------------------

(def sprite-vert (dsl/load-edn "src/shaders/sprite.vert.edn"))
(def sprite-frag (dsl/load-edn "src/shaders/sprite.frag.edn"))

;; ---------------------------------------------------------------------------
;; Text shaders (:text renderable)
;; ---------------------------------------------------------------------------

(def text-vert (dsl/load-edn "src/shaders/text.vert.edn"))
(def text-frag (dsl/load-edn "src/shaders/text.frag.edn"))
