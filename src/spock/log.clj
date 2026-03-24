(ns spock.log
  "Logging utilities.")

(require '[clojure.tools.logging :as log])

(defmacro debug
  "Augments `log/debug` with automatic file and line context."
  [& msg]
  `(log/debug ~(str *file* ":" (:line (meta &form)) " - " ~@msg)))

(defmacro info
  "Augments `log/info` with automatic file and line context."
  [& msg]
  `(log/info ~(str *file* ":" (:line (meta &form)) " - " ~@msg)))

(defmacro warn
  "Augments `log/warn` with automatic file and line context."
  [& msg]
  `(log/warn ~(str *file* ":" (:line (meta &form)) " - " ~@msg)))

(defmacro error
  "Augments `log/error` with automatic file and line context."
  [& msg]
  `(log/error ~(str *file* ":" (:line (meta &form)) " - " ~@msg)))

(comment
  (debug "Debug message with file/line context.")
  (info "Info message with file/line context.")
  (warn "Warn message with file/line context.")
  (error "Error message with file/line context."))