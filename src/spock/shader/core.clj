(ns spock.shader.core
  "GLSL → SPIR-V compilation and SPIR-V loading.

   Uses lwjgl-shaderc for in-process compilation — no glslc subprocess required.
   Falls back to the glslc subprocess when shaderc is unavailable (e.g. unsupported platform)."
  (:require [spock.log :as log])
  (:import [java.nio.file Files Paths]
           [java.nio ByteOrder ByteBuffer]
           [org.lwjgl.util.shaderc Shaderc]))

;; ---------------------------------------------------------------------------
;; Stage detection
;; ---------------------------------------------------------------------------

(defn- infer-stage
  "Infer the shaderc shader kind from the file extension."
  [source-path]
  (cond
    (.endsWith ^String source-path ".vert") Shaderc/shaderc_vertex_shader
    (.endsWith ^String source-path ".frag") Shaderc/shaderc_fragment_shader
    (.endsWith ^String source-path ".comp") Shaderc/shaderc_compute_shader
    (.endsWith ^String source-path ".geom") Shaderc/shaderc_geometry_shader
    :else (throw (RuntimeException. (str "Unknown shader stage for: " source-path)))))

;; ---------------------------------------------------------------------------
;; In-process compilation via shaderc
;; ---------------------------------------------------------------------------

(defn- compile-with-shaderc
  "Compile GLSL source to SPIR-V using lwjgl-shaderc.
   Writes the .spv file alongside the source. Returns true on success."
  [source-path]
  (let [spv-path (str source-path ".spv")
        source   (slurp (java.io.File. ^String source-path))
        stage    (infer-stage source-path)
        compiler (Shaderc/shaderc_compiler_initialize)
        options  (Shaderc/shaderc_compile_options_initialize)]
    (try
      ;; Target Vulkan 1.0 / SPIR-V 1.0 — broadest compatibility
      (Shaderc/shaderc_compile_options_set_target_env
       options
       Shaderc/shaderc_target_env_vulkan
       Shaderc/shaderc_env_version_vulkan_1_0)
      (Shaderc/shaderc_compile_options_set_target_spirv
       options
       Shaderc/shaderc_spirv_version_1_0)
      (let [result (Shaderc/shaderc_compile_into_spv
                    compiler
                    source
                    stage
                    source-path
                    "main"
                    options)
            status (Shaderc/shaderc_result_get_compilation_status result)]
        (try
          (if (= status Shaderc/shaderc_compilation_status_success)
            (let [spv-buf (Shaderc/shaderc_result_get_bytes result)
                  len     (Shaderc/shaderc_result_get_length result)
                  bytes   (byte-array len)]
              (.get spv-buf bytes)
              (Files/write (Paths/get spv-path (make-array String 0))
                           bytes
                           (make-array java.nio.file.OpenOption 0))
              (log/debug "shader/compile-glsl OK:" source-path)
              true)
            (let [errors  (Shaderc/shaderc_result_get_error_message result)
                  nwarn   (Shaderc/shaderc_result_get_num_warnings result)]
              (when (pos? nwarn)
                (log/warn "shader warnings for" source-path ":" nwarn))
              (throw (RuntimeException.
                      (str "shaderc failed for " source-path ":\n" errors)))))
          (finally
            (Shaderc/shaderc_result_release result))))
      (finally
        (Shaderc/shaderc_compile_options_release options)
        (Shaderc/shaderc_compiler_release compiler)))))

;; ---------------------------------------------------------------------------
;; Subprocess fallback (glslc)
;; ---------------------------------------------------------------------------

(defn- compile-with-glslc
  "Fallback: invoke glslc subprocess to compile GLSL to SPIR-V."
  [source-path]
  (let [spv-path (str source-path ".spv")
        proc     (-> (ProcessBuilder. ["glslc" source-path "-o" spv-path])
                     (.redirectErrorStream true)
                     (.start))
        output   (slurp (.getInputStream proc))
        result   (.waitFor proc)]
    (when (not= result 0)
      (throw (RuntimeException.
              (str "glslc failed for " source-path ":\n" output))))
    (log/debug "shader/compile-glsl (glslc fallback) OK:" source-path)
    true))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn compile-glsl
  "Compile a GLSL source file to SPIR-V.
   Tries in-process shaderc first; falls back to the glslc subprocess.
   Returns true on success, throws RuntimeException on failure.
   Output .spv is written alongside the source file."
  [source-path]
  (try
    (compile-with-shaderc source-path)
    (catch UnsatisfiedLinkError _
      (log/warn "shaderc native not available, falling back to glslc subprocess")
      (compile-with-glslc source-path))))

(defn load-spirv
  "Load a compiled SPIR-V (.spv) file.
   Returns a java.nio.ByteBuffer suitable for VkShaderModuleCreateInfo,
   or nil on failure."
  [spv-path]
  (try
    (let [path  (Paths/get spv-path (make-array String 0))
          bytes (Files/readAllBytes path)
          buf   (doto (ByteBuffer/allocateDirect (count bytes))
                  (.order ByteOrder/LITTLE_ENDIAN)
                  (.put bytes)
                  (.flip))]
      buf)
    (catch Exception e
      (println (str "[spock.shader] Failed to load SPIR-V: " spv-path " — " (.getMessage e)))
      nil)))
