(ns claude-log-stream.parser-test
  (:require [clojure.test :refer [deftest is testing]]
            [claude-log-stream.parser :as parser]
            [clojure.data.json :as json])
  (:import [java.time Instant]))

(def sample-user-message
  {:timestamp \"2024-01-15T10:30:00Z\"
   :session-id \"session-123\"
   :message-id \"msg-456\"
   :conversation-id \"conv-789\"
   :content \"Help me analyze this data\"
   :role \"user\"
   :token-count 5})

(def sample-assistant-message
  {:timestamp \"2024-01-15T10:31:00Z\"
   :session-id \"session-123\"
   :message-id \"msg-457\"
   :conversation-id \"conv-789\"
   :content \"I'll help you analyze the data. Let me start by reading the file.\"
   :role \"assistant\"
   :model \"claude-3-opus\"
   :token-count 15
   :cost-usd 0.0045})

(def sample-tool-usage
  {:timestamp \"2024-01-15T10:31:30Z\"
   :session-id \"session-123\"
   :message-id \"msg-458\"
   :conversation-id \"conv-789\"
   :tool-name \"Read\"
   :tool-input {:file-path \"/data/sales.csv\"}
   :tool-output \"CSV data with 1000 rows...\"
   :token-count 250})

(def sample-system-message
  {:timestamp \"2024-01-15T10:30:00Z\"
   :session-id \"session-123\"
   :message-id \"msg-455\"
   :conversation-id \"conv-789\"
   :content \"You are Claude, an AI assistant created by Anthropic.\"
   :role \"system\"})

(def sample-summary-message
  {:timestamp \"2024-01-15T10:45:00Z\"
   :session-id \"session-123\"
   :conversation-id \"conv-789\"
   :token-count 500
   :cost-usd 0.025})

(deftest test-kebab-case-keys
  (testing \"converts camelCase keys to kebab-case\"
    (let [input {:firstName \"John\" :lastName \"Doe\" :phoneNumber \"555-1234\"}
          result (parser/kebab-case-keys input)]
      (is (= (:first-name result) \"John\"))
      (is (= (:last-name result) \"Doe\"))
      (is (= (:phone-number result) \"555-1234\")))))

(deftest test-parse-timestamp
  (testing \"parses valid ISO timestamp\"
    (let [timestamp \"2024-01-15T10:30:00Z\"
          result (parser/parse-timestamp timestamp)]
      (is (instance? Instant result))
      (is (= (.toString result) timestamp))))
  
  (testing \"handles invalid timestamp gracefully\"
    (let [result (parser/parse-timestamp \"invalid-timestamp\")]
      (is (nil? result)))))

(deftest test-infer-message-type
  (testing \"infers user message type\"
    (is (= :user-message (parser/infer-message-type {:role \"user\"}))))
  
  (testing \"infers assistant message type\"
    (is (= :assistant-message (parser/infer-message-type {:role \"assistant\"}))))
  
  (testing \"infers system message type\"
    (is (= :system-message (parser/infer-message-type {:role \"system\"}))))
  
  (testing \"infers tool usage type\"
    (is (= :tool-usage (parser/infer-message-type {:tool-name \"Read\"}))))
  
  (testing \"infers summary message type\"
    (is (= :summary-message (parser/infer-message-type {:conversation-id \"conv-123\"}))))
  
  (testing \"returns unknown for unrecognized types\"
    (is (= :unknown (parser/infer-message-type {:random-field \"value\"})))))

(deftest test-validate-message
  (testing \"validates user message successfully\"
    (let [result (parser/validate-message sample-user-message)]
      (is (:valid? result))
      (is (= (:message-type result) :user-message))))
  
  (testing \"validates assistant message successfully\"
    (let [result (parser/validate-message sample-assistant-message)]
      (is (:valid? result))
      (is (= (:message-type result) :assistant-message))))
  
  (testing \"validates tool usage successfully\"
    (let [result (parser/validate-message sample-tool-usage)]
      (is (:valid? result))
      (is (= (:message-type result) :tool-usage))))
  
  (testing \"validates system message successfully\"
    (let [result (parser/validate-message sample-system-message)]
      (is (:valid? result))
      (is (= (:message-type result) :system-message))))
  
  (testing \"validates summary message successfully\"
    (let [result (parser/validate-message sample-summary-message)]
      (is (:valid? result))
      (is (= (:message-type result) :summary-message))))
  
  (testing \"marks invalid message as invalid\"
    (let [invalid-message {:role \"user\"} ;; missing required fields
          result (parser/validate-message invalid-message)]
      (is (not (:valid? result)))
      (is (contains? result :errors)))))

(deftest test-parse-jsonl-line
  (testing \"parses valid JSONL line\"
    (let [jsonl-line (json/write-str sample-user-message)
          result (parser/parse-jsonl-line jsonl-line 1)]
      (is (:valid? result))
      (is (= (:line-number result) 1))
      (is (= (:message-type result) :user-message))))
  
  (testing \"handles invalid JSON gracefully\"
    (let [invalid-line \"{ invalid json\"
          result (parser/parse-jsonl-line invalid-line 1)]
      (is (not (:valid? result)))
      (is (= (:line-number result) 1))
      (is (contains? result :error))))
  
  (testing \"skips blank lines\"
    (let [result (parser/parse-jsonl-line \"\" 1)]
      (is (nil? result))))
  
  (testing \"skips whitespace-only lines\"
    (let [result (parser/parse-jsonl-line \"   \" 1)]
      (is (nil? result)))))

(deftest test-group-by-message-type
  (testing \"groups messages by type correctly\"
    (let [messages [(assoc sample-user-message :valid? true :message-type :user-message)
                   (assoc sample-assistant-message :valid? true :message-type :assistant-message)
                   (assoc sample-tool-usage :valid? true :message-type :tool-usage)]
          grouped (parser/group-by-message-type messages)]
      (is (= 1 (count (:user-message grouped))))
      (is (= 1 (count (:assistant-message grouped))))
      (is (= 1 (count (:tool-usage grouped)))))))

(deftest test-group-by-session
  (testing \"groups messages by session ID\"
    (let [messages [(assoc sample-user-message :valid? true :session-id \"session-1\")
                   (assoc sample-assistant-message :valid? true :session-id \"session-1\")
                   (assoc sample-tool-usage :valid? true :session-id \"session-2\")]
          grouped (parser/group-by-session messages)]
      (is (= 2 (count (get grouped \"session-1\"))))
      (is (= 1 (count (get grouped \"session-2\")))))))

(deftest test-extract-tool-usage
  (testing \"extracts tool usage patterns\"
    (let [messages [(assoc sample-tool-usage :valid? true :message-type :tool-usage :tool-name \"Read\")
                   (assoc sample-tool-usage :valid? true :message-type :tool-usage :tool-name \"Write\")
                   (assoc sample-tool-usage :valid? true :message-type :tool-usage :tool-name \"Read\")]
          usage (parser/extract-tool-usage messages)]
      (is (= 2 (count usage)))
      (let [read-usage (first (filter #(= (:tool-name %) \"Read\") usage))]
        (is (= 2 (:usage-count read-usage)))))))

(deftest test-calculate-token-stats
  (testing \"calculates token statistics correctly\"
    (let [messages [(assoc sample-user-message :valid? true :token-count 10)
                   (assoc sample-assistant-message :valid? true :token-count 20)
                   (assoc sample-tool-usage :valid? true :token-count 30)]
          stats (parser/calculate-token-stats messages)]
      (is (= 60 (:total-tokens stats)))
      (is (= 20.0 (:average-tokens stats)))
      (is (= 30 (:max-tokens stats)))
      (is (= 10 (:min-tokens stats)))
      (is (= 3 (:message-count stats)))))
  
  (testing \"handles messages without token counts\"
    (let [messages [(dissoc sample-user-message :token-count)
                   (assoc sample-assistant-message :valid? true :token-count 20)]
          stats (parser/calculate-token-stats messages)]
      (is (= 20 (:total-tokens stats)))
      (is (= 20.0 (:average-tokens stats)))
      (is (= 1 (:message-count stats))))))

(deftest test-calculate-cost-stats
  (testing \"calculates cost statistics\"
    (let [messages [(assoc sample-assistant-message :valid? true :cost-usd 0.05)
                   (assoc sample-assistant-message :valid? true :cost-usd 0.03)]
          stats (parser/calculate-cost-stats messages)]
      (is (= 0.08 (:total-cost stats)))
      (is (= 0.04 (:average-cost stats))))))