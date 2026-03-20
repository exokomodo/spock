(ns spin-shooter.scripts.game
  "Spin Shooter — main game scene script.

   Coordinate system: Vulkan NDC, x ∈ [-1,1], y ∈ [-1,1].
   Origin (0,0) = center of screen.

   Screen edge for spawn / despawn: abs(x) > 1.2 or abs(y) > 1.2."
  (:require [spock.scene  :as scene]
            [spock.entity :as entity]
            [spock.renderable.polygon :as polygon]
            [spock.input.core :as input]
            [spock.renderer.core :as renderer]
            [spock.log    :as log]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:private PLANET-RADIUS   0.08)   ; NDC units — matches :polygon :radius
(def ^:private GUN-ORBIT-DIST  0.11)   ; center of gun from planet center
(def ^:private BULLET-SPEED    1.2)    ; NDC units / second
(def ^:private PLANET-ROT-SPEED 0.4)  ; radians / second
(def ^:private GUN-ROT-SPEED   0.8)   ; same as planet, guns track with it
(def ^:private ENEMY-BASE-SPEED 0.15)  ; starting enemy speed
(def ^:private ENEMY-SPEED-RAMP 0.02)  ; extra speed per second elapsed
(def ^:private SPAWN-INTERVAL-BASE 2.5) ; seconds between spawns initially
(def ^:private SPAWN-INTERVAL-MIN  0.4) ; minimum spawn interval
(def ^:private SPAWN-ACCEL       0.05)  ; interval shrinks by this per spawn
(def ^:private COLLISION-DIST   0.07)  ; bullet-enemy collision distance
(def ^:private SCREEN-EDGE      1.25)  ; out-of-bounds threshold

;; ---------------------------------------------------------------------------
;; Local game state
;; ---------------------------------------------------------------------------

(def ^:private state
  (atom {:planet      {:angle 0.0}
         :guns        [{:angle 0.0}
                       {:angle (/ (* 2.0 Math/PI) 3.0)}
                       {:angle (* 2.0 (/ (* 2.0 Math/PI) 3.0))}]
         :bullets     []
         :enemies     []
         :score       0
         :spawn-timer 0.0
         :spawn-interval SPAWN-INTERVAL-BASE
         :elapsed     0.0}))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- gun-angles
  "Evenly space n guns around 2π."
  [n base-angle]
  (let [step (/ (* 2.0 Math/PI) n)]
    (mapv (fn [i] {:angle (+ base-angle (* i step))}) (range n))))

(defn- gun-pos
  "Return [x y] of gun at angle a on orbit-dist from center."
  [a]
  [(* GUN-ORBIT-DIST (Math/cos a))
   (* GUN-ORBIT-DIST (- (Math/sin a)))])  ; flip Y for Vulkan NDC

(defn- random-edge-pos
  "Spawn position on a random screen edge."
  []
  (let [edge (rand-int 4)]
    (case edge
      0 [(- SCREEN-EDGE) (- (rand 2.4) 1.2)]  ; left
      1 [SCREEN-EDGE     (- (rand 2.4) 1.2)]  ; right
      2 [(- (rand 2.4) 1.2) (- SCREEN-EDGE)]  ; top
      3 [(- (rand 2.4) 1.2) SCREEN-EDGE])))   ; bottom

(defn- dist2
  "Squared distance between [x1 y1] and [x2 y2]."
  [x1 y1 x2 y2]
  (let [dx (- x1 x2) dy (- y1 y2)]
    (+ (* dx dx) (* dy dy))))

(defn- out-of-bounds?
  "True if (x,y) is outside the screen boundary."
  [x y]
  (or (> (Math/abs (double x)) SCREEN-EDGE)
      (> (Math/abs (double y)) SCREEN-EDGE)))

;; ---------------------------------------------------------------------------
;; on-init
;; ---------------------------------------------------------------------------

(defn on-init [game sc shared-state]
  (log/log "spin-shooter game on-init")
  ;; Reset local state with fresh gun layout
  (reset! state {:planet      {:angle 0.0}
                 :guns        (gun-angles 3 0.0)
                 :bullets     []
                 :enemies     []
                 :score       0
                 :spawn-timer 0.0
                 :spawn-interval SPAWN-INTERVAL-BASE
                 :elapsed     0.0})
  ;; Sync score from shared-state (0 on first load)
  (swap! state assoc :score (or (:score @shared-state) 0)))

;; ---------------------------------------------------------------------------
;; on-tick
;; ---------------------------------------------------------------------------

