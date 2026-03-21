(ns spock.shader.dsl
  "Minimal s-expression shader DSL for Spock.

   Emits GLSL 450 source from Clojure data, then compiles to SPIR-V via
   spock.shader.core (shaderc in-process).

   ## Example — polygon vertex shader:

   ```clojure
   (defshader polygon-vert :vertex
     {:inputs  [{:name :in-pos :type :vec2 :location 0}]
      :outputs [{:name :frag-color :type :vec4 :location 0}]
      :push-constants [{:name :translation :type :vec2}
                       {:name :rotation    :type :float}
                       {:name :padding     :type :float}
                       {:name :color       :type :vec4}]}
     (let [c (cos (.rotation pc))
           s (sin (.rotation pc))
           rotated (vec2 (- (* (.x in-pos) c) (* (.y in-pos) s))
                         (+ (* (.x in-pos) s) (* (.y in-pos) c)))
           final (+ rotated (.translation pc))]
       (set! gl-Position (vec4 final 0.0 1.0))
       (set! frag-color (.color pc))))
   ```

   The macro compiles at load time and stores a SPIR-V ByteBuffer in the var.
   Use (pipeline/vert-spv cfg my-shader) instead of (pipeline/vert-path ...)."
  (:require [spock.shader.core :as shader])
  (:import [java.io File]))

;; ---------------------------------------------------------------------------
;; Type map
;; ---------------------------------------------------------------------------

(def ^:private glsl-types
  {:float   "float"
   :int     "int"
   :uint    "uint"
   :bool    "bool"
   :vec2    "vec2"
   :vec3    "vec3"
   :vec4    "vec4"
   :mat2    "mat2"
   :mat3    "mat3"
   :mat4    "mat4"
   :ivec2   "ivec2"
   :ivec3   "ivec3"
   :ivec4   "ivec4"
   :sampler2D "sampler2D"})

(defn- glsl-type [k]
  (or (get glsl-types k) (throw (ex-info (str "Unknown GLSL type: " k) {:type k}))))

;; ---------------------------------------------------------------------------
;; Identifier helpers
;; ---------------------------------------------------------------------------

