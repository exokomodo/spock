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
            [spock.log             :as log])
  (:import [org.lwjgl.vulkan
            VK10
            VkBufferCreateInfo
            VkMemoryRequirements
            VkMemoryAllocateInfo
            VkPhysicalDeviceMemoryProperties]
           [org.lwjgl.system MemoryStack MemoryUtil]
           [java.nio ByteBuffer ByteOrder]))

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
                    (log/trace "edn :triangle draw: no pipeline yet"))))
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
;; Built-in renderable: :polygon
;; ---------------------------------------------------------------------------

;; Push constant layout for polygon shader (bytes, std430 / packed):
;;   offset 0:  vec2  translation  (8 bytes)
;;   offset 8:  float rotation     (4 bytes)
;;   offset 12: (4 bytes padding to align vec4)
;;   offset 16: vec4  color        (16 bytes)
;;   total: 32 bytes
;;
;; NOTE: GLSL std140 packs push constants the same way for these types.
;; vec2 = 8 bytes, float = 4 bytes, then vec4 must be 16-byte aligned → 4 bytes pad.
(def ^:private ^:const PUSH_CONSTANT_BYTES 32)

(defn- compute-polygon-vertices
  "Return a flat float array of (sides * 2) floats for a regular N-gon,
   center-origin, CCW winding, in NDC-friendly coords scaled by radius."
  [sides radius]
  (let [n (int sides)
        step (/ (* 2.0 Math/PI) n)]
    (float-array
      (for [i (range n)
            coord [:x :y]]
        (let [angle (* i step)]
          (case coord
            :x (* (double radius) (Math/cos angle))
            :y (* (double radius) (- (Math/sin angle)))))))))  ; flip Y for Vulkan

;; Physical device is needed to find memory types. Accept it explicitly.
(defn- find-memory-type
  "Find a memory type index that satisfies type-filter and required-props flags."
  [^org.lwjgl.vulkan.VkPhysicalDevice physical-device type-filter required-props]
  (let [stack (MemoryStack/stackPush)]
    (try
      (let [props (VkPhysicalDeviceMemoryProperties/malloc stack)]
        (VK10/vkGetPhysicalDeviceMemoryProperties physical-device props)
        (loop [i 0]
          (if (>= i (.memoryTypeCount props))
            (throw (RuntimeException. "Failed to find suitable memory type"))
            (let [mem-type (.get (.memoryTypes props) i)
                  flags    (.propertyFlags mem-type)]
              (if (and (not= 0 (bit-and (int type-filter) (bit-shift-left 1 i)))
                       (= (int required-props) (bit-and (int flags) (int required-props))))
                i
                (recur (inc i)))))))
      (finally
        (MemoryStack/stackPop)))))

