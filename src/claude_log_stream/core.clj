(ns claude-log-stream.core
  "Core entry point for Claude Log Stream analytics engine."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [clojure.tools.logging :as log]
            [claude-log-stream.parser :as parser]
            [claude-log-stream.analyzer :as analyzer]
            [claude-log-stream.dashboard :as dashboard]
            [claude-log-stream.specs :as specs])
  (:gen-class))

(def cli-options
  [["-f" "--file FILE" "Input JSONL log file path"
    :required "Input file is required"]
   ["-o" "--output DIR" "Output directory for analysis results"
    :default "./output"]
   ["-w" "--watch" "Watch file for real-time processing"]
   ["-d" "--dashboard" "Launch interactive dashboard"]
   ["-v" "--verbose" "Verbose logging"]
   ["-h" "--help" "Show help"]])

(defn usage [options-summary]
  (->> ["Claude Log Stream - Advanced analytics for Claude Code logs"
        ""
        "Usage: claude-log-stream [options]"
        ""
        "Options:"
        options-summary
        ""
        "Examples:"
        "  claude-log-stream -f logs/claude.jsonl -d"
        "  claude-log-stream -f logs/claude.jsonl -w -v"]
       (clojure.string/join \newline)))

(s/fdef usage
  :args (s/cat :options-summary string?)
  :ret string?
  :fn (fn [{{:keys [options-summary]} :args ret :ret}]
        (str/includes? ret options-summary)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (clojure.string/join \newline errors)))

(s/fdef error-msg
  :args (s/cat :errors (s/coll-of string?))
  :ret string?
  :fn (fn [{{:keys [errors]} :args ret :ret}]
        (every? #(str/includes? ret %) errors)))

(defn validate-args [args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)]
    (cond
      (:help options)
      {:exit-message (usage summary) :ok? true}

      errors
      {:exit-message (error-msg errors)}

      (and (:file options) (.exists (java.io.File. (:file options))))
      {:action :analyze :options options}

      :else
      {:exit-message (usage summary)})))

(s/fdef validate-args
  :args (s/cat :args ::specs/cli-args)
  :ret ::specs/cli-result
  ;; either an action to run or a message to exit with, never both
  :fn (fn [{ret :ret}]
        (not= (nil? (:action ret)) (nil? (:exit-message ret)))))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(s/fdef exit
  :args (s/cat :status int? :msg any?)
  :ret any?)

(defn -main [& args]
  (let [{:keys [action options exit-message ok?]} (validate-args args)]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (do
        (when (:verbose options)
          (log/info "Starting Claude Log Stream with options:" options))

        (case action
          :analyze
          (do
            (log/info "Processing log file:" (:file options))
            (let [parsed-data (parser/parse-jsonl-file (:file options))
                  analysis (analyzer/analyze-logs parsed-data)]
              (if (:dashboard options)
                (dashboard/launch analysis)
                (analyzer/print-summary analysis)))))))))

(s/fdef -main
  :args (s/* string?))
