(ns spock.shader.builtins
  "Pre-compiled DSL shaders for built-in spock renderables.

   Each var holds a SPIR-V ByteBuffer compiled from the DSL at namespace load time.
   Use with (pipeline/vert-spv cfg polygon-vert) instead of (pipeline/vert-path ...)."
  (:require [spock.shader.dsl :as dsl]))

;; ---------------------------------------------------------------------------
;; Triangle shaders (hello example default)
;;   Hardcoded RGB triangle — no vertex buffer, positions/colors in shader
;; ---------------------------------------------------------------------------

(dsl/defshader triangle-vert :vertex
  {:outputs [{:name :frag-color :type :vec3 :location 0}]
   :const-arrays
   [{:name :positions :type :vec2 :size 3
     :values ['(vec2 0.0 -0.5) '(vec2 0.5 0.5) '(vec2 -0.5 0.5)]}
    {:name :colors :type :vec3 :size 3
     :values ['(vec3 1.0 0.0 0.0) '(vec3 0.0 1.0 0.0) '(vec3 0.0 0.0 1.0)]}]}
  (set! gl-Position (vec4 (aget positions gl-VertexIndex) 0.0 1.0))
  (set! frag-color (aget colors gl-VertexIndex)))

(dsl/defshader triangle-frag :fragment
  {:inputs  [{:name :frag-color :type :vec3 :location 0}]
   :outputs [{:name :out-color  :type :vec4 :location 0}]}
  (set! out-color (vec4 frag-color 1.0)))

;; ---------------------------------------------------------------------------
;; Polygon shaders
;; ---------------------------------------------------------------------------

(dsl/defshader polygon-vert :vertex
  {:inputs  [{:name :in-pos :type :vec2 :location 0}]
   :outputs [{:name :frag-color :type :vec4 :location 0}]
   :push-constants [{:name :translation :type :vec2}
                    {:name :rotation    :type :float}
                    {:name :pad         :type :float}
                    {:name :color       :type :vec4}]}
  (let [^float c       (cos (.rotation pc))
        ^float s       (sin (.rotation pc))
        ^vec2  rotated (vec2 (- (* (.x in-pos) c) (* (.y in-pos) s))
                             (+ (* (.x in-pos) s) (* (.y in-pos) c)))
        ^vec2  final   (+ rotated (.translation pc))]
    (set! gl-Position (vec4 final 0.0 1.0))
    (set! frag-color (.color pc))))

(dsl/defshader polygon-frag :fragment
  {:inputs  [{:name :frag-color :type :vec4 :location 0}]
   :outputs [{:name :out-color  :type :vec4 :location 0}]}
  (set! out-color frag-color))

;; ---------------------------------------------------------------------------
;; Sprite shaders
;; ---------------------------------------------------------------------------

(dsl/defshader sprite-vert :vertex
  {:outputs [{:name :frag-uv    :type :vec2 :location 0}
             {:name :frag-color :type :vec4 :location 1}]
   :push-constants [{:name :translation :type :vec2}
                    {:name :rotation    :type :float}
                    {:name :padding     :type :float}
                    {:name :scale       :type :vec2}
                    {:name :padding2    :type :vec2}
                    {:name :color       :type :vec4}]
   :const-arrays
   [{:name :positions :type :vec2 :size 6
     :values ['(vec2 -0.5 -0.5) '(vec2 0.5 -0.5) '(vec2 0.5 0.5)
              '(vec2 -0.5 -0.5) '(vec2 0.5 0.5) '(vec2 -0.5 0.5)]}
    {:name :uvs :type :vec2 :size 6
     :values ['(vec2 0.0 0.0) '(vec2 1.0 0.0) '(vec2 1.0 1.0)
              '(vec2 0.0 0.0) '(vec2 1.0 1.0) '(vec2 0.0 1.0)]}]}
  (let [^vec2  pos (aget positions gl-VertexIndex)
        ^float c   (cos (.rotation pc))
        ^float s   (sin (.rotation pc))]
    (*= pos (.scale pc))
    (set! pos (vec2 (- (* (.x pos) c) (* (.y pos) s))
                    (+ (* (.x pos) s) (* (.y pos) c))))
    (+= pos (.translation pc))
    (set! gl-Position (vec4 pos 0.0 1.0))
    (set! frag-uv (aget uvs gl-VertexIndex))
    (set! frag-color (.color pc))))

(dsl/defshader sprite-frag :fragment
  {:inputs   [{:name :frag-uv    :type :vec2 :location 0}
              {:name :frag-color :type :vec4 :location 1}]
   :outputs  [{:name :out-color :type :vec4 :location 0}]
   :uniforms [{:name :tex-sampler :type :sampler2D :set 0 :binding 0}]}
  (set! out-color (* (texture tex-sampler frag-uv) frag-color)))

;; ---------------------------------------------------------------------------
;; Text shaders
;; ---------------------------------------------------------------------------

(dsl/defshader text-vert :vertex
  {:outputs [{:name :frag-uv    :type :vec2 :location 0}
             {:name :frag-color :type :vec4 :location 1}]
   :push-constants [{:name :pos      :type :vec2}
                    {:name :size     :type :vec2}
                    {:name :uv-pos   :type :vec2}
                    {:name :uv-size  :type :vec2}
                    {:name :color    :type :vec4}]
   :const-arrays
   [{:name :offsets :type :vec2 :size 6
     :values ['(vec2 0.0 0.0) '(vec2 1.0 0.0) '(vec2 1.0 1.0)
              '(vec2 0.0 0.0) '(vec2 1.0 1.0) '(vec2 0.0 1.0)]}]}
  (let [^vec2 off (aget offsets gl-VertexIndex)]
    (set! gl-Position (vec4 (+ (.pos pc) (* off (.size pc))) 0.0 1.0))
    (set! frag-uv (+ (.uv-pos pc) (* off (.uv-size pc))))
    (set! frag-color (.color pc))))

(dsl/defshader text-frag :fragment
  {:inputs   [{:name :frag-uv    :type :vec2 :location 0}
              {:name :frag-color :type :vec4 :location 1}]
   :outputs  [{:name :out-color :type :vec4 :location 0}]
   :uniforms [{:name :font-atlas :type :sampler2D :set 0 :binding 0}]}
  (let [^float alpha (.r (texture font-atlas frag-uv))]
    (set! out-color (vec4 (.rgb frag-color) (* (.a frag-color) alpha)))))
