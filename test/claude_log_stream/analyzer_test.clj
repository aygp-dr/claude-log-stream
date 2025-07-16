(ns claude-log-stream.analyzer-test
  (:require [clojure.test :refer [deftest is testing]]
            [claude-log-stream.analyzer :as analyzer])
  (:import [java.time Instant Duration]))

(def sample-session-messages
  [{:timestamp (Instant/parse \"2024-01-15T10:30:00Z\")
    :session-id \"session-123\"
    :message-id \"msg-1\"
    :role \"user\"
    :message-type :user-message
    :valid? true}
   {:timestamp (Instant/parse \"2024-01-15T10:31:00Z\")
    :session-id \"session-123\"
    :message-id \"msg-2\"
    :role \"assistant\"
    :message-type :assistant-message
    :valid? true}
   {:timestamp (Instant/parse \"2024-01-15T10:32:00Z\")
    :session-id \"session-123\"
    :message-id \"msg-3\"
    :tool-name \"Read\"
    :message-type :tool-usage
    :valid? true}
   {:timestamp (Instant/parse \"2024-01-15T10:35:00Z\")
    :session-id \"session-123\"
    :message-id \"msg-4\"
    :role \"assistant\"
    :message-type :assistant-message
    :valid? true}])

(deftest test-session-duration
  (testing \"calculates session duration correctly\"
    (let [duration (analyzer/session-duration sample-session-messages)]
      (is (instance? Duration duration))
      (is (= 5 (.toMinutes duration)))))
  
  (testing \"handles empty session\"
    (let [duration (analyzer/session-duration [])]
      (is (nil? duration))))
  
  (testing \"handles single message\"
    (let [single-message [(first sample-session-messages)]
          duration (analyzer/session-duration single-message)]
      (is (instance? Duration duration))
      (is (= 0 (.toMinutes duration))))))

(deftest test-conversation-flow-analysis
  (testing \"analyzes conversation flow patterns\"
    (let [flow (analyzer/conversation-flow-analysis sample-session-messages)]
      (is (= 4 (:message-count flow)))
      (is (= 1 (:unique-tools flow)))
      (is (contains? (:role-transitions flow) [\"user\" \"assistant\"]))
      (is (contains? (:tool-usage-patterns flow) \"Read\")))))

(deftest test-productivity-metrics
  (testing \"calculates productivity metrics\"
    (let [metrics (analyzer/productivity-metrics sample-session-messages)]
      (is (= 5 (:duration-minutes metrics)))
      (is (= 1 (:tool-usage-count metrics)))
      (is (= 4 (:total-interactions metrics)))
      (is (= 2.0 (:interaction-ratio metrics))) ;; 2 assistant / 1 user
      (is (< 0 (:tools-per-minute metrics)))
      (is (< 0 (:responses-per-minute metrics))))))

(deftest test-cluster-conversations
  (testing \"clusters conversations by similarity\"
    (let [conversations {\"conv-1\" sample-session-messages
                        \"conv-2\" (take 2 sample-session-messages)}
          clusters (analyzer/cluster-conversations conversations)]
      (is (map? clusters))
      (is (every? vector? (vals clusters))))))

(deftest test-tool-effectiveness-analysis
  (testing \"analyzes tool effectiveness\"
    (let [tool-messages [{:message-type :tool-usage
                         :tool-name \"Read\"
                         :session-id \"session-1\"
                         :timestamp (Instant/now)
                         :tool-output \"success\"}
                        {:message-type :tool-usage
                         :tool-name \"Read\"
                         :session-id \"session-2\"
                         :timestamp (Instant/now)
                         :tool-output nil}
                        {:message-type :tool-usage
                         :tool-name \"Write\"
                         :session-id \"session-1\"
                         :timestamp (Instant/now)
                         :tool-output \"success\"}]
          analysis (analyzer/tool-effectiveness-analysis tool-messages)]
      (is (= 2 (count analysis)))
      (let [read-analysis (first (filter #(= \"Read\" (:tool-name %)) analysis))]
        (is (= 2 (:total-usage read-analysis)))
        (is (= 2 (:unique-sessions read-analysis)))
        (is (= 0.5 (:success-rate read-analysis)))))))

(deftest test-cost-optimization-insights
  (testing \"generates cost optimization insights\"
    (let [messages [{:cost-usd 0.05 :model \"claude-3-opus\" :session-id \"session-1\"}
                   {:cost-usd 0.03 :model \"claude-3-opus\" :session-id \"session-1\"}
                   {:cost-usd 0.10 :model \"claude-3-sonnet\" :session-id \"session-2\"}]
          insights (analyzer/cost-optimization-insights messages)]
      (is (= 0.18 (:total-cost insights)))
      (is (= 2 (count (:cost-by-model insights))))
      (is (map? (first (:expensive-sessions insights)))))))

(deftest test-analyze-logs
  (testing \"performs comprehensive log analysis\"
    (let [messages (map #(assoc % :valid? true) sample-session-messages)
          analysis (analyzer/analyze-logs messages)]
      (is (contains? analysis :summary))
      (is (contains? analysis :sessions))
      (is (contains? analysis :conversations))
      (is (contains? analysis :tools))
      (is (contains? analysis :tokens))
      (is (contains? analysis :costs))
      (is (contains? analysis :temporal))
      
      ;; Check summary stats
      (is (= 4 (get-in analysis [:summary :total-messages])))
      (is (= 4 (get-in analysis [:summary :valid-messages])))
      (is (= 1 (get-in analysis [:summary :unique-sessions])))
      
      ;; Check message type distribution
      (let [message-types (get-in analysis [:summary :message-types])]
        (is (= 1 (:user-message message-types)))
        (is (= 2 (:assistant-message message-types)))
        (is (= 1 (:tool-usage message-types)))))))

(deftest test-streaming-processor
  (testing \"creates streaming processor\"
    (let [processor (analyzer/streaming-processor identity 2)]
      (is (contains? processor :input))
      (is (contains? processor :output)))))

(deftest test-real-time-analyzer
  (testing \"sets up real-time analysis pipeline\"
    (let [analysis-fns {\"test-analyzer\" identity}
          analyzers (analyzer/real-time-analyzer analysis-fns)]
      (is (contains? analyzers \"test-analyzer\"))
      (is (contains? (get analyzers \"test-analyzer\") :input))
      (is (contains? (get analyzers \"test-analyzer\") :output)))))