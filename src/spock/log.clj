(ns spock.util.logging
  "Enhanced logging with file and line context.")

(defmacro log-with-context!
  "Wraps a log call with the file and line number at the call site.
   (e.g., (log-with-context! :info "message") logs 'file.clj:12 - message')"
  [level & msg]
  `(let [file# ~(or *file* "<unknown>")            ;; File where macro is called
         line# ~(or (:line (meta &form)) -1)]       ;; Line where macro is called
     (clojure.tools.logging/log
       (str file# ":" line# " - " ~@msg)         ;; Include file:line in message
       :throwable (if (> (count '~msg) 1) (last '~msg) nil)
       :level ~level)))

(comment
  ;; Example usage:
  (log-with-context! :info "Loaded successfully"))