(ns spock.import-test)
(defn test-it []
  (let [cls (Class/forName "org.lwjgl.vulkan.VkSurfaceFormatKHR$Buffer")]
    (println "found:" cls)))
