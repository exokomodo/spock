(ns spock.camera
  "Perspective camera — look-at view matrix and Vulkan-compatible projection.")

(defrecord Camera [position target up fov-deg near far])

(defn make-camera
  "Create a perspective camera.
   position, target, up — [x y z] vectors
   fov-deg — vertical field of view in degrees
   near, far — clip planes"
  [position target up fov-deg near far]
  (->Camera position target up fov-deg near far))

;; ---------------------------------------------------------------------------
;; Vector math
;; ---------------------------------------------------------------------------

(defn- v- [[ax ay az] [bx by bz]] [(- ax bx) (- ay by) (- az bz)])
(defn- v+ [[ax ay az] [bx by bz]] [(+ ax bx) (+ ay by) (+ az bz)])
(defn- v* [[x y z] s]             [(* x s)   (* y s)   (* z s)])
(defn- dot [[ax ay az] [bx by bz]] (+ (* ax bx) (* ay by) (* az bz)))
(defn- cross [[ax ay az] [bx by bz]]
  [(- (* ay bz) (* az by))
   (- (* az bx) (* ax bz))
   (- (* ax by) (* ay bx))])
(defn- normalize [v]
  (let [len (Math/sqrt (dot v v))]
    (if (< len 1e-10) [0.0 0.0 1.0] (v* v (/ 1.0 len)))))

;; ---------------------------------------------------------------------------
;; View matrix (right-handed look-at, column-major)
;; ---------------------------------------------------------------------------

(defn view-matrix
  "4x4 view matrix as a flat float array (column-major)."
  [{:keys [position target up]}]
  (let [f  (normalize (v- target position))   ; forward
        r  (normalize (cross f up))            ; right
        u  (cross r f)                         ; true up
        [rx ry rz] r
        [ux uy uz] u
        [fx fy fz] f
        tx (- (dot r position))
        ty (- (dot u position))
        tz    (dot f position)
        m  (float-array 16)]
    (aset m 0  (float rx)) (aset m 1  (float ry)) (aset m 2  (float rz)) (aset m 3  0.0)
    (aset m 4  (float ux)) (aset m 5  (float uy)) (aset m 6  (float uz)) (aset m 7  0.0)
    (aset m 8  (float (- fx))) (aset m 9  (float (- fy))) (aset m 10 (float (- fz))) (aset m 11 0.0)
    (aset m 12 (float tx)) (aset m 13 (float ty)) (aset m 14 (float tz)) (aset m 15 1.0)
    m))

;; ---------------------------------------------------------------------------
;; Projection matrix (perspective, Vulkan NDC, column-major)
;;
;; Vulkan differences vs OpenGL:
;;   - Y axis is flipped: negate [1][1]  → negating m11
;;   - Depth range [0,1]: use the RH zero-to-one formulation
;; ---------------------------------------------------------------------------

(defn projection-matrix
  "4x4 perspective projection matrix as a flat float array (column-major).
   aspect — width/height."
  [{:keys [fov-deg near far]} aspect]
  (let [half-fov  (/ (* fov-deg Math/PI) 360.0)
        tan-half  (Math/tan half-fov)
        m00 (/ 1.0 (* (double aspect) tan-half))
        m11 (/ 1.0 tan-half)                       ; negated below for Vulkan Y-flip
        m22 (/ (double far) (- (double near) (double far)))
        m23 (* m22 (double near))
        m   (float-array 16)]
    (aset m 0  (float m00))
    (aset m 5  (float (- m11)))  ; Y-flip
    (aset m 10 (float m22))
    (aset m 11 -1.0)             ; perspective divide row
    (aset m 14 (float m23))
    m))

;; ---------------------------------------------------------------------------
;; Matrix multiply (4x4, column-major)
;; ---------------------------------------------------------------------------

(defn- mat4*
  "Multiply two column-major 4x4 float-arrays. Returns a new float-array."
  [^floats a ^floats b]
  (let [out (float-array 16)]
    (dotimes [col 4]
      (dotimes [row 4]
        (aset out (+ row (* col 4))
              (float (loop [k 0 s 0.0]
                       (if (= k 4) s
                           (recur (inc k)
                                  (+ s (* (aget a (+ row (* k 4)))
                                          (aget b (+ k (* col 4))))))))))))
    out))

;; ---------------------------------------------------------------------------
;; Combined VP
;; ---------------------------------------------------------------------------

(defn vp-matrix
  "View-projection matrix (column-major float array). Result = projection * view."
  [camera aspect]
  (mat4* (projection-matrix camera aspect)
         (view-matrix camera)))
