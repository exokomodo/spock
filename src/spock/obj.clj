(ns spock.obj
  "Wavefront OBJ loader. Returns interleaved vertex data suitable for Vulkan."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn- parse-float [s] (Float/parseFloat s))
(defn- parse-int   [s] (Integer/parseInt s))

(defn- parse-face-vertex
  "Parse a face vertex token like 'v/vt/vn', 'v//vn', or 'v'.
   Returns [v-idx vt-idx vn-idx] (0-indexed, nil when absent)."
  [token]
  (let [parts (str/split token #"/" -1)]
    [(dec (parse-int (nth parts 0)))
     (when (and (> (count parts) 1) (not= (nth parts 1) "")) (dec (parse-int (nth parts 1))))
     (when (and (> (count parts) 2) (not= (nth parts 2) "")) (dec (parse-int (nth parts 2))))]))

(defn- triangulate
  "Fan-triangulate a face given as a vector of vertex tuples.
   Returns a seq of 3-tuples."
  [face-verts]
  (when (>= (count face-verts) 3)
    (let [v0   (first face-verts)
          rest (rest face-verts)]
      (map (fn [[a b]] [v0 a b])
           (partition 2 1 rest)))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn load-obj
  "Parse a Wavefront OBJ file at path.

   Returns a mesh map:
     :vertices     float-array — interleaved [x y z nx ny nz u v] per unique vertex (8 floats, 32 bytes)
     :indices      int-array   — triangle indices (0-indexed)
     :vertex-count int
     :index-count  int"
  [path]
  (with-open [rdr (io/reader path)]
    (let [lines     (line-seq rdr)
          positions (java.util.ArrayList.)
          normals   (java.util.ArrayList.)
          uvs       (java.util.ArrayList.)
          faces     (java.util.ArrayList.)]

      (doseq [raw lines]
        (let [line (str/trim raw)]
          (cond
            (str/starts-with? line "v ")
            (let [p (str/split line #"\s+")]
              (.add positions [(parse-float (nth p 1))
                               (parse-float (nth p 2))
                               (parse-float (nth p 3))]))

            (str/starts-with? line "vn ")
            (let [p (str/split line #"\s+")]
              (.add normals [(parse-float (nth p 1))
                             (parse-float (nth p 2))
                             (parse-float (nth p 3))]))

            (str/starts-with? line "vt ")
            (let [p (str/split line #"\s+")]
              (.add uvs [(parse-float (nth p 1))
                         (parse-float (nth p 2))]))

            (str/starts-with? line "f ")
            (let [p      (str/split line #"\s+")
                  tokens (subvec (vec p) 1)]
              (.add faces (mapv parse-face-vertex tokens))))))

      ;; Build deduplicated interleaved vertex buffer
      (let [vertex-map  (java.util.LinkedHashMap.)
            vertex-list (java.util.ArrayList.)
            index-list  (java.util.ArrayList.)
            default-nrm [0.0 1.0 0.0]
            default-uv  [0.0 0.0]

            get-or-add!
            (fn [key pos-idx nrm-idx uv-idx]
              (if (.containsKey vertex-map key)
                (.get vertex-map key)
                (let [idx (int (.size vertex-list))
                      pos (if (and pos-idx (< pos-idx (.size positions)))
                            (.get positions pos-idx) [0.0 0.0 0.0])
                      nrm (if (and nrm-idx (< nrm-idx (.size normals)))
                            (.get normals nrm-idx) default-nrm)
                      uv  (if (and uv-idx (< uv-idx (.size uvs)))
                            (.get uvs uv-idx) default-uv)]
                  (.put vertex-map key idx)
                  (.add vertex-list [(nth pos 0) (nth pos 1) (nth pos 2)
                                     (nth nrm 0) (nth nrm 1) (nth nrm 2)
                                     (nth uv 0)  (nth uv 1)])
                  idx)))]

        (doseq [face faces]
          (doseq [tri (triangulate face)]
            (doseq [[v-idx vt-idx vn-idx] tri]
              (.add index-list
                    (int (get-or-add! [v-idx vt-idx vn-idx] v-idx vn-idx vt-idx))))))

        (let [vc     (.size vertex-list)
              ic     (.size index-list)
              verts  (float-array (* vc 8))
              idxs   (int-array ic)]
          (dotimes [i vc]
            (let [v (.get vertex-list i) b (* i 8)]
              (aset verts (+ b 0) (float (nth v 0)))
              (aset verts (+ b 1) (float (nth v 1)))
              (aset verts (+ b 2) (float (nth v 2)))
              (aset verts (+ b 3) (float (nth v 3)))
              (aset verts (+ b 4) (float (nth v 4)))
              (aset verts (+ b 5) (float (nth v 5)))
              (aset verts (+ b 6) (float (nth v 6)))
              (aset verts (+ b 7) (float (nth v 7)))))
          (dotimes [i ic]
            (aset idxs i (int (.get index-list i))))
          {:vertices     verts
           :indices      idxs
           :vertex-count vc
           :index-count  ic})))))
