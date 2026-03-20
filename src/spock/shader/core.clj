(ns spock.shader.core
  "GLSL → SPIR-V compilation and SPIR-V loading.
   Mirrors drakon::Renderer::compileGlslShader + loadCompiledShader."
  (:import [java.nio.file Files Paths]
           [java.nio ByteOrder ByteBuffer]))

(defn compile-glsl
  "Invoke glslc to compile a GLSL source file to SPIR-V.
   Returns true on success, throws RuntimeException with compiler output on failure.
   Output .spv is written alongside the source file."
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
    true))

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
