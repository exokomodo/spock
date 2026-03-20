(ns spock.main
  "Engine entry point for EDN-driven games.
   Usage: lein edn path/to/game.edn"
  (:require [spock.edn       :as spock-edn]
            [spock.game.core :as game])
  (:gen-class))

(defn -main [& [edn-path]]
  (when-not edn-path
    (println "Usage: lein edn <path/to/game.edn>")
    (System/exit 1))
  (let [[g lc] (spock-edn/load-game edn-path)]
    (game/start! g lc))
  (System/exit 0))
