(ns spock.log
  "Simple file logger. Thread-safe via an agent.
   Writes to spock.log in the working directory by default."
  (:import [java.io FileWriter BufferedWriter]
           [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

(def ^:private log-file
  (atom (str (System/getProperty "user.dir") "/spock.log")))

(def ^:private fmt
  (DateTimeFormatter/ofPattern "HH:mm:ss.SSS"))

(def ^:private writer-agent
  (agent nil))

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

(defn log [& args]
  (let [ts  (.format fmt (LocalDateTime/now))
        msg (apply str (interpose " " (map str args)))
        line (str "[" ts "] " msg)]
    (send writer-agent do-write line)))

(defn set-log-file! [path]
  (reset! log-file path))
