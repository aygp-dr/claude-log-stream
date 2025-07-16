(require '[clojure.data.json :as json])
(require '[clojure.java.io :as io])

(defn parse-real-logs [file-path]
  (with-open [reader (io/reader file-path)]
    (let [lines (line-seq reader)]
      (doall (map json/read-str lines)))))

(defn analyze-real-logs [messages]
  (let [user-msgs (filter #(= (get % "type") "user") messages)
        assistant-msgs (filter #(= (get % "type") "assistant") messages)
        tool-msgs (filter #(= (get % "type") "tool_use") messages)
        all-tools (mapcat (fn [entry]
                             (let [msg (get entry "message")]
                               (if (map? msg)
                                 (let [content (get msg "content")]
                                   (if (vector? content)
                                     (map (fn [c] (get c "name")) 
                                          (filter (fn [item] (= (get item "type") "tool_use")) content))
                                     []))
                                 [])))
                          messages)
        sessions (distinct (map #(get % "sessionId") messages))
        timestamps (map #(get % "timestamp") messages)
        first-timestamp (first (sort timestamps))
        last-timestamp (last (sort timestamps))]
    {:total-messages (count messages)
     :user-messages (count user-msgs)
     :assistant-messages (count assistant-msgs)
     :tool-messages (count tool-msgs)
     :tools-used (distinct (remove nil? all-tools))
     :sessions (count sessions)
     :session-ids sessions
     :timespan {:first first-timestamp :last last-timestamp}}))

(defn extract-tools [messages]
  "Extract tool usage from Claude log messages"
  (mapcat (fn [msg]
            (let [message (get msg "message")
                  content (get message "content")]
              (if (vector? content)
                (filter #(= (get % "type") "tool_use") content)
                [])))
          messages))

(println "=== Claude Real Log Analysis ===")
(let [file-path (first *command-line-args*)
      messages (parse-real-logs file-path)
      analysis (analyze-real-logs messages)
      tools (extract-tools messages)]
  (println "File:" file-path)
  (println "Total messages:" (:total-messages analysis))
  (println "User messages:" (:user-messages analysis))
  (println "Assistant messages:" (:assistant-messages analysis))
  (println "Tool usage events:" (count tools))
  (println "Unique tools used:" (count (:tools-used analysis)))
  (println "Tools:" (:tools-used analysis))
  (println "Sessions:" (:sessions analysis))
  (println "Timespan:" (:first (:timespan analysis)) "to" (:last (:timespan analysis)))
  (when (seq tools)
    (println "\nTool usage breakdown:")
    (doseq [[tool-name count] (frequencies (map #(get % "name") tools))]
      (println " " tool-name ":" count "uses"))))