(ns spock.input.core
  "GLFW-based input polling. Game-agnostic.

   Key states cycle as follows each frame:
     :pressed  → :held     (key is down, not the first frame)
     :released → :none     (key is up, cleared after one frame)
     :held     → :held     (unchanged while still down)
     :none     → :none

   Usage:
     (register-callbacks! window-handle)   ; call after glfwCreateWindow
     ;; in game loop, after glfwPollEvents:
     (tick!)
     ;; query:
     (key-pressed?  :space)
     (key-held?     :a)
     (key-released? :escape)
     (mouse-pressed? 0)   ; 0 = left button
     (mouse-pos)          ; => [x y]"
  (:import [org.lwjgl.glfw GLFW]))

;; ---------------------------------------------------------------------------
;; State atoms
;; ---------------------------------------------------------------------------

;; Maps GLFW key-code (int) → :pressed/:held/:released/:none
(defonce ^:private key-states (atom {}))

;; Maps button-index (int) → :pressed/:held/:released/:none
(defonce ^:private mouse-button-states (atom {}))

;; Current cursor position as [x y] in screen coords.
(defonce ^:private cursor-pos (atom [0.0 0.0]))

;; ---------------------------------------------------------------------------
;; Key alias map
;; ---------------------------------------------------------------------------

(def ^:private key-aliases
  (merge
   {:space  GLFW/GLFW_KEY_SPACE
    :enter  GLFW/GLFW_KEY_ENTER
    :escape GLFW/GLFW_KEY_ESCAPE
    :tab    GLFW/GLFW_KEY_TAB
    :left   GLFW/GLFW_KEY_LEFT
    :right  GLFW/GLFW_KEY_RIGHT
    :up     GLFW/GLFW_KEY_UP
    :down   GLFW/GLFW_KEY_DOWN
    :backspace GLFW/GLFW_KEY_BACKSPACE
    :delete GLFW/GLFW_KEY_DELETE
    :equal  GLFW/GLFW_KEY_EQUAL        ; = key
    :minus  GLFW/GLFW_KEY_MINUS        ; - key
    :plus   GLFW/GLFW_KEY_EQUAL        ; alias
    :lshift GLFW/GLFW_KEY_LEFT_SHIFT
    :rshift GLFW/GLFW_KEY_RIGHT_SHIFT
    :lctrl  GLFW/GLFW_KEY_LEFT_CONTROL
    :rctrl  GLFW/GLFW_KEY_RIGHT_CONTROL
    :lalt   GLFW/GLFW_KEY_LEFT_ALT
    :ralt   GLFW/GLFW_KEY_RIGHT_ALT}
   ;; :a–:z
   (into {} (map (fn [i]
                   (let [kw (keyword (str (char (+ (int \a) i))))
                         code (+ GLFW/GLFW_KEY_A i)]
                     [kw code]))
                 (range 26)))
   ;; :0–:9
   (into {} (map (fn [i]
                   (let [kw (keyword (str i))
                         code (+ GLFW/GLFW_KEY_0 i)]
                     [kw code]))
                 (range 10)))))

(defn- resolve-key
  "Resolve a keyword alias or raw int key code to an int GLFW key code."
  [k]
  (if (keyword? k)
    (or (get key-aliases k)
        (throw (ex-info (str "Unknown key alias: " k) {:key k})))
    (int k)))

;; ---------------------------------------------------------------------------
;; Callback installation
;; ---------------------------------------------------------------------------

(defn register-callbacks!
  "Install GLFW key, mouse button, and cursor position callbacks on window.
   Must be called from the main thread after glfwCreateWindow."
  [^long window]
  ;; Key callback
  (GLFW/glfwSetKeyCallback
    window
    (reify org.lwjgl.glfw.GLFWKeyCallbackI
      (invoke [_ _win key _sc action _mods]
        (when (>= key 0)
          (cond
            (= action GLFW/GLFW_PRESS)
            (swap! key-states assoc key :pressed)

            (= action GLFW/GLFW_RELEASE)
            (swap! key-states assoc key :released)
            ;; GLFW_REPEAT: treat as held (do not overwrite :pressed on same frame)
            :else nil)))))

  ;; Mouse button callback
  (GLFW/glfwSetMouseButtonCallback
    window
    (reify org.lwjgl.glfw.GLFWMouseButtonCallbackI
      (invoke [_ _win button action _mods]
        (when (>= button 0)
          (cond
            (= action GLFW/GLFW_PRESS)
            (swap! mouse-button-states assoc button :pressed)

            (= action GLFW/GLFW_RELEASE)
            (swap! mouse-button-states assoc button :released))))))

  ;; Cursor position callback
  (GLFW/glfwSetCursorPosCallback
    window
    (reify org.lwjgl.glfw.GLFWCursorPosCallbackI
      (invoke [_ _win x y]
        (reset! cursor-pos [x y]))))

  nil)

;; ---------------------------------------------------------------------------
;; tick! — advance state after each frame's event processing
;; ---------------------------------------------------------------------------

(defn tick!
  "Advance input state. Call once per frame after glfwPollEvents.
   :pressed → :held, :released → :none."
  []
  (let [advance (fn [states]
                  (reduce-kv
                    (fn [m k v]
                      (case v
                        :pressed  (assoc m k :held)
                        :released (dissoc m k)   ; remove :none entries to keep map lean
                        m))
                    states
                    states))]
    (swap! key-states advance)
    (swap! mouse-button-states advance)))

;; ---------------------------------------------------------------------------
;; Key query API
;; ---------------------------------------------------------------------------

(defn key-pressed?
  "True if key was pressed this frame (first frame of press).
   k can be a keyword alias (:space, :a–:z, :0–:9, etc.) or a raw GLFW key code."
  [k]
  (= :pressed (get @key-states (resolve-key k))))

(defn key-held?
  "True if key is being held (pressed or held).
   k can be a keyword alias or a raw GLFW key code."
  [k]
  (contains? #{:pressed :held} (get @key-states (resolve-key k))))

(defn key-released?
  "True if key was released this frame.
   k can be a keyword alias or a raw GLFW key code."
  [k]
  (= :released (get @key-states (resolve-key k))))

;; ---------------------------------------------------------------------------
;; Mouse query API
;; ---------------------------------------------------------------------------

(defn mouse-pressed?
  "True if mouse button was pressed this frame (first frame of press).
   button is an integer index (0=left, 1=right, 2=middle)."
  [button]
  (= :pressed (get @mouse-button-states (int button))))

(defn mouse-held?
  "True if mouse button is being held (pressed or held).
   button is an integer index."
  [button]
  (contains? #{:pressed :held} (get @mouse-button-states (int button))))

(defn mouse-released?
  "True if mouse button was released this frame.
   button is an integer index."
  [button]
  (= :released (get @mouse-button-states (int button))))

(defn mouse-pos
  "Return current cursor position as [x y] in screen coordinates."
  []
  @cursor-pos)
