(ns spock.log
  "Logging utilities with configurable log file output.")

(require '[clojure.tools.logging :as log]
         '[clojure.java.io :as io]
         '[spock.settings :as settings])

;; Initialize a log file writer
(defn configure-logging! []
  (let [log-file (:file settings/logging-config)]
    (when log-file
      (.mkdirs (.getParentFile (io/file log-file)))
      (binding [*out* (io/writer log-file)
                *err* (io/writer log-file)]
        (println (str "Logging started at " (java.util.Date.)))))))

(defmacro debug
  "Augments `log/debug` with automatic file and line context."
  [& msg]
  `(log/debug ~@(list* (str *file* ":" (:line (meta &form)) " - ") msg)))

(defmacro info
  "Augments `log/info` with automatic file and line context."
  [& msg]
  `(log/info ~@(list* (str *file* ":" (:line (meta &form)) " - ") msg)))

(defmacro warn
  "Augments `log/warn` with automatic file and line context."
  [& msg]
  `(log/warn ~@(list* (str *file* ":" (:line (meta &form)) " - ") msg)))

(defmacro error
  "Augments `log/error` with automatic file and line context."
  [& msg]
  `(log/error ~@(list* (str *file* ":" (:line (meta &form)) " - ") msg)))

(comment
  (configure-logging!)
  (debug "Debug message with file/line context.")
  (info "Info message with file/line context.")
  (warn "Warn message with file/line context.")
  (error "Error message with file/line context."))