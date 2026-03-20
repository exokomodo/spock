(ns spock.renderable.registry
  "Central registry for renderable types.

   Built-in types (:triangle, :polygon) register themselves when their
   namespaces are required. Games can register custom types via
   register-renderable!.

   Usage:
     ;; in a custom renderable namespace:
     (registry/register-renderable! :my-mesh make-my-mesh)

     ;; in spock.edn (or game code):
     (registry/make-renderable {:type :my-mesh ...} renderer)")

(defonce ^:private renderable-registry (atom {}))

(defn register-renderable!
  "Register a renderable type by keyword.
   constructor-fn is (fn [cfg renderer] renderable) — called after
   Vulkan init, from on-init!, so the renderer is fully initialised."
  [kw constructor-fn]
  (swap! renderable-registry assoc kw constructor-fn)
  nil)

(defn make-renderable
  "Instantiate a renderable from a config map using the registry.
   cfg must contain :type. Throws if the type is not registered."
  [cfg renderer]
  (let [t    (:type cfg)
        ctor (get @renderable-registry t)]
    (when-not ctor
      (throw (ex-info (str "Unknown renderable type: " t
                           ". Register it with spock.renderable.registry/register-renderable!")
                      {:type t :registered (keys @renderable-registry)})))
    (ctor cfg renderer)))
