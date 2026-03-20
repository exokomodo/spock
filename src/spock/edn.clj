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
     (def [game first-scene shared-state registry] (load-game \"game.edn\"))
     ;; or for a bare scene:
     (def [game scene shared-state registry] (load-scene \"scene.edn\" {} {}))
     (game/start! game scene shared-state registry)"
  (:require [clojure.edn      :as edn]
            [clojure.java.io  :as io]
            [spock.game.core  :as game]
            [spock.scene      :as scene]
            [spock.settings   :as settings]
            [spock.entity     :as entity]
            [spock.renderable.core :as renderable]
            [spock.renderer.core   :as renderer]
            [spock.pipeline.core   :as pipeline]
            [spock.log             :as log]))

;; ---------------------------------------------------------------------------
;; Renderable registry
;; ---------------------------------------------------------------------------

(defonce ^:private renderable-registry (atom {}))

(defn register-renderable!
  "Register a renderable type by keyword.
   constructor-fn is (fn [cfg renderer] renderable)."
  [kw constructor-fn]
  (swap! renderable-registry assoc kw constructor-fn)
  nil)

(defn- make-renderable [cfg renderer]
  (let [t    (:type cfg)
        ctor (get @renderable-registry t)]
    (when-not ctor
      (throw (ex-info (str "Unknown renderable type: " t
                           ". Register it with spock.edn/register-renderable!")
                      {:type t :registered (keys @renderable-registry)})))
    (ctor cfg renderer)))

;; ---------------------------------------------------------------------------
;; Built-in renderable: :triangle
;; ---------------------------------------------------------------------------

(defn- make-triangle-renderable [cfg renderer]
  (let [shaders   (:shaders cfg)
        vert-path (:vert shaders)
        frag-path (:frag shaders)]
    (when-not (and vert-path frag-path)
      (throw (ex-info ":triangle renderable requires :shaders {:vert ... :frag ...}" {:cfg cfg})))
    (let [pipeline-atom (atom {})
          r (reify renderable/Renderable
              (draw [_this command-buffer _device _render-pass _extent]
                (let [{:keys [pipeline layout]} @pipeline-atom]
                  (if (and pipeline layout)
                    (do
                      (org.lwjgl.vulkan.VK10/vkCmdBindPipeline
                        ^org.lwjgl.vulkan.VkCommandBuffer command-buffer
                        org.lwjgl.vulkan.VK10/VK_PIPELINE_BIND_POINT_GRAPHICS
                        (long pipeline))
                      (org.lwjgl.vulkan.VK10/vkCmdDraw
                        ^org.lwjgl.vulkan.VkCommandBuffer command-buffer
                        3 1 0 0))
                    (log/log "edn :triangle draw: no pipeline yet"))))
              (cleanup! [_this _device]
                (pipeline/destroy! @pipeline-atom)
                (reset! pipeline-atom {})))]
      (with-meta r {:pipeline-atom pipeline-atom
                    :vert-path     vert-path
                    :frag-path     frag-path}))))

(defn- build-triangle-pipeline! [renderable renderer]
  (let [{:keys [pipeline-atom vert-path frag-path]} (meta renderable)
        ext (renderer/get-extent      renderer)
        rp  (renderer/get-render-pass renderer)
        dev (renderer/get-device      renderer)
        pl  (-> (pipeline/builder dev rp)
                (pipeline/vert-path vert-path)
                (pipeline/frag-path frag-path)
                (pipeline/topology  :triangle-list)
                (pipeline/cull-mode :none)
                (pipeline/build!))]
    (reset! pipeline-atom pl)))

(register-renderable! :triangle make-triangle-renderable)

;; ---------------------------------------------------------------------------
;; Component instantiation
;; ---------------------------------------------------------------------------

(defn- instantiate-component [k v renderer]
  (case k
    :renderable (make-renderable v renderer)
    v))

(defn- post-init-component! [k v renderer]
  (when (and (= k :renderable)
             (contains? (meta v) :pipeline-atom))
    (build-triangle-pipeline! v renderer)))

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
  (let [resolved   (resolve-entity-cfg ent-cfg base-dir)
        id         (or (:id resolved) (keyword (gensym "entity-")))
        comp-cfgs  (dissoc resolved :id)
        components (reduce-kv
                     (fn [m k v]
                       (assoc m k (instantiate-component k v renderer)))
                     {}
                     comp-cfgs)]
    (entity/make id components)))

(defn- post-init-entity! [ent renderer]
  (doseq [[k v] (:components ent)]
    (post-init-component! k v renderer)))

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
  "Load a scene EDN file and instantiate entities. Returns a Scene record.
   renderer may be nil at parse time — pass nil and call init-scene! later."
  [scene-id scene-path renderer]
  (let [cfg      (read-edn-file scene-path)
        base-dir (.getParent (io/file scene-path))
        script   (load-script (:script cfg))
        ent-cfgs (vec (:entities cfg []))]
    (log/log "load-scene-data:" scene-path "entities=" (count ent-cfgs))
    {:scene-id  scene-id
     :scene-path scene-path
     :script    script
     :ent-cfgs  ent-cfgs
     :cfg       cfg
     :base-dir  (or base-dir ".")}))

(defn init-scene!
  "Instantiate entities for a loaded scene data map. Requires a live renderer.
   Returns a Scene record with entities ready to render."
  [scene-data renderer]
  (let [{:keys [scene-id scene-path script ent-cfgs base-dir]} scene-data
        entities (mapv #(load-entity % renderer base-dir) ent-cfgs)]
    (doseq [ent entities]
      (post-init-entity! ent renderer))
    (scene/make scene-id scene-path entities (:script scene-data))))

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
;; Lifecycle reify — used by both load-game and load-bare-scene
;; ---------------------------------------------------------------------------

(defn make-lifecycle
  "Build a GameLifecycle reify for a game with scene swapping support.

   g            — Game record
   initial-scene-data — output of load-scene-data for the first scene
   shared-state — atom, process-lifetime
   registry     — keyword→path map of registered scenes"
  [g initial-scene-data shared-state registry]
  (let [current-scene (atom nil)]
    (reify game/GameLifecycle
      (on-init! [_this]
        (log/log "edn on-init!")
        (let [r   (:renderer g)
              sc  (init-scene! initial-scene-data r)]
          (reset! current-scene sc)
          (doseq [ent (scene/get-entities sc)]
            (game/add-entity! g ent))
          (call-script-fn (get-in initial-scene-data [:script :on-init])
                          g sc shared-state)))

      (on-tick! [_this delta]
        ;; Check for pending scene swap first
        (when-let [target (scene/pending)]
          (scene/clear-pending!)
          (let [r          (:renderer g)
                old-scene  @current-scene
                [new-id new-path] (resolve-scene-target target registry)]
            ;; Teardown current scene
            (call-script-fn (get-in old-scene [:script :on-done])
                            g old-scene shared-state)
            (entity/cleanup-all! (scene/get-entities old-scene) (renderer/get-device r))
            ;; Load and init new scene
            (let [new-data (load-scene-data new-id new-path r)
                  new-sc   (init-scene! new-data r)]
              (reset! current-scene new-sc)
              ;; Replace entity list in game state
              (swap! (:state g) assoc :entities (vec (scene/get-entities new-sc)))
              (call-script-fn (get-in new-data [:script :on-init])
                              g new-sc shared-state))))
        ;; Normal tick
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
  (let [cfg          (read-edn-file game-path)
        base-dir     (or (.getParent (io/file game-path)) ".")
        settings-path (when-let [s (:settings cfg)]
                        (str base-dir "/" s))
        _            (when settings-path (settings/load! settings-path))
        merged       (settings/merge-scene cfg)
        registry     (build-registry (:scenes cfg []))
        entry-name   (or (:entry cfg)
                         (ffirst registry)
                         (throw (ex-info "game.edn must have :entry or :scenes" {:cfg cfg})))
        entry-path   (or (get registry entry-name)
                         (throw (ex-info (str "Entry scene not in registry: " entry-name)
                                         {:entry entry-name :registry registry})))
        shared-state (atom (or (:state cfg) {}))
        g            (game/make-game (:title merged "Spock")
                                     (:width  merged 1280)
                                     (:height merged 720))
        scene-data   (load-scene-data entry-name entry-path nil)]
    (log/log "load-game:" game-path "entry=" entry-name "scenes=" (count registry))
    [g (make-lifecycle g scene-data shared-state registry)]))

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
    (log/log "load-bare-scene:" scene-path)
    [g (make-lifecycle g data shared {})]))
