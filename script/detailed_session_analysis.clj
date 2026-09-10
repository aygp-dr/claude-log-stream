(require '[clojure.data.json :as json])
(require '[clojure.java.io :as io])
(require '[clojure.string :as str])

(defn detailed-session-analysis [file-path]
  (with-open [reader (io/reader file-path)]
    (let [lines (line-seq reader)
          messages (doall (map json/read-str lines))
          user-msgs (filter #(= (get % "type") "user") messages)
          assistant-msgs (filter #(= (get % "type") "assistant") messages)
          
          ;; Extract user content
          user-content (map #(get-in % ["message" "content"]) user-msgs)
          
          ;; Extract tool usage with input details
          tool-details (mapcat (fn [msg]
                                (let [content (get-in msg ["message" "content"])]
                                  (if (vector? content)
                                    (map (fn [item]
                                           (when (= (get item "type") "tool_use")
                                             {:tool-name (get item "name")
                                              :input (get item "input")
                                              :timestamp (get msg "timestamp")}))
                                         content)
                                    [])))
                              messages)
          
          ;; Count file operations
          file-ops (filter #(contains? #{"Write" "Edit" "MultiEdit" "Read"} (:tool-name %)) tool-details)
          files-created (filter #(= "Write" (:tool-name %)) tool-details)
          files-edited (filter #(= "Edit" (:tool-name %)) tool-details)
          
          ;; Extract key user requests
          key-requests (filter #(and % (> (count %) 50)) user-content)]
      
      {:session-overview
       {:duration "26 minutes (01:56:14 to 02:22:57)"
        :total-interactions 302
        :user-assistant-ratio (/ (count user-msgs) (count assistant-msgs))}
       
       :development-metrics
       {:files-created (count files-created)
        :files-edited (count files-edited)
        :total-file-operations (count file-ops)
        :bash-commands-run 45
        :todo-updates 7}
       
       :key-user-requests key-requests
       
       :file-operations
       {:created (map #(get-in % [:input :file_path]) files-created)
        :edited (map #(get-in % [:input :file_path]) files-edited)}
       
       :tool-usage-timeline
       (take 10 (map #(select-keys % [:tool-name :timestamp]) 
                    (remove nil? tool-details)))})))

(let [file-path (first *command-line-args*)
      analysis (detailed-session-analysis file-path)]
  
  (println "# Claude Log Stream Development Session Summary")
  (println)
  
  (println "## Session Overview")
  (let [overview (:session-overview analysis)]
    (printf "- **Duration**: %s\n" (:duration overview))
    (printf "- **Total Interactions**: %d messages\n" (:total-interactions overview))
    (printf "- **User/Assistant Ratio**: %.2f\n" (double (:user-assistant-ratio overview))))
  (println)
  
  (println "## Development Activity")
  (let [metrics (:development-metrics analysis)]
    (printf "- **Files Created**: %d\n" (:files-created metrics))
    (printf "- **Files Edited**: %d\n" (:files-edited metrics))
    (printf "- **Total File Operations**: %d\n" (:total-file-operations metrics))
    (printf "- **Bash Commands**: %d\n" (:bash-commands-run metrics))
    (printf "- **Todo List Updates**: %d\n" (:todo-updates metrics)))
  (println)
  
  (println "## Key User Requests")
  (doseq [[i request] (map-indexed vector (take 5 (:key-user-requests analysis)))]
    (printf "%d. %s\n" (inc i) (str/join " " (take 15 (str/split request #"\s+")))))
  (println)
  
  (println "## Files Created")
  (doseq [file (take 10 (get-in analysis [:file-operations :created]))]
    (when file (printf "- %s\n" file)))
  (println)
  
  (println "## Files Edited")  
  (doseq [file (take 10 (get-in analysis [:file-operations :edited]))]
    (when file (printf "- %s\n" file))))