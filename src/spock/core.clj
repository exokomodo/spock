(ns spock.core
  "Top-level spock API.

   (spock/load-game :edn \"game.edn\")  → [game lifecycle]
   (game/start! game lifecycle)"
  (:require [spock.edn :as edn-loader]))

;; ---------------------------------------------------------------------------
;; load-game multimethod — dispatch on format keyword
;; ---------------------------------------------------------------------------

(defmulti load-game
  "Load a game definition from a file.
   Returns [game lifecycle] ready to pass to game/start!.

   Dispatch value is a format keyword:
     :edn — EDN file with :title, :width, :height, :script, :renderables"
  (fn [fmt _path] fmt))

(defmethod load-game :edn [_ path]
  (edn-loader/load-game path))

(defmethod load-game :default [fmt _path]
  (throw (ex-info (str "Unknown game format: " fmt
                       ". Supported: :edn")
                  {:format fmt})))

;; Re-export renderable registry so users only need to require spock.core
(def register-renderable! edn-loader/register-renderable!)