(defn on-tick [game sc delta shared-state]
  (let [dt (double delta)]

    ;; --- Input: gun count ---
    (when (or (input/key-pressed? :equal)
              (input/key-pressed? :plus))
      (swap! state
             (fn [s]
               (let [n    (count (:guns s))
                     base (get-in s [:planet :angle])]
                 (assoc s :guns (gun-angles (inc n) base))))))

    (when (input/key-pressed? :minus)
      (swap! state
             (fn [s]
               (let [n    (max 1 (dec (count (:guns s))))
                     base (get-in s [:planet :angle])]
                 (assoc s :guns (gun-angles n base))))))

    ;; --- Input: fire ---
    (when (or (input/key-pressed? :space)
              (input/mouse-pressed? 0))
      (swap! state
             (fn [s]
               (let [new-bullets
                     (mapv (fn [{:keys [angle]}]
                             (let [[gx gy] (gun-pos angle)]
                               {:x gx :y gy
                                :vx (* BULLET-SPEED (Math/cos angle))
                                :vy (* BULLET-SPEED (- (Math/sin angle)))
                                :age 0.0}))
                           (:guns s))]
                 (update s :bullets into new-bullets)))))

    ;; --- Simulation tick ---
    (swap! state
           (fn [s]
             (let [elapsed'     (+ (:elapsed s) dt)
                   planet-angle (+ (get-in s [:planet :angle])
                                   (* PLANET-ROT-SPEED dt))
                   n-guns       (count (:guns s))
                   guns'        (gun-angles n-guns planet-angle)

                   ;; Move bullets
                   bullets'     (->> (:bullets s)
                                     (map (fn [b]
                                            (-> b
                                                (update :x + (* (:vx b) dt))
                                                (update :y + (* (:vy b) dt))
                                                (update :age + dt))))
                                     (remove (fn [b]
                                               (out-of-bounds? (:x b) (:y b))))
                                     vec)

                   ;; Spawn enemies
                   spawn-timer' (+ (:spawn-timer s) dt)
                   spawn-interval (:spawn-interval s)
                   [enemies-spawned spawn-timer'' spawn-interval']
                   (loop [st spawn-timer' si spawn-interval es (:enemies s)]
                     (if (>= st si)
                       (let [[ex ey] (random-edge-pos)
                             speed   (+ ENEMY-BASE-SPEED
                                        (* ENEMY-SPEED-RAMP elapsed'))
                             ;; direction toward center
                             dx (- ex) dy (- ey)
                             mag (Math/sqrt (+ (* dx dx) (* dy dy)))
                             nx (if (> mag 0.001) (/ (- ex) mag) 0.0)
                             ny (if (> mag 0.001) (/ (- ey) mag) 0.0)
                             new-enemy {:x ex :y ey
                                        :vx (* nx speed)
                                        :vy (* ny speed)
                                        :angle 0.0
                                        :speed speed}
                             new-si (max SPAWN-INTERVAL-MIN
                                         (- si SPAWN-ACCEL))]
                         (recur (- st si) new-si (conj es new-enemy)))
                       [es st si]))

                   ;; Move enemies
                   enemies-moved (->> enemies-spawned
                                      (map (fn [e]
                                             (-> e
                                                 (update :x + (* (:vx e) dt))
                                                 (update :y + (* (:vy e) dt))
                                                 (update :angle + (* 1.5 dt)))))
                                      (remove (fn [e]
                                                (out-of-bounds? (:x e) (:y e))))
                                      vec)

                   ;; Collision detection
                   coll-dist2 (* COLLISION-DIST COLLISION-DIST)
                   ;; Mark colliding pairs
                   hit-bullets  (atom #{})
                   hit-enemies  (atom #{})
                   score-gain   (atom 0)
                   _            (doseq [[bi b] (map-indexed vector bullets')]
                                  (doseq [[ei e] (map-indexed vector enemies-moved)]
                                    (when (< (dist2 (:x b) (:y b) (:x e) (:y e))
                                             coll-dist2)
                                      (swap! hit-bullets conj bi)
                                      (swap! hit-enemies conj ei)
                                      (swap! score-gain inc))))

                   bullets-final (vec (keep-indexed
                                        (fn [i b] (when-not (contains? @hit-bullets i) b))
                                        bullets'))
                   enemies-final (vec (keep-indexed
                                        (fn [i e] (when-not (contains? @hit-enemies i) e))
                                        enemies-moved))

                   ;; Check if any enemy reached planet center
                   planet-hit?  (some (fn [e]
                                        (< (dist2 (:x e) (:y e) 0.0 0.0)
                                           (* PLANET-RADIUS PLANET-RADIUS)))
                                      enemies-final)

                   new-score (+ (:score s) @score-gain)]

               ;; Planet hit → request scene swap (after state is updated)
               (when planet-hit?
                 (scene/swap! :gameover))

               (-> s
                   (assoc-in [:planet :angle] planet-angle)
                   (assoc :guns         guns'
                          :bullets      bullets-final
                          :enemies      enemies-final
                          :score        new-score
                          :spawn-timer  spawn-timer''
                          :spawn-interval spawn-interval'
                          :elapsed      elapsed')))))

    ;; --- Sync score to shared state ---
    (swap! shared-state assoc :score (:score @state))

    ;; --- Update renderables ---
    (let [s @state
          entities (scene/get-entities sc)
          find-r   (fn [id]
                     (some-> (filter #(= (:id %) id) entities)
                             first
                             (entity/get-component :renderable)))]

      ;; Planet
      (when-let [r (find-r :planet-r)]
        (when-let [inst (polygon/instances r)]
          (reset! inst [{:x 0.0 :y 0.0
                         :rotation (get-in s [:planet :angle])
                         :color [0.3 0.7 1.0 1.0]}])))

      ;; Guns
      (when-let [r (find-r :gun-r)]
        (when-let [inst (polygon/instances r)]
          (reset! inst
                  (mapv (fn [{:keys [angle]}]
                          (let [[gx gy] (gun-pos angle)]
                            {:x gx :y gy
                             :rotation angle
                             :color [1.0 0.8 0.2 1.0]}))
                        (:guns s)))))

      ;; Bullets
      (when-let [r (find-r :bullet-r)]
        (when-let [inst (polygon/instances r)]
          (reset! inst
                  (mapv (fn [b]
                          {:x (:x b) :y (:y b)
                           :rotation (:age b)
                           :color [1.0 1.0 0.4 1.0]})
                        (:bullets s)))))

      ;; Enemies
      (when-let [r (find-r :enemy-r)]
        (when-let [inst (polygon/instances r)]
          (reset! inst
                  (mapv (fn [e]
                          {:x (:x e) :y (:y e)
                           :rotation (:angle e)
                           :color [1.0 0.3 0.3 1.0]})
                        (:enemies s))))))))

;; ---------------------------------------------------------------------------
;; on-done
;; ---------------------------------------------------------------------------

(defn on-done [game sc shared-state]
  (log/log "spin-shooter game on-done score=" (:score @state)))
