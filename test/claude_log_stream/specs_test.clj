(ns claude-log-stream.specs-test
  "Generative checks for every pure s/fdef'd fn, plus data-spec sanity.
  Per https://clojure.org/guides/spec (Testing)."
  (:require [claude-log-stream.analyzer :as analyzer]
            [claude-log-stream.core]
            [claude-log-stream.dashboard :as dashboard]
            [claude-log-stream.parser :as parser]
            [claude-log-stream.specs :as specs]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [deftest is testing]]))

(def ^:private check-opts {:clojure.spec.test.check/opts {:num-tests 50}})

(def ^:private api-nses
  '[claude-log-stream.parser claude-log-stream.analyzer
    claude-log-stream.dashboard claude-log-stream.core])

;; Side-effecting fns: fdef'd for instrumentation, never generatively checked.
(def ^:private side-effecting
  #{`parser/parse-jsonl-file         ; reads a file
    `parser/parse-jsonl-stream       ; reads a stream, calls back
    `analyzer/streaming-processor    ; starts a go-loop
    `analyzer/real-time-analyzer     ; starts go-loops
    `analyzer/print-summary          ; prints
    `dashboard/clear-screen          ; prints
    `dashboard/move-cursor           ; prints
    `dashboard/draw-box              ; prints
    `dashboard/render-dashboard      ; prints
    `dashboard/handle-input          ; reads stdin
    `dashboard/launch                ; interactive loop
    `claude-log-stream.core/exit     ; System/exit
    `claude-log-stream.core/-main})  ; IO, System/exit

(defn- checkable []
  (remove side-effecting (stest/enumerate-namespace api-nses)))

(deftest fdefs-hold-under-generative-testing
  (let [results (stest/check (checkable) check-opts)]
    (is (seq results) "expected at least one fdef'd fn to check")
    (doseq [r results]
      (testing (str (:sym r))
        (is (nil? (:failure r))
            (pr-str (stest/abbrev-result r)))))))

(deftest data-specs-generate-and-conform
  (doseq [k [::specs/message ::specs/messages ::specs/raw-message ::specs/jsonl-line
             ::specs/analysis ::specs/cli-args
             ::parser/user-message ::parser/assistant-message ::parser/system-message
             ::parser/tool-usage ::parser/summary-message]]
    (testing (str k)
      (is (every? (fn [[v _]] (s/valid? k v)) (s/exercise k 10))))))

(deftest generated-messages-match-the-parser-schemas
  (doseq [m (gen/sample (s/gen ::specs/raw-message) 30)]
    (is (:valid? (parser/validate-message m)) (pr-str m))))

(deftest real-values-conform
  (let [lines (parser/parse-jsonl-file "test/resources/sample.jsonl")
        valid (filter :valid? lines)]
    (testing "the sample log"
      (is (seq valid))
      (is (every? #(s/valid? ::specs/parsed-line %) lines))
      (is (s/valid? ::specs/messages valid)))
    (testing "its analysis"
      (is (s/valid? ::specs/analysis (analyzer/analyze-logs lines))))
    (testing "a JSONL line round-trips through the renderer"
      (let [m (first valid)
            line (specs/->jsonl (dissoc m :message-type :valid? :line-number))]
        (is (= (dissoc m :line-number) (dissoc (parser/parse-jsonl-line line 1) :line-number)))))))
