(ns claude-log-stream.parser
  "JSONL parser for Claude Code log format with schema validation."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jsonista.core :as j]
            [camel-snake-kebab.core :as csk]
            [claude-log-stream.specs :as specs])
  (:import [java.time Instant]
           [java.io BufferedReader]))

;; Specs for Claude Code log message types
;; ISO-8601 in the raw JSONL; parse-jsonl-line validates after converting
;; it to an Instant, so accept both.
(s/def ::timestamp (s/or :instant inst? :iso-string string?))
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

(s/fdef kebab-case-keys
  :args (s/cat :m (s/nilable ::specs/json-object))
  :ret (s/nilable (s/map-of keyword? any?))
  :fn (fn [{{:keys [m]} :args ret :ret}]
        (and (<= (count ret) (count m))
             (every? #(= % (csk/->kebab-case-keyword %)) (keys ret)))))

(defn parse-timestamp
  "Parse ISO timestamp string to Instant."
  [timestamp-str]
  (try
    (Instant/parse timestamp-str)
    (catch Exception e
      (log/warn "Failed to parse timestamp:" timestamp-str)
      nil)))

(s/fdef parse-timestamp
  :args (s/cat :timestamp-str (s/nilable ::specs/iso-timestamp))
  :ret (s/nilable ::specs/instant))

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

(s/fdef infer-message-type
  :args (s/cat :msg ::specs/raw-message)
  :ret ::specs/message-type
  :fn (fn [{{:keys [msg]} :args ret :ret}]
        (= (= :tool-usage ret) (boolean (:tool-name msg)))))

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

(s/fdef validate-message
  :args (s/cat :msg ::specs/raw-message)
  :ret ::specs/validated-message
  ;; validation only adds keys, and an unknown type is never valid
  :fn (fn [{{:keys [msg]} :args ret :ret}]
        (and (= (dissoc msg :message-type :valid? :errors)
                (dissoc ret :message-type :valid? :errors))
             (or (not (:valid? ret)) (not= :unknown (:message-type ret))))))

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

(s/fdef parse-jsonl-line
  :args (s/cat :line (s/nilable ::specs/jsonl-line) :line-number pos-int?)
  :ret (s/nilable ::specs/parsed-line)
  :fn (fn [{{:keys [line line-number]} :args ret :ret}]
        (if (str/blank? line)
          (nil? ret)
          (= line-number (:line-number ret)))))

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

(s/fdef parse-jsonl-file
  :args (s/cat :file-path ::specs/path)
  :ret (s/coll-of ::specs/parsed-line))

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

(s/fdef parse-jsonl-stream
  :args (s/cat :input-stream some? :callback-fn ifn?)
  :ret nil?)

(defn group-by-message-type
  "Group parsed messages by their type."
  [messages]
  (group-by :message-type (filter :valid? messages)))

(s/fdef group-by-message-type
  :args ::specs/messages-args
  :ret (s/map-of (s/nilable ::specs/message-type) (s/coll-of ::specs/message :kind vector?))
  :fn specs/groups-valid-messages?)

(defn group-by-session
  "Group messages by session ID."
  [messages]
  (group-by :session-id (filter :valid? messages)))

(s/fdef group-by-session
  :args ::specs/messages-args
  :ret (s/map-of (s/nilable string?) (s/coll-of ::specs/message :kind vector?))
  :fn specs/groups-valid-messages?)

(defn group-by-conversation
  "Group messages by conversation ID."
  [messages]
  (group-by :conversation-id (filter :valid? messages)))

(s/fdef group-by-conversation
  :args ::specs/messages-args
  :ret (s/map-of (s/nilable string?) (s/coll-of ::specs/message :kind vector?))
  :fn specs/groups-valid-messages?)

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
               :first-used (first (sort (map :timestamp usages)))
               :last-used (last (sort (map :timestamp usages)))}))))

(s/fdef extract-tool-usage
  :args ::specs/messages-args
  :ret (s/coll-of ::specs/tool-usage)
  :fn (specs/counts-tool-messages? :usage-count))

(defn calculate-token-stats
  "Calculate token usage statistics."
  [messages]
  (let [valid-messages (filter :valid? messages)
        token-counts (keep :token-count valid-messages)]
    {:total-tokens (apply + token-counts)
     :average-tokens (if (seq token-counts)
                       (double (/ (apply + token-counts) (count token-counts)))
                       0.0)
     :max-tokens (if (seq token-counts) (apply max token-counts) 0)
     :min-tokens (if (seq token-counts) (apply min token-counts) 0)
     :message-count (count valid-messages)}))

(s/fdef calculate-token-stats
  :args ::specs/messages-args
  :ret ::specs/token-stats
  :fn (fn [{{:keys [messages]} :args ret :ret}]
        (and (= (:message-count ret) (count (filter :valid? messages)))
             (<= (:min-tokens ret) (:average-tokens ret) (:max-tokens ret)))))

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

(s/fdef calculate-cost-stats
  :args ::specs/messages-args
  :ret ::specs/cost-stats)

(comment
  ;; Example usage
  (def sample-messages (parse-jsonl-file "test-data/claude-logs.jsonl"))
  (group-by-message-type sample-messages)
  (extract-tool-usage sample-messages)
  (calculate-token-stats sample-messages))