(defn- ident
  "Convert a Clojure keyword/symbol to a GLSL identifier.
   :in-pos → inPos, :frag-color → fragColor (camelCase)."
  [k]
  (let [s (name k)
        parts (clojure.string/split s #"-")]
    (str (first parts)
         (apply str (map clojure.string/capitalize (rest parts))))))

;; ---------------------------------------------------------------------------
;; Expression emitter
;; ---------------------------------------------------------------------------

(declare emit-expr)

(defn- emit-args [args]
  (clojure.string/join ", " (map emit-expr args)))

(defn- emit-expr [form]
  (cond
    ;; nil / boolean
    (nil? form)     "null"
    (true? form)    "true"
    (false? form)   "false"
    ;; numbers
    (integer? form) (str form)
    (float? form)   (let [s (str form)]
                      ;; ensure GLSL sees a float literal
                      (if (.contains s ".") s (str s ".0")))
    (double? form)  (let [s (str form)]
                      (if (.contains s ".") s (str s ".0")))
    ;; keywords → identifiers
    (keyword? form) (ident form)
    ;; symbols
    (symbol? form)
    (case form
      gl-Position   "gl_Position"
      gl-VertexIndex "gl_VertexIndex"
      gl-FragCoord  "gl_FragCoord"
      gl-PointSize  "gl_PointSize"
      ;; default: camelCase the symbol name
      (let [s (name form)
            parts (clojure.string/split s #"-")]
        (str (first parts)
             (apply str (map clojure.string/capitalize (rest parts))))))
    ;; list forms
    (seq? form)
    (let [[op & args] form]
      (case op
        ;; field access: (.x v) → v.x
        (.x .y .z .w .r .g .b .a .xy .xyz .xyzw .zw .yz .translation .rotation .color
            .scale .pos .size .uv-pos .uv-size .padding .padding2)
        (str (emit-expr (first args)) "." (subs (name op) 1))
        ;; array index: (aget arr i) → arr[i]
        aget
        (str (emit-expr (first args)) "[" (emit-expr (second args)) "]")
        ;; binary operators
        (+ - * /)
        (str "(" (emit-expr (first args)) " " (name op) " " (emit-expr (second args)) ")")
        ;; comparison / logical
        (= not= < > <= >= and or not)
        (let [glsl-op (case op = "==" not= "!=" < "<" > ">" <= "<=" >= ">="
                            and "&&" or "||" not "!")]
          (if (= op 'not)
            (str "(!" (emit-expr (first args)) ")")
            (str "(" (emit-expr (first args)) " " glsl-op " " (emit-expr (second args)) ")")))
        ;; set! (assignment)
        set!
        (str (emit-expr (first args)) " = " (emit-expr (second args)))
        ;; let bindings → local declarations
        let
        (let [[bindings & body] args
              pairs (partition 2 bindings)
              decls (map (fn [[name val]]
                           (str "  // let " (emit-expr name) "\n"
                                "  auto " (emit-expr name) " = " (emit-expr val) ";"))
                         pairs)
              stmts (map #(str "  " (emit-expr %) ";") body)]
          (clojure.string/join "\n" (concat decls stmts)))
        ;; when / if
        when
        (str "if (" (emit-expr (first args)) ") {\n"
             (clojure.string/join "\n" (map #(str "    " (emit-expr %) ";") (rest args)))
             "\n  }")
        if
        (str "(" (emit-expr (first args)) " ? "
             (emit-expr (second args)) " : " (emit-expr (nth args 2)) ")")
        ;; do / begin
        do
        (clojure.string/join "\n  " (map #(str (emit-expr %) ";") args))
        ;; function calls: (vec4 x y z w), (cos x), (dot a b), etc.
        (str (name op) "(" (emit-args args) ")")))
    :else (str form)))

;; ---------------------------------------------------------------------------
;; Declaration emitters
;; ---------------------------------------------------------------------------

(defn- emit-inputs [inputs]
  (map (fn [{:keys [name type location]}]
         (str "layout(location = " location ") in " (glsl-type type) " " (ident name) ";"))
       inputs))

(defn- emit-outputs [outputs]
  (map (fn [{:keys [name type location]}]
         (str "layout(location = " location ") out " (glsl-type type) " " (ident name) ";"))
       outputs))

(defn- emit-push-constants [fields]
  (when (seq fields)
    (let [field-lines (map (fn [{:keys [name type]}]
                             (str "    " (glsl-type type) " " (ident name) ";"))
                           fields)]
      [(str "layout(push_constant) uniform PushConstants {\n"
            (clojure.string/join "\n" field-lines) "\n"
            "} pc;")])))

(defn- emit-uniforms [uniforms]
  (map (fn [{:keys [name type set binding]}]
         (str "layout(set = " (or set 0)
              ", binding = " (or binding 0)
              ") uniform " (glsl-type type) " " (ident name) ";"))
       uniforms))

(defn- emit-vertex-arrays [arrays]
  ;; constant arrays inside main, e.g. positions/uvs for hardcoded quads
  (map (fn [{:keys [name type size values]}]
         (str "const " (glsl-type type) " " (ident name)
              "[" size "] = " (glsl-type type) "[](\n    "
              (clojure.string/join ",\n    "
                                   (map (fn [v] (emit-expr v)) values))
              "\n);"))
       arrays))

;; ---------------------------------------------------------------------------
;; Body emitter
;; ---------------------------------------------------------------------------

(defn- emit-body [forms]
  (clojure.string/join "\n    "
                       (map (fn [f]
                              (let [s (emit-expr f)]
                                (if (.endsWith s ";") s (str s ";"))))
                            forms)))

;; ---------------------------------------------------------------------------
;; Full shader emitter
;; ---------------------------------------------------------------------------

(defn emit-glsl
  "Emit a GLSL 450 string from a shader descriptor map.

   descriptor keys:
   - :stage        — :vertex | :fragment | :compute
   - :inputs       — [{:name :foo :type :vec2 :location 0} ...]
   - :outputs      — [{:name :bar :type :vec4 :location 0} ...]
   - :push-constants — [{:name :field :type :vec2} ...]
   - :uniforms     — [{:name :sampler :type :sampler2D :set 0 :binding 0} ...]
   - :const-arrays — [{:name :positions :type :vec2 :size 6 :values [...]} ...]
   - :main         — seq of s-expression forms for void main() {}"
  [{:keys [inputs outputs push-constants uniforms const-arrays main]}]
  (let [lines (concat
               ["#version 450" ""]
               (emit-inputs (or inputs []))
               (when (seq inputs) [""])
               (emit-outputs (or outputs []))
               (when (seq outputs) [""])
               (emit-push-constants push-constants)
               (when (seq push-constants) [""])
               (emit-uniforms (or uniforms []))
               (when (seq uniforms) [""])
               ["void main() {"]
               (when (seq const-arrays)
                 (concat (emit-vertex-arrays const-arrays) [""]))
               [(str "    " (emit-body (or main [])))]
               ["}"])]
    (clojure.string/join "\n" (filter some? lines))))

;; ---------------------------------------------------------------------------
;; compile-shader — emit GLSL → compile via shaderc → return SPIR-V ByteBuffer
;; ---------------------------------------------------------------------------

(defn compile-shader
  "Compile a shader descriptor map to a SPIR-V ByteBuffer.
   Writes a temporary .glsl file, compiles with shaderc, returns the buffer."
  [descriptor]
  (let [stage      (:stage descriptor)
        ext        (case stage
                     :vertex   ".vert"
                     :fragment ".frag"
                     :compute  ".comp"
                     (throw (ex-info "Unknown shader stage" {:stage stage})))
        glsl       (emit-glsl descriptor)
        tmp        (doto (File/createTempFile "spock_shader_" ext)
                     (.deleteOnExit))
        tmp-path   (.getAbsolutePath tmp)]
    (spit tmp-path glsl)
    (shader/compile-glsl tmp-path)
    (shader/load-spirv (str tmp-path ".spv"))))

;; ---------------------------------------------------------------------------
;; defshader macro
;; ---------------------------------------------------------------------------

(defmacro defshader
  "Define a shader as a Clojure var. Compiles at load time to a SPIR-V ByteBuffer.

   Usage:
     (defshader my-vert :vertex
       {:inputs  [{:name :in-pos :type :vec2 :location 0}]
        :outputs [{:name :frag-color :type :vec4 :location 0}]
        :push-constants [{:name :translation :type :vec2}
                         {:name :rotation :type :float}
                         {:name :pad :type :float}
                         {:name :color :type :vec4}]}
       (set! gl-Position (vec4 (.translation pc) 0.0 1.0))
       (set! frag-color (.color pc)))

   The resulting var holds a SPIR-V ByteBuffer.
   Use: (pipeline/vert-spv cfg my-vert)"
  [shader-name stage descriptor & body]
  `(def ~shader-name
     (compile-shader (assoc ~descriptor
                            :stage ~stage
                            :main (quote ~(vec body))))))
