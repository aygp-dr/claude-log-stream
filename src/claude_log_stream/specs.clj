(ns claude-log-stream.specs
  "Data specs for claude-log-stream (https://clojure.org/guides/spec).

  The JSONL message schemas (::parser/user-message and friends) stay in
  claude-log-stream.parser, where validate-message uses them. This ns builds
  on them: the parsed message maps the analyzers consume, the analysis
  result, the CLI shapes, and generators for realistic sessions and JSONL
  lines. Function specs (s/fdef) live next to each defn."
  (:require [camel-snake-kebab.core :as csk]
            [claude-log-stream.analysis :as-alias analysis]
            [claude-log-stream.cli :as-alias cli]
            [claude-log-stream.conversations :as-alias conversations]
            [claude-log-stream.cost-insights :as-alias cost-insights]
            [claude-log-stream.cost-stats :as-alias cost-stats]
            [claude-log-stream.flow :as-alias flow]
            [claude-log-stream.message :as-alias message]
            [claude-log-stream.model-cost :as-alias model-cost]
            [claude-log-stream.processor :as-alias processor]
            [claude-log-stream.productivity :as-alias productivity]
            [claude-log-stream.session-cost :as-alias session-cost]
            [claude-log-stream.session-report :as-alias session-report]
            [claude-log-stream.summary :as-alias summary]
            [claude-log-stream.temporal :as-alias temporal]
            [claude-log-stream.token-stats :as-alias token-stats]
            [claude-log-stream.tool-effect :as-alias tool-effect]
            [claude-log-stream.tool-usage :as-alias tool-usage]
            [claude-log-stream.tools :as-alias tools]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen])
  (:import [java.time Duration Instant]))

;; --- Generators ---
;; Built by fns, not held in vars: building one loads test.check, which is
;; only on the :dev/:test classpath.

