(ns spock.entity
  "Entity system.

   An entity is a named container of components.
   Components are plain maps or protocol implementations stored by keyword.

   Built-in component keys the engine knows about:
     :renderable — anything implementing spock.renderable.core/Renderable
     :transform  — {:position [x y z] :rotation [x y z] :scale [x y z]}

   Scripts and user code can attach arbitrary component keys.

   Usage:
     (entity/make :player {:renderable my-renderable
                           :transform  {:position [0 0 0]}})

     (entity/get-component e :renderable)
     (entity/put-component  e :transform {:position [1 0 0]})
     (entity/renderable?    e)"
  (:require [spock.renderable.core :as renderable]))

;; ---------------------------------------------------------------------------
;; Entity record
;; ---------------------------------------------------------------------------

(defrecord Entity [id components])

(defn make
  "Create an entity.
   id         — keyword identifier (e.g. :player). Use (gensym) for anonymous entities.
   components — map of component keyword → value (default {})."
  ([id]             (->Entity id {}))
  ([id components]  (->Entity id components)))

(defn get-component
  "Return the component at key k, or nil."
  [^Entity entity k]
  (get (:components entity) k))

(defn put-component
  "Return a new entity with component k set to v."
  [^Entity entity k v]
  (update entity :components assoc k v))

(defn remove-component
  "Return a new entity with component k removed."
  [^Entity entity k]
  (update entity :components dissoc k))

(defn renderable?
  "True if the entity has a :renderable component."
  [^Entity entity]
  (some? (get-component entity :renderable)))

;; ---------------------------------------------------------------------------
;; Convenience constructors
;; ---------------------------------------------------------------------------

(defn from-renderable
  "Wrap a bare Renderable in an anonymous entity.
   Used for backward compatibility with add-renderable!."
  [r]
  (make (keyword (gensym "entity-")) {:renderable r}))

;; ---------------------------------------------------------------------------
;; Engine helpers
;; ---------------------------------------------------------------------------

(defn renderables
  "Return a seq of all :renderable components from a collection of entities."
  [entities]
  (->> entities
       (filter renderable?)
       (map #(get-component % :renderable))))

(defn cleanup-all!
  "Call renderable/cleanup! on every entity that has a :renderable component."
  [entities device]
  (doseq [e entities]
    (when-let [r (get-component e :renderable)]
      (try (renderable/cleanup! r device)
           (catch Exception ex
             (println "[entity] cleanup! failed for" (:id e) ":" (.getMessage ex)))))))
