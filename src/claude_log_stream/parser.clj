(ns claude-log-stream.parser
  "JSONL parser for Claude Code log format with schema validation."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jsonista.core :as j]
            [camel-snake-kebab.core :as csk])
  (:import [java.time Instant]
           [java.io BufferedReader]))

;; Specs for Claude Code log message types
(s/def ::timestamp string?)
(s/def ::session-id string?)
(s/def ::message-id string?)
(s/def ::conversation-id string?)
(s/def ::content string?)
(s/def ::role #{"user" "assistant" "system"})
(s/def ::tool-name string?)
(s/def ::tool-input map?)
(s/def ::tool-output any?)
(s/def ::token-count pos-int?)
(s/def ::model string?)
(s/def ::cost-usd number?)

;; User message schema
(s/def ::user-message
  (s/keys :req-un [::timestamp ::session-id ::message-id ::conversation-id
                   ::content ::role]
          :opt-un [::token-count]))

;; Assistant message schema  
(s/def ::assistant-message
  (s/keys :req-un [::timestamp ::session-id ::message-id ::conversation-id
                   ::content ::role ::model]
          :opt-un [::token-count ::cost-usd]))

;; System message schema
(s/def ::system-message
  (s/keys :req-un [::timestamp ::session-id ::message-id ::conversation-id
                   ::content ::role]
          :opt-un [::token-count]))

;; Tool usage message schema
(s/def ::tool-usage
  (s/keys :req-un [::timestamp ::session-id ::message-id ::conversation-id
                   ::tool-name ::tool-input]
          :opt-un [::tool-output ::token-count]))

;; Summary message schema
(s/def ::summary-message
  (s/keys :req-un [::timestamp ::session-id ::conversation-id]
          :opt-un [::token-count ::cost-usd]))

(defn kebab-case-keys
  "Convert map keys from camelCase to kebab-case."
  [m]
  (when m
    (reduce-kv (fn [acc k v]
                 (assoc acc (csk/->kebab-case-keyword k) v))
               {}
               m)))

(defn parse-timestamp
  "Parse ISO timestamp string to Instant."
  [timestamp-str]
  (try
    (Instant/parse timestamp-str)
    (catch Exception e
      (log/warn "Failed to parse timestamp:" timestamp-str)
      nil)))

(defn infer-message-type
  "Infer message type from message content."
  [msg]
  (cond
    (:tool-name msg) :tool-usage
    (:role msg) (case (:role msg)
                  "user" :user-message
                  "assistant" :assistant-message
                  "system" :system-message
                  :unknown)
    (and (:conversation-id msg) 
         (not (:message-id msg))) :summary-message
    :else :unknown))

(defn validate-message
  "Validate message against appropriate schema."
  [msg]
  (let [msg-type (infer-message-type msg)
        spec (case msg-type
               :user-message ::user-message
               :assistant-message ::assistant-message
               :system-message ::system-message
               :tool-usage ::tool-usage
               :summary-message ::summary-message
               nil)]
    (if spec
      (if (s/valid? spec msg)
        (assoc msg :message-type msg-type :valid? true)
        (do
          (log/warn "Invalid message:" (s/explain-str spec msg))
          (assoc msg :message-type msg-type :valid? false :errors (s/explain-data spec msg))))
      (do
        (log/warn "Unknown message type:" msg)
        (assoc msg :message-type :unknown :valid? false)))))

(defn parse-jsonl-line
  "Parse a single JSONL line and validate."
  [line line-number]
  (try
    (when-not (str/blank? line)
      (let [parsed (-> line
                       (json/read-str :key-fn keyword)
                       kebab-case-keys
                       (update :timestamp parse-timestamp))
            validated (validate-message parsed)]
        (assoc validated :line-number line-number)))
    (catch Exception e
      (log/error "Failed to parse line" line-number ":" (.getMessage e))
      {:line-number line-number
       :valid? false
       :error (.getMessage e)
       :raw-line line})))

(defn parse-jsonl-file
  "Parse entire JSONL file with memory-efficient streaming."
  [file-path]
  (log/info "Parsing JSONL file:" file-path)
  (with-open [reader (io/reader file-path)]
    (let [lines (line-seq reader)
          results (map-indexed (fn [idx line]
                                 (parse-jsonl-line line (inc idx)))
                               lines)
          parsed-messages (doall (remove nil? results))]
      
      (log/info "Parsed" (count parsed-messages) "messages")
      (let [valid-count (count (filter :valid? parsed-messages))
            invalid-count (- (count parsed-messages) valid-count)]
        (log/info "Valid messages:" valid-count)
        (when (> invalid-count 0)
          (log/warn "Invalid messages:" invalid-count)))
      
      parsed-messages)))

(defn parse-jsonl-stream
  "Parse JSONL data from input stream for real-time processing."
  [input-stream callback-fn]
  (with-open [reader (BufferedReader. (io/reader input-stream))]
    (loop [line-number 1]
      (when-let [line (.readLine reader)]
        (let [parsed (parse-jsonl-line line line-number)]
          (when parsed
            (callback-fn parsed)))
        (recur (inc line-number))))))

(defn group-by-message-type
  "Group parsed messages by their type."
  [messages]
  (group-by :message-type (filter :valid? messages)))

(defn group-by-session
  "Group messages by session ID."
  [messages]
  (group-by :session-id (filter :valid? messages)))

(defn group-by-conversation
  "Group messages by conversation ID."
  [messages]
  (group-by :conversation-id (filter :valid? messages)))

(defn extract-tool-usage
  "Extract tool usage patterns from messages."
  [messages]
  (->> messages
       (filter #(= (:message-type %) :tool-usage))
       (group-by :tool-name)
       (map (fn [[tool-name usages]]
              {:tool-name tool-name
               :usage-count (count usages)
               :sessions (distinct (map :session-id usages))
               :first-used (apply min (map :timestamp usages))
               :last-used (apply max (map :timestamp usages))}))))

(defn calculate-token-stats
  "Calculate token usage statistics."
  [messages]
  (let [valid-messages (filter :valid? messages)
        token-counts (keep :token-count valid-messages)]
    {:total-tokens (apply + token-counts)
     :average-tokens (if (seq token-counts)
                       (/ (apply + token-counts) (count token-counts))
                       0)
     :max-tokens (if (seq token-counts) (apply max token-counts) 0)
     :min-tokens (if (seq token-counts) (apply min token-counts) 0)
     :message-count (count valid-messages)}))

(defn calculate-cost-stats
  "Calculate cost statistics from messages."
  [messages]
  (let [costs (keep :cost-usd (filter :valid? messages))]
    {:total-cost (apply + costs)
     :average-cost (if (seq costs)
                     (/ (apply + costs) (count costs))
                     0)
     :cost-by-session (group-by :session-id 
                                (filter :cost-usd (filter :valid? messages)))}))

(comment
  ;; Example usage
  (def sample-messages (parse-jsonl-file "test-data/claude-logs.jsonl"))
  (group-by-message-type sample-messages)
  (extract-tool-usage sample-messages)
  (calculate-token-stats sample-messages))