(defn- create-vertex-buffer!
  "Create and populate a host-visible vertex buffer.
   Returns {:buffer long :memory long :device device}."
  [^org.lwjgl.vulkan.VkDevice device
   ^org.lwjgl.vulkan.VkPhysicalDevice physical-device
   float-data]
  (let [stack  (MemoryStack/stackPush)
        floats (count float-data)
        size   (long (* floats 4))
        bci    (VkBufferCreateInfo/calloc stack)]
    (try
      (.sType bci VK10/VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
      (.size  bci size)
      (.usage bci VK10/VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
      (.sharingMode bci VK10/VK_SHARING_MODE_EXCLUSIVE)
      (let [lp (.mallocLong stack 1)
            r  (VK10/vkCreateBuffer device bci nil lp)]
        (when (not= r VK10/VK_SUCCESS)
          (throw (RuntimeException. (str "vkCreateBuffer failed: " r))))
        (let [buf          (.get lp 0)
              mr           (VkMemoryRequirements/malloc stack)
              _            (VK10/vkGetBufferMemoryRequirements device buf mr)
              host-visible (bit-or VK10/VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                                   VK10/VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
              mem-type-idx (find-memory-type physical-device (.memoryTypeBits mr) host-visible)
              mai          (VkMemoryAllocateInfo/calloc stack)]
          (.sType           mai VK10/VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
          (.allocationSize  mai (.size mr))
          (.memoryTypeIndex mai (int mem-type-idx))
          (.rewind lp)
          (let [r2 (VK10/vkAllocateMemory device mai nil lp)]
            (when (not= r2 VK10/VK_SUCCESS)
              (VK10/vkDestroyBuffer device buf nil)
              (throw (RuntimeException. (str "vkAllocateMemory failed: " r2))))
            (let [mem    (.get lp 0)
                  _      (VK10/vkBindBufferMemory device buf mem 0)
                  pp     (.mallocPointer stack 1)
                  _      (VK10/vkMapMemory device mem 0 size 0 pp)
                  mapped (org.lwjgl.system.MemoryUtil/memByteBuffer (.get pp 0) (int size))]
              (.order mapped ByteOrder/LITTLE_ENDIAN)
              (let [fb (.asFloatBuffer mapped)]
                (doseq [f float-data] (.put fb (float f))))
              (VK10/vkUnmapMemory device mem)
              {:buffer buf
               :memory mem
               :device device}))))
      (finally
        (MemoryStack/stackPop)))))

(defn- make-polygon-renderable
  "Build a :polygon renderable.
   cfg: {:sides N :color [r g b a] :radius R}

   The renderable maintains an instance pool — a vector of instance maps:
     {:x float :y float :rotation float :color [r g b a]}
   Scripts update the pool each tick via the :instances atom in metadata.
   On draw, all instances are rendered in sequence with push-constants!.
   If :instances is empty, falls back to drawing one default instance at origin."
  [cfg _renderer]
  (let [sides    (int (or (:sides cfg) 6))
        radius   (double (or (:radius cfg) 0.1))
        def-color (vec (or (:color cfg) [1.0 1.0 1.0 1.0]))
        pipeline-atom (atom nil)
        vbuf-atom     (atom nil)   ; {:buffer :memory :device}
        instances     (atom [])    ; vector of {:x :y :rotation :color}
        ;; Reusable ByteBuffer for push constants (allocated once, reused per draw call)
        pc-buf (doto (ByteBuffer/allocateDirect PUSH_CONSTANT_BYTES)
                 (.order ByteOrder/LITTLE_ENDIAN))
        r (reify renderable/Renderable
            (draw [_this command-buffer _device _render-pass _extent]
              (let [pl @pipeline-atom
                    vb @vbuf-atom]
                (when (and pl vb)
                  (let [{:keys [pipeline layout]} pl]
                    (org.lwjgl.vulkan.VK10/vkCmdBindPipeline
                      ^org.lwjgl.vulkan.VkCommandBuffer command-buffer
                      VK10/VK_PIPELINE_BIND_POINT_GRAPHICS
                      (long pipeline))
                    ;; Bind vertex buffer at binding 0
                    (let [stack (org.lwjgl.system.MemoryStack/stackPush)
                          vbp   (doto (.mallocLong stack 1) (.put (:buffer vb)) (.flip))
                          offp  (doto (.mallocLong stack 1) (.put 0) (.flip))]
                      (VK10/vkCmdBindVertexBuffers command-buffer 0 vbp offp)
                      (org.lwjgl.system.MemoryStack/stackPop))
                    ;; Draw each instance with per-instance push constants
                    (let [insts @instances
                          draw-list (if (seq insts) insts [{:x 0.0 :y 0.0 :rotation 0.0 :color def-color}])]
                      (doseq [{:keys [x y rotation color]} draw-list]
                        (let [c (vec (or color def-color))]
                          (.clear pc-buf)
                          (.putFloat pc-buf (float (or x 0.0)))        ; translation.x
                          (.putFloat pc-buf (float (or y 0.0)))        ; translation.y
                          (.putFloat pc-buf (float (or rotation 0.0))) ; rotation
                          (.putFloat pc-buf 0.0)                       ; padding
                          (.putFloat pc-buf (float (nth c 0 1.0)))     ; color.r
                          (.putFloat pc-buf (float (nth c 1 1.0)))     ; color.g
                          (.putFloat pc-buf (float (nth c 2 1.0)))     ; color.b
                          (.putFloat pc-buf (float (nth c 3 1.0)))     ; color.a
                          (.flip pc-buf)
                          (pipeline/push-constants!
                            command-buffer
                            (long layout)
                            VK10/VK_SHADER_STAGE_VERTEX_BIT
                            pc-buf))
                        (VK10/vkCmdDraw
                          ^org.lwjgl.vulkan.VkCommandBuffer command-buffer
                          (int sides) 1 0 0)))))))
            (cleanup! [_this device]
              (when-let [pl @pipeline-atom]
                (pipeline/destroy! pl)
                (reset! pipeline-atom nil))
              (when-let [vb @vbuf-atom]
                (VK10/vkDestroyBuffer device (long (:buffer vb)) nil)
                (VK10/vkFreeMemory    device (long (:memory vb)) nil)
                (reset! vbuf-atom nil))))]
    (with-meta r {:pipeline-atom pipeline-atom
                  :vbuf-atom     vbuf-atom
                  :instances     instances
                  :sides         sides
                  :radius        radius})))

;; VK_FORMAT_R32G32_SFLOAT = 103 (two 32-bit floats, for vec2 position)
(def ^:private ^:const VK_FORMAT_R32G32_SFLOAT 103)

(defn- build-polygon-pipeline!
  "Initialize GPU resources for a polygon renderable."
  [renderable renderer]
  (let [{:keys [pipeline-atom vbuf-atom sides radius]} (meta renderable)
        ^org.lwjgl.vulkan.VkDevice dev (renderer/get-device renderer)
        pd  (.getPhysicalDevice dev)
        rp  (renderer/get-render-pass renderer)
        ;; Build vertex data
        floats (compute-polygon-vertices sides radius)
        vb     (create-vertex-buffer! dev pd floats)
        ;; Build pipeline with vertex input and push constants
        pl     (-> (pipeline/builder dev rp)
                   (pipeline/vert-path "src/shaders/polygon.vert")
                   (pipeline/frag-path "src/shaders/polygon.frag")
                   (pipeline/topology  :triangle-fan)
                   (pipeline/cull-mode :none)
                   (pipeline/vertex-input 8 [{:location 0
                                              :format   VK_FORMAT_R32G32_SFLOAT
                                              :offset   0}])
                   (pipeline/push-constant-size PUSH_CONSTANT_BYTES)
                   (pipeline/build!))]
    (reset! vbuf-atom vb)
    (reset! pipeline-atom pl)))

(register-renderable! :polygon make-polygon-renderable)

(defn polygon-instances
  "Return the instances atom for a :polygon renderable so scripts can update it.
   instances atom holds a vector of {:x :y :rotation :color} maps.
   Set it via (reset! (edn/polygon-instances r) new-vec) or swap!."
  [polygon-renderable]
  (:instances (meta polygon-renderable)))

;; ---------------------------------------------------------------------------
;; Component instantiation
;; ---------------------------------------------------------------------------

(defn- instantiate-component [k v renderer]
  (case k
    :renderable (make-renderable v renderer)
    v))

(defn- post-init-component! [k v renderer]
  (when (= k :renderable)
    (let [m (meta v)]
      (cond
        (contains? m :vert-path)   (build-triangle-pipeline! v renderer)
        (contains? m :vbuf-atom)   (build-polygon-pipeline!  v renderer)))))

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
    (log/info "load-scene-data:" scene-path "entities=" (count ent-cfgs))
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
        (log/info "edn on-init!")
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
    (log/info "load-game:" game-path "entry=" entry-name "scenes=" (count registry))
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
    (log/info "load-bare-scene:" scene-path)
    [g (make-lifecycle g data shared {})]))
