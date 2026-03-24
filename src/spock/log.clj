(ns spock.log
  "Enhanced logging with file and line context.")

(defmacro log-with-context!
  "Wraps a log call with the file and line number at the call site.
   Example usage: (log-with-context! :info \"Loaded successfully\")"
  [level & msg]
  `(let [file# ~(or *file* "<unknown>")     ;; File where macro is called
         line# ~(or (:line (meta &form)) -1) ;; Line where macro is called
         msg#   (str file# ":" line# " - " ~@msg)]
     (clojure.tools.logging/log ~level msg#)))

(comment
  ;; Example usage:
  (log-with-context! :info "This is a test log. "))