(defproject spock "0.1.0-SNAPSHOT"
  :description "A Vulkan game engine written in Clojure"
  :url "https://github.com/exokomodo/spock"
  :license {:name "CC0 1.0 Universal"
            :url "https://creativecommons.org/publicdomain/zero/1.0/"}

  :dependencies [[org.clojure/clojure "1.12.0"]
                 ;; Shader DSL + SPIR-V compiler (shaderc natives included transitively)
                 [com.exokomodo/warpaint "0.1.0"]
                 ;; LWJGL BOM — core + Vulkan + GLFW + OpenAL + natives
                 [org.lwjgl/lwjgl "3.3.4"]
                 [org.lwjgl/lwjgl-vulkan "3.3.4"]
                 [org.lwjgl/lwjgl-glfw "3.3.4"]
                 [org.lwjgl/lwjgl-openal "3.3.4"]
                 ;; Natives — Linux aarch64 (Raspberry Pi 5) + x86_64
                 [org.lwjgl/lwjgl "3.3.4" :classifier "natives-linux-arm64"]
                 [org.lwjgl/lwjgl-glfw "3.3.4" :classifier "natives-linux-arm64"]
                 [org.lwjgl/lwjgl-openal "3.3.4" :classifier "natives-linux-arm64"]
                 [org.lwjgl/lwjgl "3.3.4" :classifier "natives-linux"]
                 [org.lwjgl/lwjgl-glfw "3.3.4" :classifier "natives-linux"]
                 [org.lwjgl/lwjgl-openal "3.3.4" :classifier "natives-linux"]
                 ;; Natives — macOS (Intel + Apple Silicon)
                 [org.lwjgl/lwjgl "3.3.4" :classifier "natives-macos"]
                 [org.lwjgl/lwjgl-glfw "3.3.4" :classifier "natives-macos"]
                 [org.lwjgl/lwjgl-openal "3.3.4" :classifier "natives-macos"]
                 [org.lwjgl/lwjgl "3.3.4" :classifier "natives-macos-arm64"]
                 [org.lwjgl/lwjgl-glfw "3.3.4" :classifier "natives-macos-arm64"]
                 [org.lwjgl/lwjgl-openal "3.3.4" :classifier "natives-macos-arm64"]
                 ;; STB — vorbis OGG decoder
                 [org.lwjgl/lwjgl-stb "3.3.4"]
                 [org.lwjgl/lwjgl-stb "3.3.4" :classifier "natives-linux-arm64"]
                 [org.lwjgl/lwjgl-stb "3.3.4" :classifier "natives-linux"]
                 [org.lwjgl/lwjgl-stb "3.3.4" :classifier "natives-macos"]
                 [org.lwjgl/lwjgl-stb "3.3.4" :classifier "natives-macos-arm64"]]
  ;; Note: lwjgl-vulkan has no natives — it uses the system Vulkan loader (libvulkan.so)

  :source-paths ["src"]
  :test-paths ["test"]
  :resource-paths ["resources"]

  :profiles
  {:dev {:plugins [[lein-cljfmt "0.9.2"]]}
   :hello {:main spock.main
           :aot [spock.main]
           :source-paths ["src" "examples" "examples/hello/scripts"]
           :jvm-opts ~(cond-> ["-Dorg.lwjgl.library.path=natives"]
                        (= "Mac OS X" (System/getProperty "os.name"))
                        (conj "-XstartOnFirstThread"
                              (str "-Dorg.lwjgl.vulkan.libname="
                                   (or (System/getenv "VULKAN_LOADER")
                                       "/usr/local/lib/libvulkan.1.dylib"))))}
   :spin-shooter {:main spock.main
                  :aot [spock.main]
                  :source-paths ["src" "examples"]
                  :jvm-opts ~(cond-> ["-Dorg.lwjgl.library.path=natives"]
                               (= "Mac OS X" (System/getProperty "os.name"))
                               (conj "-XstartOnFirstThread"
                                     (str "-Dorg.lwjgl.vulkan.libname="
                                          (or (System/getenv "VULKAN_LOADER")
                                              "/usr/local/lib/libvulkan.1.dylib")))))
   :teapot {:main spock.main
            :aot [spock.main]
            :source-paths ["src" "examples" "examples/teapot/scripts"]
            :jvm-opts ~(cond-> ["-Dorg.lwjgl.library.path=natives"]
                         (= "Mac OS X" (System/getProperty "os.name"))
                         (conj "-XstartOnFirstThread"
                               (str "-Dorg.lwjgl.vulkan.libname="
                                    (or (System/getenv "VULKAN_LOADER")
                                        "/usr/local/lib/libvulkan.1.dylib"))))}}

  :aliases
  {"hello"        ["with-profile" "hello" "run" "--" "examples/hello/game.edn"]
   "spin-shooter" ["with-profile" "spin-shooter" "run" "--"
                   "examples/spin_shooter/game.edn"]
   "teapot"       ["with-profile" "teapot" "run" "--"
                   "examples/teapot/game.edn"]})
