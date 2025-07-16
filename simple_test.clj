(require '[clojure.data.json :as json])
(require '[clojure.java.io :as io])

(defn simple-parse [file-path]
  (with-open [reader (io/reader file-path)]
    (let [lines (line-seq reader)]
      (doall (map json/read-str lines)))))

(defn simple-analyze [messages]
  (let [user-msgs (filter #(= (get % "role") "user") messages)
        assistant-msgs (filter #(= (get % "role") "assistant") messages)
        tool-msgs (filter #(contains? % "toolName") messages)]
    {:total-messages (count messages)
     :user-messages (count user-msgs)
     :assistant-messages (count assistant-msgs)
     :tool-messages (count tool-msgs)
     :tools-used (distinct (map #(get % "toolName") tool-msgs))}))

(println "=== Claude Log Stream - Simple Analysis ===")
(let [file-path (first *command-line-args*)
      messages (simple-parse file-path)
      analysis (simple-analyze messages)]
  (println "File:" file-path)
  (println "Total messages:" (:total-messages analysis))
  (println "User messages:" (:user-messages analysis))
  (println "Assistant messages:" (:assistant-messages analysis))
  (println "Tool messages:" (:tool-messages analysis))
  (println "Tools used:" (:tools-used analysis)))