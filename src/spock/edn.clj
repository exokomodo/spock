(ns spock.edn
  "EDN-driven game loader.

   Loads a game from a .edn file:
   - Pure data EDN — no fn literals, no eval
   - :script references a Clojure namespace symbol; the engine requires it
     and resolves on-init / on-tick / on-done as vars (all optional)
   - Renderables are instantiated via the renderable registry by :type keyword

   Usage:
     (def [game lifecycle] (load-game \"game.edn\"))
     (game/start! game lifecycle)"
  (:require [clojure.edn     :as edn]
            [clojure.java.io :as io]
            [spock.game.core  :as game]
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
   constructor-fn is (fn [cfg renderer] renderable) — called after Vulkan init,
   from on-init!, so the renderer is fully initialised."
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
    ;; Build the pipeline atom — pipeline is populated in on-init! once the
    ;; renderer is ready, so we return a placeholder atom here and fill it later.
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
      ;; Stash builder fn on the renderable's meta so on-init! can build the pipeline
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

;; Register built-in types at load time
(register-renderable! :triangle make-triangle-renderable)

;; ---------------------------------------------------------------------------
;; Script resolution
;; ---------------------------------------------------------------------------

(defn- resolve-script-fn
  "Require ns-sym and return the var for fn-name, or nil if not found."
  [ns-sym fn-name]
  (try
    (require ns-sym)
    (requiring-resolve (symbol (str ns-sym) (str fn-name)))
    (catch Exception _
      nil)))

(defn- load-script
  "Returns {:on-init fn-or-nil :on-tick fn-or-nil :on-done fn-or-nil} for ns-sym."
  [ns-sym]
  (if ns-sym
    {:on-init (resolve-script-fn ns-sym "on-init")
     :on-tick (resolve-script-fn ns-sym "on-tick")
     :on-done (resolve-script-fn ns-sym "on-done")}
    {:on-init nil :on-tick nil :on-done nil}))

;; ---------------------------------------------------------------------------
;; load-game
;; ---------------------------------------------------------------------------

(defn load-game
  "Read an EDN file and return [game lifecycle].
   Pass both to game/start!.

   EDN format:
     {:title  \"My Game\"
      :width  1280
      :height 720
      :script my.game.script     ; optional namespace symbol
      :renderables [{:type :triangle
                     :shaders {:vert \"...\" :frag \"...\"}}]}"
  [edn-path]
  (let [cfg        (-> (slurp (io/file edn-path)) edn/read-string)
        title      (or (:title cfg) "Spock")
        width      (or (:width  cfg) 1280)
        height     (or (:height cfg) 720)
        script     (load-script (:script cfg))
        g          (game/make-game title width height)
        ;; Renderable configs — we build the actual renderables in on-init!
        ;; once the renderer is ready.
        rdbl-cfgs  (vec (:renderables cfg []))
    (log/log "edn/load-game:" edn-path "title=" title "renderables=" (count rdbl-cfgs))

    [g
     (reify game/GameLifecycle
       (on-init! [_this]
         (log/log "edn on-init!")
         ;; Instantiate renderables and add them as entities
         (let [r (:renderer g)]
           (doseq [cfg rdbl-cfgs]
             (let [rdbl (make-renderable cfg r)
                   ent  (entity/make (keyword (or (:id cfg) (gensym "entity-")))
                                     {:renderable rdbl})]
               (when (= :triangle (:type cfg))
                 (build-triangle-pipeline! rdbl r))
               (game/add-entity! g ent))))
         (when-let [f (:on-init script)]
           (f g)))

       (on-tick! [_this delta]
         (when-let [f (:on-tick script)]
           (f g delta)))

       (on-done! [_this]
         (when-let [f (:on-done script)]
           (f g))))]))
