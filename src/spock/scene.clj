(ns spock.scene
  "Scene record and swap API.

   A Scene holds the identity and entity list for one active scene.
   Scripts request a scene swap via swap! — the engine picks it up
   after the current on-tick returns, never mid-tick.

   Usage (from a script):
     (spock.scene/swap! :menu)               ;; by name registered in game.edn
     (spock.scene/swap! \"scenes/menu.edn\")   ;; or direct path")

;; ---------------------------------------------------------------------------
;; Scene record
;; ---------------------------------------------------------------------------

(defrecord Scene [id       ;; keyword name, e.g. :hello
                  path     ;; file path the scene was loaded from
                  entities ;; atom — current live entity list
                  script]) ;; namespace symbol or nil

(defn make
  "Create a Scene. entities is wrapped in an atom if not already one."
  [id path entities script]
  (->Scene id path (atom (vec entities)) script))

(defn get-entities [^Scene scene] @(:entities scene))

(defn add-entity!
  "Add an entity to the scene's entity list."
  [^Scene scene ent]
  (swap! (:entities scene) conj ent))

(defn remove-entity!
  "Remove entity by id."
  [^Scene scene id]
  (swap! (:entities scene) (fn [es] (vec (remove #(= (:id %) id) es)))))

;; ---------------------------------------------------------------------------
;; Pending swap
;; ---------------------------------------------------------------------------

(defonce ^:private pending-swap (atom nil))

(defn swap!
  "Request a scene transition. target is a keyword (registered scene name)
   or a string (direct EDN file path). Takes effect after the current
   on-tick returns."
  [target]
  (clojure.core/swap! pending-swap (constantly target)))

(defn pending
  "Return the pending swap target, or nil."
  []
  @pending-swap)

(defn clear-pending!
  "Clear the pending swap. Called by the engine after it processes the swap."
  []
  (clojure.core/swap! pending-swap (constantly nil)))
