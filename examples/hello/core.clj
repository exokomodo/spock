(ns hello.core
  "Hello Vulkan — entry point. Loads the scene from game.edn."
  (:require [spock.core      :as spock]
            [spock.game.core :as game])
  (:gen-class))

(defn -main [& _args]
  (let [[g lc] (spock/load-game :edn "examples/hello/game.edn")]
    (game/start! g lc))
  (System/exit 0))
