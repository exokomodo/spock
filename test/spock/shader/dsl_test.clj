(ns spock.shader.dsl-test
  "Tests for the shader DSL — verifies GLSL emission and round-trip compilation."
  (:require [clojure.test :refer [deftest is testing]]
            [spock.shader.dsl :as dsl]))

;; ---------------------------------------------------------------------------
;; emit-glsl tests — no GPU needed
;; ---------------------------------------------------------------------------

(deftest emit-glsl-version
  (testing "emits #version 450 header"
    (let [glsl (dsl/emit-glsl {})]
      (is (.startsWith glsl "#version 450")))))

(deftest emit-glsl-inputs
  (testing "emits layout(location=N) in declarations"
    (let [glsl (dsl/emit-glsl
                {:inputs [{:name :in-pos :type :vec2 :location 0}
                          {:name :in-color :type :vec4 :location 1}]})]
      (is (.contains glsl "layout(location = 0) in vec2 inPos;"))
      (is (.contains glsl "layout(location = 1) in vec4 inColor;")))))

(deftest emit-glsl-outputs
  (testing "emits layout(location=N) out declarations"
    (let [glsl (dsl/emit-glsl
                {:outputs [{:name :frag-color :type :vec4 :location 0}]})]
      (is (.contains glsl "layout(location = 0) out vec4 fragColor;")))))

(deftest emit-glsl-push-constants
  (testing "emits push_constant uniform block"
    (let [glsl (dsl/emit-glsl
                {:push-constants [{:name :translation :type :vec2}
                                  {:name :color :type :vec4}]})]
      (is (.contains glsl "layout(push_constant) uniform PushConstants {"))
      (is (.contains glsl "vec2 translation;"))
      (is (.contains glsl "vec4 color;"))
      (is (.contains glsl "} pc;")))))

(deftest emit-glsl-uniforms
  (testing "emits set/binding uniform declarations"
    (let [glsl (dsl/emit-glsl
                {:uniforms [{:name :font-atlas :type :sampler2D :set 0 :binding 0}]})]
      (is (.contains glsl "layout(set = 0, binding = 0) uniform sampler2D fontAtlas;")))))

(deftest emit-glsl-main-set
  (testing "emits set! as assignment"
    (let [glsl (dsl/emit-glsl
                {:main '[(set! gl-Position (vec4 0.0 0.0 0.0 1.0))]})]
      (is (.contains glsl "gl_Position = vec4(0.0, 0.0, 0.0, 1.0);")))))

(deftest emit-glsl-field-access
  (testing "emits .field as field access"
    (let [glsl (dsl/emit-glsl
                {:main '[(set! out-color (.color pc))]})]
      (is (.contains glsl "pc.color")))))

(deftest emit-glsl-camel-case-idents
  (testing "converts kebab-case keywords to camelCase identifiers"
    (let [glsl (dsl/emit-glsl
                {:inputs [{:name :in-uv-coord :type :vec2 :location 0}]})]
      (is (.contains glsl "inUvCoord")))))

;; ---------------------------------------------------------------------------
;; Compile round-trip tests — requires shaderc native
;; ---------------------------------------------------------------------------

(deftest compile-passthrough-frag
  (testing "minimal passthrough fragment shader compiles to SPIR-V"
    (let [buf (dsl/compile-shader
               {:stage   :fragment
                :inputs  [{:name :frag-color :type :vec4 :location 0}]
                :outputs [{:name :out-color :type :vec4 :location 0}]
                :main    '[(set! out-color frag-color)]})]
      (is (some? buf))
      (is (pos? (.capacity buf))))))

(deftest compile-passthrough-vert
  (testing "minimal passthrough vertex shader compiles to SPIR-V"
    (let [buf (dsl/compile-shader
               {:stage   :vertex
                :outputs [{:name :frag-color :type :vec4 :location 0}]
                :push-constants [{:name :color :type :vec4}]
                :main    '[(set! gl-Position (vec4 0.0 0.0 0.0 1.0))
                           (set! frag-color (.color pc))]})]
      (is (some? buf))
      (is (pos? (.capacity buf))))))

(deftest edn-inline-shader-round-trip
  (testing "DSL descriptor read from EDN string compiles to SPIR-V"
    (let [edn-str "{:outputs [{:name :frag-color :type :vec3 :location 0}]
                    :main [(set! gl-Position (vec4 0.0 0.0 0.0 1.0))
                           (set! frag-color (vec3 1.0 0.0 0.0))]}"
          descriptor (clojure.edn/read-string edn-str)
          buf (dsl/compile-shader (assoc descriptor :stage :vertex))]
      (is (some? buf))
      (is (pos? (.capacity buf))))))
