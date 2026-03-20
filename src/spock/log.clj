(ns spock.log
  "Simple levelled file logger. Thread-safe via an agent.

   Log levels (coarsest to finest):
     :error :warn :info :debug :trace

   Default level: :info — only :error/:warn/:info messages are written.
   Set :log-level :trace in settings.edn for full verbose output.
   Set :log-file in settings.edn to redirect output (default: spock.log)."
  (:import [java.io FileWriter BufferedWriter]
           [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

;; ---------------------------------------------------------------------------
;; Config
;; ---------------------------------------------------------------------------

(def ^:private log-file  (atom (str (System/getProperty "user.dir") "/spock.log")))
(def ^:private log-level (atom :info))

(def ^:private level-rank {:error 0 :warn 1 :info 2 :debug 3 :trace 4})

(defn set-log-file!  [path]  (reset! log-file  path))
(defn set-log-level! [level] (reset! log-level level))

(defn configure!
  "Apply logging config from a settings map.
   Reads :log-file and :log-level keys."
  [settings]
  (when-let [f (:log-file  settings)] (set-log-file!  f))
  (when-let [l (:log-level settings)] (set-log-level! l)))

;; ---------------------------------------------------------------------------
;; Writer
;; ---------------------------------------------------------------------------

(def ^:private fmt (DateTimeFormatter/ofPattern "HH:mm:ss.SSS"))

(def ^:private writer-agent (agent nil))

(defn- open-writer ^BufferedWriter []
  (BufferedWriter. (FileWriter. ^String @log-file true)))

(defn- do-write [_ ^String line]
  (try
    (with-open [w (open-writer)]
      (.write w line)
      (.newLine w))
    (catch Exception e
      (binding [*out* *err*]
        (println "[spock.log] write failed:" (.getMessage e)))))
  nil)

(defn- enabled? [level]
  (<= (get level-rank level 99)
      (get level-rank @log-level 2)))

(defn- write! [level & args]
  (when (enabled? level)
    (let [ts   (.format fmt (LocalDateTime/now))
          msg  (apply str (interpose " " (map str args)))
          line (str "[" ts "] [" (name level) "] " msg)]
      (send writer-agent do-write line))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn error [& args] (apply write! :error args))
(defn warn  [& args] (apply write! :warn  args))
(defn info  [& args] (apply write! :info  args))
(defn debug [& args] (apply write! :debug args))
(defn trace [& args] (apply write! :trace args))

(defn log
  "Backward-compatible alias — logs at :debug level.
   Existing engine/game calls to (log/log ...) are unchanged but are
   now silenced at the default :info level."
  [& args]
  (apply write! :debug args))
