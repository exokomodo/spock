(ns spock.shader.core
  "GLSL → SPIR-V compilation and SPIR-V loading.
   Mirrors drakon::Renderer::compileGlslShader + loadCompiledShader."
  (:import [java.nio.file Files Paths]
           [java.nio ByteOrder ByteBuffer]))

(defn compile-glsl
  "Invoke glslc to compile a GLSL source file to SPIR-V.
   Returns true on success, false on failure.
   Output is written to <source-path>.spv."
  [source-path]
  (let [spv-path (str source-path ".spv")
        result   (-> (ProcessBuilder. ["glslc" source-path "-o" spv-path])
                     (.redirectErrorStream true)
                     (.start)
                     (.waitFor))]
    (when (not= result 0)
      (println (str "[spock.shader] Failed to compile: " source-path)))
    (= result 0)))

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
