(ns claude-log-stream.core
  "Core entry point for Claude Log Stream analytics engine."
  (:require [clojure.tools.cli :as cli]
            [clojure.tools.logging :as log]
            [claude-log-stream.parser :as parser]
            [claude-log-stream.analyzer :as analyzer]
            [claude-log-stream.dashboard :as dashboard])
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

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (clojure.string/join \newline errors)))

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

(defn exit [status msg]
  (println msg)
  (System/exit status))

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