(ns spock.edn
  "EDN-driven game loader.

   Two entry-point formats:

   scene.edn — single scene, no registry, no shared state:
     {:title  \"My Game\"
      :script my.script
      :entities [{:id :foo :renderable {:type :triangle :shaders {...}}}
                 {:file \"entities/bar.edn\"}]}

   game.edn — full game: registry, settings, shared state, scene swapping:
     {:entry    :hello
      :settings \"settings.edn\"
      :state    {:score 0}
      :scenes   [{:name :hello :file \"scenes/hello.edn\"}
                 {:name :menu  :file \"scenes/menu.edn\"}]}

   Usage:
     (def [game lifecycle] (load-game \"game.edn\"))
     (game/start! game lifecycle)"
  (:require [clojure.edn      :as edn]
            [clojure.java.io  :as io]
            [spock.game.core  :as game]
            [spock.scene      :as scene]
            [spock.settings   :as settings]
            [spock.entity     :as entity]
            [spock.renderable.registry :as registry]
            ;; Require built-in renderable namespaces to trigger their registrations
            [spock.renderable.triangle]
            [spock.renderable.polygon]
            [spock.renderable.sprite]
            [spock.renderable.text]
            [spock.renderable.mesh]
            [spock.renderable.text]
            [spock.renderer.core   :as renderer]
            [spock.audio.core      :as audio]
            [spock.log             :as log]))

;; ---------------------------------------------------------------------------
;; Component instantiation
;; ---------------------------------------------------------------------------

(defn- instantiate-component [k v renderer]
  (case k
    :renderable (registry/make-renderable v renderer)
    :audio      (audio/load-sound! (:file v))
    v))

(defn- post-init-component! [k v renderer]
  (when (= k :renderable)
    (let [m (meta v)]
      (cond
        (contains? m :vert-shader) (spock.renderable.triangle/build-pipeline! v renderer)
        (contains? m :vbuf-atom)   (spock.renderable.polygon/build-pipeline! v renderer)
        (contains? m :image-path)  (spock.renderable.sprite/build-pipeline! v renderer)
        (contains? m :font-path)   (spock.renderable.text/build-pipeline! v renderer)
        (contains? m :obj-path)    (spock.renderable.mesh/build-pipeline! v renderer)))))

;; ---------------------------------------------------------------------------
;; Entity loading
;; ---------------------------------------------------------------------------

(defn- read-edn-file [path]
  (-> (slurp (io/file path)) edn/read-string))

(defn- resolve-entity-cfg
  "If cfg has :file, load and merge that EDN file. Otherwise return cfg as-is."
  [cfg base-dir]
  (if-let [f (:file cfg)]
    (let [path (if (.isAbsolute (io/file f))
                 f
                 (str base-dir "/" f))]
      (merge (read-edn-file path) (dissoc cfg :file)))
    cfg))

(defn- load-entity [ent-cfg renderer base-dir]
  (let [resolved  (resolve-entity-cfg ent-cfg base-dir)
        id        (or (:id resolved) (keyword (gensym "entity-")))
        comp-cfgs (dissoc resolved :id)
        components (reduce-kv
                    (fn [m k v]
                      (assoc m k (instantiate-component k v renderer)))
                    {}
                    comp-cfgs)]
    (entity/make id components)))

(defn- post-init-entity! [ent renderer]
  (doseq [[k v] (:components ent)]
    (try
      (post-init-component! k v renderer)
      (catch Exception e
        (log/warn "post-init-entity! failed for entity" (:id ent) "component" k ":" (.getMessage e))))))

;; ---------------------------------------------------------------------------
;; Script resolution
;; ---------------------------------------------------------------------------

(defn- resolve-script-fn [ns-sym fn-name]
  (try
    (require ns-sym)
    (requiring-resolve (symbol (str ns-sym) (str fn-name)))
    (catch Exception _ nil)))

(defn- load-script [ns-sym]
  (if ns-sym
    {:on-init (resolve-script-fn ns-sym "on-init")
     :on-tick (resolve-script-fn ns-sym "on-tick")
     :on-done (resolve-script-fn ns-sym "on-done")}
    {:on-init nil :on-tick nil :on-done nil}))

;; ---------------------------------------------------------------------------
;; Call script fn with only as many args as it accepts
;; ---------------------------------------------------------------------------

(defn- call-script-fn
  "Call f with args, truncated to the fn's max fixed arity.
   Allows scripts to define fewer args than the engine passes."
  [f & args]
  (when f
    (let [args-vec  (vec args)
          max-arity (->> (.getDeclaredMethods (class f))
                         (filter #(= "invoke" (.getName %)))
                         (map #(alength (.getParameterTypes %)))
                         (apply max 0))]
      (apply f (take max-arity args-vec)))))

;; ---------------------------------------------------------------------------
;; Scene loading
;; ---------------------------------------------------------------------------

(defn load-scene-data
  "Load a scene EDN file. Returns a data map for use with init-scene!."
  [scene-id scene-path _renderer]
  (let [cfg      (read-edn-file scene-path)
        base-dir (.getParent (io/file scene-path))
        script   (load-script (:script cfg))
        ent-cfgs (vec (:entities cfg []))]
    (log/info "load-scene-data:" scene-path "entities=" (count ent-cfgs))
    {:scene-id   scene-id
     :scene-path scene-path
     :script     script
     :ent-cfgs   ent-cfgs
     :cfg        cfg
     :base-dir   (or base-dir ".")}))

(defn init-scene!
  "Instantiate entities for a loaded scene data map. Requires a live renderer.
   Returns a Scene record with entities ready to render."
  [scene-data renderer]
  (let [{:keys [scene-id scene-path script ent-cfgs base-dir]} scene-data
        entities (mapv #(load-entity % renderer base-dir) ent-cfgs)]
    (doseq [ent entities]
      (post-init-entity! ent renderer))
    (scene/make scene-id scene-path entities (:script scene-data) (:cfg scene-data {}))))

;; ---------------------------------------------------------------------------
;; Scene registry
;; ---------------------------------------------------------------------------

(defn build-registry
  "Build a name→path map from a game.edn :scenes vector."
  [scenes-cfg]
  (reduce (fn [m {:keys [name file]}] (assoc m name file)) {} scenes-cfg))

(defn resolve-scene-target
  "Resolve a swap! target (keyword or path string) against the registry.
   Returns a [scene-id path] pair."
  [target registry]
  (cond
    (keyword? target) (let [path (get registry target)]
                        (when-not path
                          (throw (ex-info (str "Unknown scene: " target
                                               ". Registered: " (keys registry))
                                          {:target target})))
                        [target path])
    (string? target)  [(keyword target) target]
    :else (throw (ex-info "swap! target must be a keyword or path string"
                          {:target target}))))

;; ---------------------------------------------------------------------------
;; Lifecycle reify
;; ---------------------------------------------------------------------------

(defn make-lifecycle
  "Build a GameLifecycle reify for a game with scene swapping support."
  [g initial-scene-data shared-state registry]
  (let [current-scene (atom nil)]
    (reify game/GameLifecycle
      (on-init! [_this]
        (log/info "edn on-init!")
        (let [r  (:renderer g)
              sc (init-scene! initial-scene-data r)]
          (reset! current-scene sc)
          (doseq [ent (scene/get-entities sc)]
            (game/add-entity! g ent))
          (call-script-fn (get-in initial-scene-data [:script :on-init])
                          g sc shared-state)))

      (on-tick! [_this delta]
        (when-let [target (scene/pending)]
          (scene/clear-pending!)
          (let [r         (:renderer g)
                old-scene @current-scene
                [new-id new-path] (resolve-scene-target target registry)]
            (call-script-fn (get-in old-scene [:script :on-done])
                            g old-scene shared-state)
            (entity/cleanup-all! (scene/get-entities old-scene) (renderer/get-device r))
            (let [new-data (load-scene-data new-id new-path r)
                  new-sc   (init-scene! new-data r)]
              (reset! current-scene new-sc)
              (swap! (:state g) assoc :entities (vec (scene/get-entities new-sc)))
              (call-script-fn (get-in new-data [:script :on-init])
                              g new-sc shared-state))))
        (let [sc @current-scene]
          (call-script-fn (get-in sc [:script :on-tick])
                          g sc delta shared-state)))

      (on-done! [_this]
        (let [sc @current-scene]
          (call-script-fn (get-in sc [:script :on-done])
                          g sc shared-state))))))

;; ---------------------------------------------------------------------------
;; load-game — game.edn entry point
;; ---------------------------------------------------------------------------

(defn load-game
  "Load from a game.edn file. Returns [game lifecycle]."
  [game-path]
  (let [cfg           (read-edn-file game-path)
        base-dir      (or (.getParent (io/file game-path)) ".")
        settings-path (when-let [s (:settings cfg)]
                        (str base-dir "/" s))
        _             (when settings-path (settings/load! settings-path))
        merged        (settings/merge-scene cfg)
        reg           (build-registry (:scenes cfg []))
        entry-name    (or (:entry cfg)
                          (ffirst reg)
                          (throw (ex-info "game.edn must have :entry or :scenes" {:cfg cfg})))
        entry-path    (or (get reg entry-name)
                          (throw (ex-info (str "Entry scene not in registry: " entry-name)
                                          {:entry entry-name :registry reg})))
        shared-state  (atom (or (:state cfg) {}))
        g             (game/make-game (:title merged "Spock")
                                      (:width  merged 1280)
                                      (:height merged 720))
        scene-data    (load-scene-data entry-name entry-path nil)]
    (log/info "load-game:" game-path "entry=" entry-name "scenes=" (count reg))
    [g (make-lifecycle g scene-data shared-state reg)]))

;; ---------------------------------------------------------------------------
;; load-bare-scene — scene.edn entry point (backward compat)
;; ---------------------------------------------------------------------------

(defn load-bare-scene
  "Load from a bare scene.edn file. No scene registry, empty shared state.
   Returns [game lifecycle]."
  [scene-path]
  (let [cfg    (read-edn-file scene-path)
        merged (settings/merge-scene cfg)
        g      (game/make-game (:title merged "Spock")
                               (:width  merged 1280)
                               (:height merged 720))
        shared (atom {})
        data   (load-scene-data (keyword scene-path) scene-path nil)]
    (log/info "load-bare-scene:" scene-path)
    [g (make-lifecycle g data shared {})]))
