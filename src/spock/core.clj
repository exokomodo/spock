(ns spock.core
  "Top-level spock API.

   (spock/load-game \"game.edn\")   → [game lifecycle]  ; full game with registry
   (spock/load-game \"scene.edn\")  → [game lifecycle]  ; bare scene, backward compat
   (game/start! game lifecycle)"
  (:require [spock.edn                :as edn-loader]
            [spock.renderable.registry :as registry]
            [clojure.string            :as str]))

(defn load-game
  "Load a game or scene EDN file. Returns [game lifecycle].
   Detects game.edn (has :entry or :scenes key) vs bare scene.edn automatically."
  ([path]
   (let [cfg (-> (slurp path) clojure.edn/read-string)]
     (if (or (:entry cfg) (:scenes cfg))
       (edn-loader/load-game path)
       (edn-loader/load-bare-scene path))))
  ([fmt path]
   (case fmt
     :edn  (load-game path)
     :game (edn-loader/load-game path)
     :scene (edn-loader/load-bare-scene path)
     (throw (ex-info (str "Unknown format: " fmt) {:format fmt})))))

;; Re-export renderable registry — games should require spock.core, not the registry directly
(def register-renderable! registry/register-renderable!)
