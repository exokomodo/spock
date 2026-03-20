(ns spock.main
  "Engine entry point for EDN-driven games.
   Usage: lein edn path/to/game.edn"
  (:require [spock.core      :as spock]
            [spock.game.core :as game])
  (:gen-class))

(defn -main [& [edn-path]]
  (when-not edn-path
    (println "Usage: lein edn <path/to/game.edn>")
    (System/exit 1))
  (let [[g lc] (spock/load-game :edn edn-path)]
    (game/start! g lc))
  (System/exit 0))
