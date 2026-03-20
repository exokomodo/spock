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
  {;; dev profile — used by Calva jack-in and interactive development.
   ;; Carries -XstartOnFirstThread on macOS so GLFW can run from the REPL
   ;; session, and adds examples/ to the source path so hello.core is visible.
   :dev   {:source-paths ["src" "examples"]
           :jvm-opts ~(cond-> ["-Dorg.lwjgl.library.path=natives"]
                        (= "Mac OS X" (System/getProperty "os.name"))
                        (conj "-XstartOnFirstThread"
                              (str "-Dorg.lwjgl.vulkan.libname="
                                   (or (System/getenv "VULKAN_LOADER")
                                       "/usr/local/lib/libvulkan.1.dylib"))))}

   :edn   {:main spock.main
           :aot [spock.main]
           :source-paths ["src" "examples"]
           :jvm-opts ~(cond-> ["-Dorg.lwjgl.library.path=natives"]
                        (= "Mac OS X" (System/getProperty "os.name"))
                        (conj "-XstartOnFirstThread"
                              (str "-Dorg.lwjgl.vulkan.libname="
                                   (or (System/getenv "VULKAN_LOADER")
                                       "/usr/local/lib/libvulkan.1.dylib"))))}
   :hello {:main hello.core
           :aot [hello.core]
           :source-paths ["src" "examples"]
           ;; macOS requires GLFW on the first thread of the process.
           ;; -XstartOnFirstThread is macOS-only and must NOT be passed on Linux
           ;; (the JVM will refuse to start with an unrecognised option).
           ;; LWJGL library path is set via JVM_OPTS / VULKAN_LOADER on macOS.
           :jvm-opts ~(cond-> ["-Dorg.lwjgl.library.path=natives"]
                        (= "Mac OS X" (System/getProperty "os.name"))
                        (conj "-XstartOnFirstThread"
                              (str "-Dorg.lwjgl.vulkan.libname="
                                   (or (System/getenv "VULKAN_LOADER")
                                       "/usr/local/lib/libvulkan.1.dylib"))))}}



  :aliases
  {"hello"  ["with-profile" "hello" "run"]
   "record" ["with-profile" "hello" "run" "--" "--record"]
   "edn"    ["with-profile" "edn"   "run" "--"]})
