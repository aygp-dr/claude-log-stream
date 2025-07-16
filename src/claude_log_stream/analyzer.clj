(ns claude-log-stream.analyzer
  "Advanced analytics engine for Claude Code logs with streaming processing."
  (:require [clojure.core.async :as async :refer [>! <! go go-loop chan pipeline-async]]
            [clojure.tools.logging :as log]
            [claude-log-stream.parser :as parser]
            [clj-time.core :as time]
            [clj-time.format :as time-format]
            [clj-time.coerce :as time-coerce])
  (:import [java.time Instant Duration]))

(defn session-duration
  "Calculate session duration from first to last message."
  [session-messages]
  (when (seq session-messages)
    (let [timestamps (keep :timestamp session-messages)
          start (apply min timestamps)
          end (apply max timestamps)]
      (when (and start end)
        (Duration/between start end)))))

(defn conversation-flow-analysis
  "Analyze conversation flow patterns within a session."
  [session-messages]
  (let [ordered-messages (sort-by :timestamp session-messages)
        role-transitions (->> ordered-messages
                             (map :role)
                             (partition 2 1)
                             (map vec)
                             frequencies)
        tool-usage-patterns (->> ordered-messages
                                (filter #(= (:message-type %) :tool-usage))
                                (map :tool-name)
                                frequencies)]
    {:role-transitions role-transitions
     :tool-usage-patterns tool-usage-patterns
     :message-count (count ordered-messages)
     :unique-tools (count (distinct (map :tool-name 
                                        (filter #(= (:message-type %) :tool-usage) 
                                        ordered-messages))))}))

(defn cluster-conversations
  "Cluster conversations by similarity in tool usage and patterns."
  [conversations]
  (let [conversation-features (map (fn [[conv-id messages]]
                                    (let [flow (conversation-flow-analysis messages)]
                                      {:conversation-id conv-id
                                       :tool-count (:unique-tools flow)
                                       :message-count (:message-count flow)
                                       :tools-used (keys (:tool-usage-patterns flow))
                                       :duration (session-duration messages)}))
                                  conversations)]
    ;; Simple clustering by tool usage similarity
    (group-by :tool-count conversation-features)))

(defn productivity-metrics
  "Calculate productivity metrics for sessions."
  [session-messages]
  (let [duration (session-duration session-messages)
        tool-usages (count (filter #(= (:message-type %) :tool-usage) session-messages))
        assistant-responses (count (filter #(= (:role %) "assistant") session-messages))
        user-messages (count (filter #(= (:role %) "user") session-messages))]
    {:duration-minutes (when duration (.toMinutes duration))
     :tools-per-minute (when (and duration (> (.toMinutes duration) 0))
                         (/ tool-usages (.toMinutes duration)))
     :responses-per-minute (when (and duration (> (.toMinutes duration) 0))
                             (/ assistant-responses (.toMinutes duration)))
     :interaction-ratio (when (> user-messages 0)
                          (/ assistant-responses user-messages))
     :tool-usage-count tool-usages
     :total-interactions (+ user-messages assistant-responses)}))

(defn tool-effectiveness-analysis
  "Analyze tool effectiveness and usage patterns."
  [messages]
  (let [tool-messages (filter #(= (:message-type %) :tool-usage) messages)
        tool-stats (group-by :tool-name tool-messages)]
    (map (fn [[tool-name usages]]
           (let [sessions (distinct (map :session-id usages))
                 success-rate (/ (count (filter :tool-output usages))
                               (count usages))]
             {:tool-name tool-name
              :total-usage (count usages)
              :unique-sessions (count sessions)
              :success-rate success-rate
              :average-per-session (/ (count usages) (count sessions))
              :first-used (apply min (map :timestamp usages))
              :last-used (apply max (map :timestamp usages))}))
         tool-stats)))

(defn cost-optimization-insights
  "Generate cost optimization recommendations."
  [messages]
  (let [costly-messages (filter :cost-usd messages)
        cost-by-model (group-by :model costly-messages)
        cost-by-session (group-by :session-id costly-messages)]
    {:total-cost (apply + (map :cost-usd costly-messages))
     :cost-by-model (map (fn [[model msgs]]
                           {:model model
                            :cost (apply + (map :cost-usd msgs))
                            :message-count (count msgs)
                            :average-cost-per-message (/ (apply + (map :cost-usd msgs))
                                                        (count msgs))})
                        cost-by-model)
     :expensive-sessions (take 10 (sort-by (fn [[_ msgs]]
                                            (- (apply + (map :cost-usd msgs))))
                                          cost-by-session))
     :recommendations (let [avg-cost (/ (apply + (map :cost-usd costly-messages))
                                       (count costly-messages))]
                        (filter #(> (:average-cost-per-message %) (* 1.5 avg-cost))
                               (map (fn [[model msgs]]
                                      {:model model
                                       :average-cost-per-message (/ (apply + (map :cost-usd msgs))
                                                                   (count msgs))})
                                    cost-by-model)))}))

(defn streaming-processor
  "Create a streaming processor for real-time analysis."
  [analysis-fn buffer-size]
  (let [input-chan (chan buffer-size)
        output-chan (chan)]
    (go-loop [buffer []]
      (if-let [message (<! input-chan)]
        (let [new-buffer (conj buffer message)]
          (if (>= (count new-buffer) buffer-size)
            (do
              (>! output-chan (analysis-fn new-buffer))
              (recur []))
            (recur new-buffer)))
        (when (seq buffer)
          (>! output-chan (analysis-fn buffer))
          (async/close! output-chan))))
    {:input input-chan
     :output output-chan}))

(defn real-time-analyzer
  "Set up real-time analysis pipeline."
  [analysis-functions]
  (let [processors (map (fn [[name fn]]
                          [name (streaming-processor fn 100)])
                       analysis-functions)]
    (into {} processors)))

(defn analyze-logs
  "Main analysis function that processes all log data."
  [messages]
  (log/info "Starting comprehensive log analysis for" (count messages) "messages")
  
  (let [valid-messages (filter :valid? messages)
        sessions (parser/group-by-session valid-messages)
        conversations (parser/group-by-conversation valid-messages)
        message-types (parser/group-by-message-type valid-messages)]
    
    (log/info "Found" (count sessions) "sessions and" (count conversations) "conversations")
    
    {:summary {:total-messages (count messages)
               :valid-messages (count valid-messages)
               :invalid-messages (- (count messages) (count valid-messages))
               :unique-sessions (count sessions)
               :unique-conversations (count conversations)
               :message-types (into {} (map (fn [[type msgs]] [type (count msgs)]) message-types))}
     
     :sessions (map (fn [[session-id session-messages]]
                      {:session-id session-id
                       :message-count (count session-messages)
                       :duration (session-duration session-messages)
                       :productivity (productivity-metrics session-messages)
                       :conversation-flow (conversation-flow-analysis session-messages)})
                   sessions)
     
     :conversations {:clusters (cluster-conversations conversations)
                     :flow-patterns (map (fn [[conv-id msgs]]
                                          [conv-id (conversation-flow-analysis msgs)])
                                        conversations)}
     
     :tools {:effectiveness (tool-effectiveness-analysis valid-messages)
             :usage-patterns (parser/extract-tool-usage valid-messages)}
     
     :tokens (parser/calculate-token-stats valid-messages)
     
     :costs (cost-optimization-insights valid-messages)
     
     :temporal {:first-message (apply min (map :timestamp valid-messages))
                :last-message (apply max (map :timestamp valid-messages))
                :time-distribution (frequencies (map #(time-format/unparse 
                                                      (time-format/formatter "yyyy-MM-dd-HH")
                                                      (time-coerce/from-long 
                                                       (.toEpochMilli (:timestamp %))))
                                                valid-messages))}}))

(defn print-summary
  "Print a formatted summary of the analysis results."
  [analysis]
  (println "\n=== Claude Log Stream Analysis Report ===")
  (println)
  
  (let [summary (:summary analysis)]
    (println \"📊 Summary:\")
    (printf \"  Total Messages: %d\\n\" (:total-messages summary))
    (printf \"  Valid Messages: %d (%.1f%%)\\n\" 
            (:valid-messages summary)
            (* 100.0 (/ (:valid-messages summary) (:total-messages summary))))
    (printf \"  Sessions: %d\\n\" (:unique-sessions summary))
    (printf \"  Conversations: %d\\n\" (:unique-conversations summary))
    (println))
  
  (let [tokens (:tokens analysis)]
    (println \"💬 Token Usage:\")
    (printf \"  Total Tokens: %d\\n\" (:total-tokens tokens))
    (printf \"  Average per Message: %.1f\\n\" (:average-tokens tokens))
    (printf \"  Max Tokens: %d\\n\" (:max-tokens tokens))
    (println))
  
  (let [costs (:costs analysis)]
    (when (> (:total-cost costs) 0)
      (println \"💰 Cost Analysis:\")
      (printf \"  Total Cost: $%.2f\\n\" (:total-cost costs))
      (println \"  By Model:\")
      (doseq [{:keys [model cost message-count]} (:cost-by-model costs)]
        (printf \"    %s: $%.2f (%d messages)\\n\" model cost message-count))
      (println)))
  
  (let [tools (:tools analysis)]
    (println \"🔧 Tool Usage:\")
    (printf \"  Unique Tools: %d\\n\" (count (:effectiveness tools)))
    (doseq [{:keys [tool-name total-usage unique-sessions success-rate]} 
            (take 10 (sort-by :total-usage > (:effectiveness tools)))]
      (printf \"    %s: %d uses across %d sessions (%.1f%% success)\\n\" 
              tool-name total-usage unique-sessions (* 100.0 success-rate)))
    (println)))