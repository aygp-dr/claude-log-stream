(require '[clojure.data.json :as json])
(require '[clojure.java.io :as io])

(defn parse-sample []
  (with-open [reader (io/reader "test/resources/sample.jsonl")]
    (let [lines (line-seq reader)]
      (doall (map json/read-str lines)))))

(println "Testing Claude Log Stream parser...")
(def data (parse-sample))
(println "Parsed" (count data) "messages")
(doseq [msg (take 3 data)]
  (println "Message:" (get msg "content" (get msg "toolName"))))
(println "Sample run successful!")