(ns spock.shader.builtins
  "Pre-compiled DSL shaders for built-in spock renderables.

   Each var holds a SPIR-V ByteBuffer compiled from the DSL at namespace load time.
   Use with (pipeline/vert-spv cfg polygon-vert) instead of (pipeline/vert-path ...)."
  (:require [spock.shader.dsl :as dsl]))

;; ---------------------------------------------------------------------------
;; Polygon shaders
;; ---------------------------------------------------------------------------

(dsl/defshader polygon-vert :vertex
  {:inputs  [{:name :in-pos :type :vec2 :location 0}]
   :outputs [{:name :frag-color :type :vec4 :location 0}]
   :push-constants [{:name :translation :type :vec2}
                    {:name :rotation    :type :float}
                    {:name :padding     :type :vec4}
                    {:name :color       :type :vec4}]}
  (set! gl-Position
        (vec4 (+ (vec2 (- (* (.x in-pos) (cos (.rotation pc)))
                          (* (.y in-pos) (sin (.rotation pc))))
                       (+ (* (.x in-pos) (sin (.rotation pc)))
                          (* (.y in-pos) (cos (.rotation pc)))))
                 (.translation pc))
              0.0
              1.0))
  (set! frag-color (.color pc)))

(dsl/defshader polygon-frag :fragment
  {:inputs  [{:name :frag-color :type :vec4 :location 0}]
   :outputs [{:name :out-color :type :vec4 :location 0}]}
  (set! out-color frag-color))
