(ns spock.camera
  "Perspective camera with look-at view matrix and Vulkan-compatible projection.")

;; ---------------------------------------------------------------------------
;; Record
;; ---------------------------------------------------------------------------

(defrecord Camera [position target up fov-deg near far])

;; ---------------------------------------------------------------------------
;; Constructor
;; ---------------------------------------------------------------------------

(defn make-camera
  "Create a perspective camera.
   position, target, up — [x y z] vectors
   fov-deg — field of view in degrees
   near, far — clip planes"
  [position target up fov-deg near far]
  (->Camera position target up fov-deg near far))

;; ---------------------------------------------------------------------------
;; Internal math helpers
;; ---------------------------------------------------------------------------

(defn- v3-sub [[ax ay az] [bx by bz]]
  [(- ax bx) (- ay by) (- az bz)])

(defn- v3-add [[ax ay az] [bx by bz]]
  [(+ ax bx) (+ ay by) (+ az bz)])

(defn- v3-scale [[x y z] s]
  [(* x s) (* y s) (* z s)])

(defn- v3-dot [[ax ay az] [bx by bz]]
  (+ (* ax bx) (* ay by) (* az bz)))

(defn- v3-cross [[ax ay az] [bx by bz]]
  [(- (* ay bz) (* az by))
   (- (* az bx) (* ax bz))
   (- (* ax by) (* ay bx))])

(defn- v3-normalize [v]
  (let [len (Math/sqrt (v3-dot v v))]
    (if (< len 1e-10)
      [0.0 0.0 0.0]
      (v3-scale v (/ 1.0 len)))))

(defn- mat4-multiply
  "Multiply two 4x4 column-major matrices (both float-arrays of length 16).
   Returns a new float-array."
  [^floats a ^floats b]
  (let [result (float-array 16)]
    ;; result[col*4 + row] = sum_k a[k*4+row] * b[col*4+k]
    (dotimes [col 4]
      (dotimes [row 4]
        (aset result (+ (* col 4) row)
              (float (reduce + (map (fn [k] (* (aget a (+ (* k 4) row))
                                               (aget b (+ (* col 4) k))))
                                    (range 4)))))))
    result))

;; ---------------------------------------------------------------------------
;; View matrix (look-at), column-major
;; ---------------------------------------------------------------------------

(defn view-matrix
  "Compute the 4x4 view matrix (look-at) as a flat float array (column-major).
   Standard right-handed look-at formula."
  [{:keys [position target up]}]
  ;; f = normalize(target - position)  (forward)
  ;; r = normalize(f x up)             (right)
  ;; u = r x f                         (true up)
  ;; View matrix (column-major, right-handed):
  ;;   [rx  ux  -fx  0 ]   col-major layout:
  ;;   [ry  uy  -fy  0 ]
  ;;   [rz  uz  -fz  0 ]
  ;;   [-dot(r,pos)  -dot(u,pos)  dot(f,pos)  1]
  (let [f  (v3-normalize (v3-sub target position))
        r  (v3-normalize (v3-cross f up))
        u  (v3-cross r f)
        [rx ry rz] r
        [ux uy uz] u
        [fx fy fz] f
        [px py pz] position
        ;; Column-major: col0=[rx ry rz 0], col1=[ux uy uz 0], col2=[-fx -fy -fz 0], col3=[tx ty tz 1]
        tx (- (v3-dot r position))
        ty (- (v3-dot u position))
        tz (v3-dot f position)
        m  (float-array 16)]
    ;; col 0
    (aset m 0  (float rx))
    (aset m 1  (float ry))
    (aset m 2  (float rz))
    (aset m 3  (float 0.0))
    ;; col 1
    (aset m 4  (float ux))
    (aset m 5  (float uy))
    (aset m 6  (float uz))
    (aset m 7  (float 0.0))
    ;; col 2 (negated forward)
    (aset m 8  (float (- fx)))
    (aset m 9  (float (- fy)))
    (aset m 10 (float (- fz)))
    (aset m 11 (float 0.0))
    ;; col 3
    (aset m 12 (float tx))
    (aset m 13 (float ty))
    (aset m 14 (float tz))
    (aset m 15 (float 1.0))
    m))

;; ---------------------------------------------------------------------------
;; Projection matrix (perspective), column-major, Vulkan NDC
;; ---------------------------------------------------------------------------

(defn projection-matrix
  "Compute the 4x4 perspective projection matrix as a flat float array (column-major).
   aspect — width/height ratio.

   Vulkan NDC differences from OpenGL:
     - Y axis flipped: negate [1][1] (element index 5)
     - Depth range [0,1] instead of [-1,1]"
  [{:keys [fov-deg near far]} aspect]
  (let [fov-rad   (* fov-deg (/ Math/PI 180.0))
        tan-half  (Math/tan (/ fov-rad 2.0))
        ;; Standard perspective entries
        m00 (/ 1.0 (* aspect tan-half))
        m11 (/ 1.0 tan-half)          ; will be negated for Vulkan
        m22 (/ (- far) (- far near))  ; depth [0,1]: -far/(far-near)
        m23 (/ (* (- far) near) (- far near))  ; -far*near/(far-near)
        m  (float-array 16)]
    ;; col 0
    (aset m 0  (float m00))
    (aset m 1  (float 0.0))
    (aset m 2  (float 0.0))
    (aset m 3  (float 0.0))
    ;; col 1 — negate Y for Vulkan
    (aset m 4  (float 0.0))
    (aset m 5  (float (- m11)))   ; Y flip
    (aset m 6  (float 0.0))
    (aset m 7  (float 0.0))
    ;; col 2
    (aset m 8  (float 0.0))
    (aset m 9  (float 0.0))
    (aset m 10 (float m22))
    (aset m 11 (float -1.0))
    ;; col 3
    (aset m 12 (float 0.0))
    (aset m 13 (float 0.0))
    (aset m 14 (float m23))
    (aset m 15 (float 0.0))
    m))

;; ---------------------------------------------------------------------------
;; Combined VP matrix
;; ---------------------------------------------------------------------------

(defn vp-matrix
  "Compute combined view-projection matrix (column-major float array).
   Result = projection * view"
  [camera aspect]
  (let [v (view-matrix camera)
        p (projection-matrix camera aspect)]
    (mat4-multiply p v)))
