(defproject spock "0.1.0-SNAPSHOT"
  :description "A Vulkan game engine written in Clojure"
  :url "https://github.com/exokomodo/spock"
  :license {:name "CC0 1.0 Universal"
            :url "https://creativecommons.org/publicdomain/zero/1.0/"}

  :dependencies [[org.clojure/clojure "1.12.0"]
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
                 [org.lwjgl/lwjgl-openal "3.3.4" :classifier "natives-macos-arm64"]]
  ;; Note: lwjgl-vulkan has no natives — it uses the system Vulkan loader (libvulkan.so)

  :source-paths ["src"]
  :test-paths ["test"]
  :resource-paths ["resources"]

  :profiles
  {:hello {:main hello.core
           :aot [hello.core]
           :source-paths ["src" "examples"]
           ;; macOS requires GLFW on the first thread of the process.
           ;; Linux doesn't need this but it's harmless.
           ;; LWJGL library path is set via JVM_OPTS in the Makefile on macOS;
           ;; the property below is a no-op on Linux.
           :jvm-opts ["-XstartOnFirstThread"
                      ~(str "-Dorg.lwjgl.librarypath="
                            (or (System/getenv "VULKAN_LIB_DIR") "/usr/local/lib"))]}}



  :aliases
  {"hello" ["with-profile" "hello" "run"]})