(defn- gen-instant []
  ;; one week from 2024-01-15T00:00:00Z, like the sample log
  (gen/fmap #(Instant/ofEpochSecond %) (gen/large-integer* {:min 1705276800 :max 1705881600})))

(s/def ::instant (s/with-gen #(instance? Instant %) gen-instant))

(s/def ::duration
  (s/with-gen #(instance? Duration %)
    #(gen/fmap (fn [secs] (Duration/ofSeconds secs)) (gen/large-integer* {:min 0 :max 86400}))))

;; An ISO-8601 timestamp as it appears in the JSONL, or something malformed.
(s/def ::iso-timestamp
  (s/with-gen string?
    #(gen/frequency [[8 (gen/fmap str (gen-instant))]
                     [1 (gen/elements ["" "yesterday" "2024-01-15" "2024-13-45T10:30:00Z"])]])))

;; --- Messages as the analyzers see them: kebab-case keys, parsed values ---

(s/def ::message/timestamp
  (s/with-gen (s/nilable (s/nonconforming (s/or :instant ::instant :iso-string string?)))
    gen-instant))
(s/def ::message/session-id (s/with-gen string? #(gen/elements ["session-1" "session-2" "session-3"])))
(s/def ::message/message-id (s/with-gen string? #(gen/fmap (fn [n] (str "msg-" n)) (gen/choose 1 9999))))
(s/def ::message/conversation-id (s/with-gen string? #(gen/elements ["conv-1" "conv-2" "conv-3"])))
(s/def ::message/content
  (s/with-gen string? #(gen/elements ["Help me analyze this sales data"
                                      "I'll help you analyze the data." "Done." ""])))
(s/def ::message/role #{"user" "assistant" "system"})
(s/def ::message/tool-name (s/with-gen string? #(gen/elements ["Read" "Write" "Edit" "Bash" "Grep"])))
(s/def ::message/tool-input
  (s/with-gen map?
    #(gen/map (gen/elements [:file-path :command :pattern]) (gen/string-alphanumeric)
              {:max-elements 2})))
;; any JSON value a tool returned
(s/def ::message/tool-output
  (s/with-gen any? #(gen/one-of [(gen/return nil) (gen/string-alphanumeric)])))
(s/def ::message/token-count (s/with-gen pos-int? #(gen/choose 1 5000)))
(s/def ::message/model
  (s/with-gen string? #(gen/elements ["claude-3-opus" "claude-3-sonnet" "claude-3-haiku"])))
(s/def ::message/cost-usd
  (s/with-gen number? #(gen/double* {:min 0.0 :max 0.5 :infinite? false :NaN? false})))
(s/def ::message/message-type
  #{:user-message :assistant-message :system-message :tool-usage :summary-message :unknown})
(s/def ::message/valid? boolean?)
(s/def ::message/line-number pos-int?)
(s/def ::message/error (s/nilable string?))
(s/def ::message/raw-line (s/nilable string?))

(s/def ::message-type ::message/message-type)

(defn- gen-typed-message
  "[type message] with the fields parser's schema for that type requires."
  []
  (gen/bind
   (gen/elements [:user-message :assistant-message :system-message :tool-usage])
   (fn [t]
     (gen/fmap
      (fn [[base extra]] [t (merge base extra)])
      (gen/tuple
       (gen/hash-map :timestamp (gen-instant)
                     :session-id (s/gen ::message/session-id)
                     :message-id (s/gen ::message/message-id)
                     :conversation-id (s/gen ::message/conversation-id))
       (case t
         :user-message (gen/hash-map :role (gen/return "user")
                                     :content (s/gen ::message/content)
                                     :token-count (s/gen ::message/token-count))
         :system-message (gen/hash-map :role (gen/return "system")
                                       :content (s/gen ::message/content))
         :assistant-message (gen/hash-map :role (gen/return "assistant")
                                          :content (s/gen ::message/content)
                                          :model (s/gen ::message/model)
                                          :token-count (s/gen ::message/token-count)
                                          :cost-usd (s/gen ::message/cost-usd))
         :tool-usage (gen/hash-map :tool-name (s/gen ::message/tool-name)
                                   :tool-input (s/gen ::message/tool-input)
                                   :tool-output (s/gen ::message/tool-output)
                                   :token-count (s/gen ::message/token-count))))))))

(defn- gen-raw-message [] (gen/fmap second (gen-typed-message)))

(defn- gen-message []
  (gen/fmap (fn [[[t m] valid?]] (assoc m :message-type t :valid? valid?))
            (gen/tuple (gen-typed-message)
                       (gen/frequency [[9 (gen/return true)] [1 (gen/return false)]]))))

;; What validate-message and infer-message-type accept: any map read from a
;; JSONL line (kebab-cased). Generated ones are realistic.
(s/def ::raw-message (s/with-gen map? gen-raw-message))

;; A parsed (and validated) message, as the analyzers consume it.
(s/def ::message
  (s/with-gen
    (s/keys :opt-un [::message/timestamp ::message/session-id ::message/message-id
                     ::message/conversation-id ::message/content ::message/role
                     ::message/tool-name ::message/tool-input ::message/tool-output
                     ::message/token-count ::message/model ::message/cost-usd
                     ::message/message-type ::message/valid? ::message/line-number])
    gen-message))

(s/def ::messages (s/coll-of ::message :kind sequential? :gen-max 12))

(s/def ::validated-message
  (s/and map? (s/keys :req-un [::message/message-type ::message/valid?])))

;; What parse-jsonl-line returns for a non-blank line: the message, or a
;; failure map with the error and raw line.
(s/def ::parsed-line
  (s/keys :req-un [::message/line-number ::message/valid?]
          :opt-un [::message/message-type ::message/error ::message/raw-line]))

;; A JSON object as clojure.data.json reads it (:key-fn keyword).
(s/def ::json-object
  (s/with-gen (s/map-of (s/or :keyword keyword? :string string?) any?)
    #(gen/map (gen/elements [:sessionId :messageId :conversationId :toolName :tokenCount
                             :costUsd :role :timestamp :session-id])
              (gen/string-alphanumeric)
              {:max-elements 5})))

(defn ->jsonl
  "Render a message map as a Claude Code JSONL line (camelCase keys, ISO timestamps)."
  [m]
  (json/write-str
   (into {} (map (fn [[k v]] [(csk/->camelCaseString k) (if (instance? Instant v) (str v) v)])) m)))

(s/def ::jsonl-line
  (s/with-gen string?
    #(gen/frequency [[8 (gen/fmap ->jsonl (gen-raw-message))]
                     [1 (gen/elements ["" "   " "{ invalid json" "null" "[1,2]" "42"])]])))

(s/def ::path (s/or :string string? :file #(instance? java.io.File %)))

;; --- Parser summaries ---

(s/def ::groups (s/map-of any? (s/coll-of ::message :kind vector?)))

(s/def ::tool-usage/tool-name (s/nilable string?))
(s/def ::tool-usage/usage-count pos-int?)
(s/def ::tool-usage/sessions (s/coll-of (s/nilable string?)))
(s/def ::tool-usage/first-used ::message/timestamp)
(s/def ::tool-usage/last-used ::message/timestamp)
(s/def ::tool-usage
  (s/keys :req-un [::tool-usage/tool-name ::tool-usage/usage-count ::tool-usage/sessions
                   ::tool-usage/first-used ::tool-usage/last-used]))

(s/def ::token-stats/total-tokens nat-int?)
(s/def ::token-stats/average-tokens double?)
(s/def ::token-stats/max-tokens nat-int?)
(s/def ::token-stats/min-tokens nat-int?)
(s/def ::token-stats/message-count nat-int?)
(s/def ::token-stats
  (s/keys :req-un [::token-stats/total-tokens ::token-stats/average-tokens
                   ::token-stats/max-tokens ::token-stats/min-tokens
                   ::token-stats/message-count]))

(s/def ::cost-stats/total-cost number?)
(s/def ::cost-stats/average-cost number?)
(s/def ::cost-stats/cost-by-session ::groups)
(s/def ::cost-stats
  (s/keys :req-un [::cost-stats/total-cost ::cost-stats/average-cost ::cost-stats/cost-by-session]))

;; --- Analyzer results ---

(s/def ::flow/role-transitions (s/map-of vector? pos-int?))
(s/def ::flow/tool-usage-patterns (s/map-of (s/nilable string?) pos-int?))
(s/def ::flow/message-count nat-int?)
(s/def ::flow/unique-tools nat-int?)
(s/def ::flow
  (s/keys :req-un [::flow/role-transitions ::flow/tool-usage-patterns ::flow/message-count
                   ::flow/unique-tools]))

(s/def ::productivity/duration-minutes (s/nilable int?))
(s/def ::productivity/tools-per-minute (s/nilable double?))
(s/def ::productivity/responses-per-minute (s/nilable double?))
(s/def ::productivity/interaction-ratio (s/nilable double?))
(s/def ::productivity/tool-usage-count nat-int?)
(s/def ::productivity/total-interactions nat-int?)
(s/def ::productivity
  (s/keys :req-un [::productivity/duration-minutes ::productivity/tools-per-minute
                   ::productivity/responses-per-minute ::productivity/interaction-ratio
                   ::productivity/tool-usage-count ::productivity/total-interactions]))

(s/def ::tool-effect/tool-name (s/nilable string?))
(s/def ::tool-effect/total-usage pos-int?)
(s/def ::tool-effect/unique-sessions pos-int?)
(s/def ::tool-effect/success-rate (s/double-in :min 0.0 :max 1.0 :NaN? false))
(s/def ::tool-effect/average-per-session double?)
(s/def ::tool-effect/first-used ::message/timestamp)
(s/def ::tool-effect/last-used ::message/timestamp)
(s/def ::tool-effectiveness
  (s/keys :req-un [::tool-effect/tool-name ::tool-effect/total-usage ::tool-effect/unique-sessions
                   ::tool-effect/success-rate ::tool-effect/average-per-session
                   ::tool-effect/first-used ::tool-effect/last-used]))

(s/def ::model-cost/model (s/nilable string?))
(s/def ::model-cost/cost number?)
(s/def ::model-cost/message-count pos-int?)
(s/def ::model-cost/average-cost-per-message number?)
(s/def ::model-cost
  (s/keys :req-un [::model-cost/model ::model-cost/cost ::model-cost/message-count
                   ::model-cost/average-cost-per-message]))
(s/def ::recommendation (s/keys :req-un [::model-cost/model ::model-cost/average-cost-per-message]))

(s/def ::session-cost/session-id (s/nilable string?))
(s/def ::session-cost/cost number?)
(s/def ::session-cost/message-count pos-int?)
(s/def ::session-cost
  (s/keys :req-un [::session-cost/session-id ::session-cost/cost ::session-cost/message-count]))

(s/def ::cost-insights/total-cost number?)
(s/def ::cost-insights/cost-by-model (s/coll-of ::model-cost))
(s/def ::cost-insights/expensive-sessions (s/coll-of ::session-cost))
(s/def ::cost-insights/recommendations (s/coll-of ::recommendation))
(s/def ::cost-insights
  (s/keys :req-un [::cost-insights/total-cost ::cost-insights/cost-by-model
                   ::cost-insights/expensive-sessions ::cost-insights/recommendations]))

(s/def ::processor/input some?)
(s/def ::processor/output some?)
(s/def ::processor (s/keys :req-un [::processor/input ::processor/output]))

(s/def ::summary/total-messages nat-int?)
(s/def ::summary/valid-messages nat-int?)
(s/def ::summary/invalid-messages nat-int?)
(s/def ::summary/unique-sessions nat-int?)
(s/def ::summary/unique-conversations nat-int?)
(s/def ::summary/message-types (s/map-of (s/nilable ::message-type) pos-int?))
(s/def ::analysis/summary
  (s/keys :req-un [::summary/total-messages ::summary/valid-messages ::summary/invalid-messages
                   ::summary/unique-sessions ::summary/unique-conversations
                   ::summary/message-types]))

(s/def ::session-report/session-id (s/nilable string?))
(s/def ::session-report/message-count pos-int?)
(s/def ::session-report/duration (s/nilable ::duration))
(s/def ::session-report/productivity ::productivity)
(s/def ::session-report/conversation-flow ::flow)
(s/def ::session-report
  (s/keys :req-un [::session-report/session-id ::session-report/message-count
                   ::session-report/duration ::session-report/productivity
                   ::session-report/conversation-flow]))
(s/def ::analysis/sessions (s/coll-of ::session-report))

(s/def ::clusters (s/map-of nat-int? (s/coll-of map? :kind vector?)))
(s/def ::conversations/clusters ::clusters)
(s/def ::conversations/flow-patterns (s/coll-of (s/tuple (s/nilable string?) ::flow)))
(s/def ::analysis/conversations
  (s/keys :req-un [::conversations/clusters ::conversations/flow-patterns]))

(s/def ::tools/effectiveness (s/coll-of ::tool-effectiveness))
(s/def ::tools/usage-patterns (s/coll-of ::tool-usage))
(s/def ::analysis/tools (s/keys :req-un [::tools/effectiveness ::tools/usage-patterns]))

(s/def ::analysis/tokens ::token-stats)
(s/def ::analysis/costs ::cost-insights)

(s/def ::temporal/first-message ::message/timestamp)
(s/def ::temporal/last-message ::message/timestamp)
(s/def ::temporal/time-distribution (s/map-of string? pos-int?))
(s/def ::analysis/temporal
  (s/keys :req-un [::temporal/first-message ::temporal/last-message
                   ::temporal/time-distribution]))

(defn- gen-analysis []
  (gen/fmap (fn [ms] ((requiring-resolve 'claude-log-stream.analyzer/analyze-logs) ms))
            (s/gen ::messages)))

;; What analyzer/analyze-logs returns (and the dashboard renders).
(s/def ::analysis
  (s/with-gen
    (s/keys :req-un [::analysis/summary ::analysis/sessions ::analysis/conversations
                     ::analysis/tools ::analysis/tokens ::analysis/costs ::analysis/temporal])
    gen-analysis))

;; --- Dashboard and CLI ---

(s/def ::color #{:red :green :yellow :blue :magenta :cyan :white :bold :reset})
(s/def ::panel-lines (s/coll-of (s/nilable string?) :kind sequential?))

(s/def ::number-like
  (s/with-gen number?
    #(gen/one-of [(gen/large-integer* {:min 0 :max 5000000})
                  (gen/double* {:min 0.0 :max 5.0e6 :infinite? false :NaN? false})])))

(s/def ::cli-arg
  (s/with-gen string?
    #(gen/elements ["-f" "test/resources/sample.jsonl" "/nonexistent/file.jsonl" "-o" "out"
                    "-d" "-v" "-w" "-h" "--help" "--bogus"])))
(s/def ::cli-args (s/coll-of ::cli-arg :kind sequential? :gen-max 6))

(s/def ::cli/exit-message string?)
(s/def ::cli/ok? boolean?)
(s/def ::cli/action #{:analyze})
(s/def ::cli/options map?)
(s/def ::cli-result (s/keys :opt-un [::cli/exit-message ::cli/ok? ::cli/action ::cli/options]))

;; --- Shared fdef pieces ---

(s/def ::messages-args (s/cat :messages ::messages))

(defn groups-valid-messages?
  "s/fdef :fn for the group-by-* fns: the groups hold exactly the valid messages."
  [{{:keys [messages]} :args ret :ret}]
  (= (count (filter :valid? messages)) (reduce + (map count (vals ret)))))

(defn counts-tool-messages?
  "s/fdef :fn for tool summaries: the per-tool counts add up to the tool messages."
  [count-key]
  (fn [{{:keys [messages]} :args ret :ret}]
    (= (count (filter #(= :tool-usage (:message-type %)) messages))
       (reduce + (map count-key ret)))))
