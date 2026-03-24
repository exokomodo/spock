(ns spock.settings
  "Global settings loader.

   settings.edn provides renderer/window defaults. Values are merged in
   this order (most specific wins):
     settings.edn → game.edn :state defaults → scene.edn

   Usage:
     (settings/load! \"settings.edn\")
     (:width @settings/settings)  ;; => 1280"
  (:require [clojure.edn     :as edn]
            [clojure.java.io :as io]))

(def defaults
  {:width         1280
   :height        720
   :title         "Spock"
   :vsync         false
   :max-fps       0
   :master-volume 1.0})

(defonce settings (atom defaults))

(defn load!
  "Load settings from path and merge over defaults.
   Also applies :log-level and :log-file to spock.log.
   Applies :master-volume to spock.audio.core.
   Returns the merged map."
  [path]
  (let [merged (if (.exists (io/file path))
                 (let [overrides (-> (slurp (io/file path)) edn/read-string)]
                   (merge defaults overrides))
                 defaults)]
    (reset! settings merged)
    ;; Apply master volume if audio is already initialized — require lazily to avoid circular dependency
    (let [set-vol! (requiring-resolve 'spock.audio.core/set-master-volume!)
          initialized? (requiring-resolve 'spock.audio.core/initialized?)]
      (when (initialized?)
        (set-vol! (double (:master-volume merged 1.0)))))
    merged))

(defn merge-scene
  "Merge scene-level overrides (width, height, title) on top of current settings.
   Returns the merged map without mutating the settings atom."
  [scene-cfg]
  (merge @settings (select-keys scene-cfg [:width :height :title :vsync :max-fps])))
