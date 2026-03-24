(ns spock.log
  "Logging utilities. Configures logging to write to a file.")

(require '[clojure.tools.logging :as log]
         '[clojure.java.io :as io]
         '[spock.settings :refer [logging-config]])

;; Ensure log directory exists
(let [{:keys [file]} logging-config]
  (.mkdirs (io/file (.getParent (io/file file)))))

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
  (debug "Debug message with file/line context.")
  (info "Info message with file/line context.")
  (warn "Warn message with file/line context.")
  (error "Error message with file/line context."))