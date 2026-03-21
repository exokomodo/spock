(ns spock.font
  "AngelCode .fnt bitmap font loader.

   Usage:
     (def font (font/load! \"assets/fonts/mono.fnt\"))
     ;; font is a map:
     ;;   {:atlas-path  \"assets/fonts/mono.png\"
     ;;    :line-height int
     ;;    :base        int
     ;;    :atlas-w     int
     ;;    :atlas-h     int
     ;;    :glyphs      {char-id {:x :y :width :height
     ;;                           :xoffset :yoffset :xadvance}}}

   The glyph coords are in pixels; callers divide by atlas-w/h for UV."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; .fnt parser (text format)
;; ---------------------------------------------------------------------------

(defn- parse-kv
  "Parse 'key=value' pairs from a .fnt line into a map of keyword → string."
  [line]
  (reduce
   (fn [m token]
     (let [eq (.indexOf ^String token "=")]
       (if (pos? eq)
         (assoc m
                (keyword (subs token 0 eq))
                (subs token (inc eq)))
         m)))
   {}
   (str/split (str/trim line) #"\s+")))

(defn- parse-int [s] (when s (Integer/parseInt (str/replace s "\"" ""))))

(defn load!
  "Load an AngelCode .fnt file. Returns a font map."
  [path]
  (let [lines  (str/split-lines (slurp (io/file path)))
        dir    (let [f (io/file path)] (or (.getParent f) "."))
        common (parse-kv (first (filter #(str/starts-with? % "common") lines)))
        page   (parse-kv (first (filter #(str/starts-with? % "page") lines)))
        glyphs (into {}
                     (for [line lines
                           :when (str/starts-with? line "char id=")]
                       (let [kv (parse-kv line)
                             id (parse-int (str (:id kv)))]
                         [id {:x        (parse-int (str (:x kv)))
                              :y        (parse-int (str (:y kv)))
                              :width    (parse-int (str (:width kv)))
                              :height   (parse-int (str (:height kv)))
                              :xoffset  (parse-int (str (:xoffset kv)))
                              :yoffset  (parse-int (str (:yoffset kv)))
                              :xadvance (parse-int (str (:xadvance kv)))}])))]
    {:atlas-path  (str dir "/" (str/replace (str (:file page)) "\"" ""))
     :line-height (parse-int (str (:lineHeight common)))
     :base        (parse-int (str (:base common)))
     :atlas-w     (parse-int (str (:scaleW common)))
     :atlas-h     (parse-int (str (:scaleH common)))
     :glyphs      glyphs}))

;; ---------------------------------------------------------------------------
;; Glyph quad builder
;; ---------------------------------------------------------------------------

(defn text->quads
  "Convert a string to a sequence of quad maps for rendering.
   Each quad: {:u0 :v0 :u1 :v1 :bx :by :bw :bh}
     u/v  — normalised atlas UVs (0.0–1.0)
     bx/by — pen offset in 'font units' (pixels)
     bw/bh — glyph size in font units
   Caller scales font units to NDC via (/ pixel-size line-height) * ndc-size."
  [font text]
  (let [{:keys [glyphs atlas-w atlas-h]} font
        aw  (double atlas-w)
        ah  (double atlas-h)]
    (loop [chars  (seq text)
           pen-x  0
           result []]
      (if-not chars
        result
        (let [id   (int (first chars))
              g    (get glyphs id (get glyphs (int \?) {}))
              x    (int (or (:x g) 0))
              y    (int (or (:y g) 0))
              w    (int (or (:width g) 0))
              h    (int (or (:height g) 0))
              xoff (int (or (:xoffset g) 0))
              yoff (int (or (:yoffset g) 0))
              xadv (int (or (:xadvance g) w))
              quad {:u0 (/ x aw) :v0 (/ y ah)
                    :u1 (/ (+ x w) aw) :v1 (/ (+ y h) ah)
                    :bx (+ pen-x xoff) :by yoff
                    :bw w :bh h}]
          (recur (next chars) (+ pen-x xadv) (conj result quad)))))))
