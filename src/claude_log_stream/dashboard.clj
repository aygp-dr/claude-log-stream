(ns claude-log-stream.dashboard
  "Interactive terminal dashboard for real-time Claude log analytics."
  (:require [clojure.core.async :as async :refer [go go-loop <! >! chan timeout]]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [claude-log-stream.analyzer :as analyzer]
            [claude-log-stream.specs :as specs])
  (:import [java.time Instant Duration]
           [java.time.format DateTimeFormatter]))

(def ^:dynamic *dashboard-width* 80)
(def ^:dynamic *dashboard-height* 24)

(defn clear-screen []
  (print "\033[2J\033[H")
  (flush))

(s/fdef clear-screen
  :args (s/cat)
  :ret nil?)

(defn move-cursor [row col]
  (printf "\033[%d;%dH" row col)
  (flush))

(s/fdef move-cursor
  :args (s/cat :row nat-int? :col nat-int?)
  :ret nil?)

(defn colorize [text color]
  (let [colors {:red "\033[31m"
                :green "\033[32m"
                :yellow "\033[33m"
                :blue "\033[34m"
                :magenta "\033[35m"
                :cyan "\033[36m"
                :white "\033[37m"
                :bold "\033[1m"
                :reset "\033[0m"}]
    (str (get colors color "") text (get colors :reset ""))))

(s/fdef colorize
  :args (s/cat :text (s/nilable string?) :color ::specs/color)
  :ret string?
  :fn (fn [{{:keys [text]} :args ret :ret}]
        (str/includes? ret (str text))))

(defn draw-box [title content width height]
  (let [title-len (count title)
        border-top (str "┌" (str/join (repeat (- width 2) "─")) "┐")
        border-bottom (str "└" (str/join (repeat (- width 2) "─")) "┘")
        title-line (str "│ " (colorize title :bold)
                        (str/join (repeat (- width 4 title-len) " ")) " │")]
    (println border-top)
    (println title-line)
    (println (str "├" (str/join (repeat (- width 2) "─")) "┤"))
    (doseq [line (take (- height 4) content)]
      (let [padded-line (str line (str/join (repeat (max 0 (- width 3 (count line))) " ")))]
        (println (str "│ " (if (> (count padded-line) (- width 3))
                             (str (subs padded-line 0 (- width 6)) "...")
                             padded-line) " │"))))
    (println border-bottom)))

(s/fdef draw-box
  :args (s/cat :title string? :content ::specs/panel-lines :width pos-int? :height nat-int?)
  :ret nil?)

(defn format-duration [duration]
  (when duration
    (let [minutes (.toMinutes duration)
          seconds (mod (.getSeconds duration) 60)]
      (format "%dm %ds" minutes seconds))))

(s/fdef format-duration
  :args (s/cat :duration (s/nilable ::specs/duration))
  :ret (s/nilable string?)
  :fn (fn [{{:keys [duration]} :args ret :ret}]
        (= (nil? duration) (nil? ret))))

(defn format-timestamp [instant]
  (when instant
    (.format (DateTimeFormatter/ofPattern "HH:mm:ss")
             (.atZone instant (java.time.ZoneId/systemDefault)))))

(s/fdef format-timestamp
  :args (s/cat :instant (s/nilable ::specs/instant))
  :ret (s/nilable string?)
  :fn (fn [{{:keys [instant]} :args ret :ret}]
        (= (nil? instant) (nil? ret))))

(defn format-number [n]
  (cond
    (nil? n) "N/A"
    (< n 1000) (str n)
    (< n 1000000) (format "%.1fK" (/ n 1000.0))
    :else (format "%.1fM" (/ n 1000000.0))))

(s/fdef format-number
  :args (s/cat :n (s/nilable ::specs/number-like))
  :ret string?)

(defn render-summary-panel [analysis]
  (let [summary (:summary analysis)
        tokens (:tokens analysis)
        costs (:costs analysis)]
    [(colorize "SUMMARY" :cyan)
     (format "Messages: %s (%.1f%% valid)"
             (format-number (:total-messages summary))
             (* 100.0 (/ (:valid-messages summary) (:total-messages summary))))
     (format "Sessions: %s | Conversations: %s"
             (format-number (:unique-sessions summary))
             (format-number (:unique-conversations summary)))
     (format "Tokens: %s (avg: %.0f)"
             (format-number (:total-tokens tokens))
             (:average-tokens tokens))
     (when (> (:total-cost costs) 0)
       (format "Cost: $%.2f" (:total-cost costs)))]))

(s/fdef render-summary-panel
  :args (s/cat :analysis ::specs/analysis)
  :ret ::specs/panel-lines)

(defn render-tools-panel [analysis]
  (let [tools (take 8 (sort-by :total-usage > (get-in analysis [:tools :effectiveness])))]
    (cons (colorize "TOP TOOLS" :green)
          (map (fn [{:keys [tool-name total-usage success-rate]}]
                 (format "%-20s %4d uses (%.0f%%)"
                         (if (> (count tool-name) 18)
                           (str (subs tool-name 0 15) "...")
                           tool-name)
                         total-usage
                         (* 100.0 success-rate)))
               tools))))

(s/fdef render-tools-panel
  :args (s/cat :analysis ::specs/analysis)
  :ret ::specs/panel-lines)

(defn render-sessions-panel [analysis]
  (let [sessions (take 8 (sort-by :message-count > (:sessions analysis)))]
    (cons (colorize "ACTIVE SESSIONS" :yellow)
          (map (fn [{:keys [session-id message-count duration]}]
                 (format "%-12s %3d msgs %s"
                         (subs session-id 0 (min 12 (count session-id)))
                         message-count
                         (format-duration duration)))
               sessions))))

(s/fdef render-sessions-panel
  :args (s/cat :analysis ::specs/analysis)
  :ret ::specs/panel-lines)

(defn render-real-time-stats [analysis last-update]
  [(colorize "REAL-TIME" :magenta)
   (format "Last Update: %s" (format-timestamp last-update))
   (format "Processing Rate: %s msgs/min"
           (format-number (get-in analysis [:summary :processing-rate] 0)))
   ""
   (colorize "RECENT ACTIVITY" :cyan)
   "• Session started"
   "• Tool usage spike"
   "• Cost threshold alert"])

(s/fdef render-real-time-stats
  :args (s/cat :analysis ::specs/analysis :last-update (s/nilable ::specs/instant))
  :ret ::specs/panel-lines)

(defn render-dashboard [analysis]
  (clear-screen)
  (move-cursor 1 1)

  (println (colorize "Claude Log Stream - Live Dashboard" :bold))
  (println (str "Updated: " (format-timestamp (Instant/now))))
  (println (str/join (repeat *dashboard-width* "═")))
  (println)

  ;; Top row - Summary and Tools
  (let [panel-width (quot *dashboard-width* 2)]
    (println (colorize "Summary" :bold) (str/join (repeat (- panel-width 20) " "))
             (colorize "Top Tools" :bold))

    (let [summary-lines (render-summary-panel analysis)
          tools-lines (render-tools-panel analysis)
          max-lines (max (count summary-lines) (count tools-lines))]
      (dotimes [i max-lines]
        (let [summary-line (get summary-lines i "")
              tools-line (get tools-lines i "")
              summary-padded (str summary-line
                                  (str/join (repeat (max 0 (- panel-width (count summary-line) 2)) " ")))]
          (println summary-padded " │ " tools-line))))

    (println)
    (println (str/join (repeat *dashboard-width* "─")))
    (println)

    ;; Bottom row - Sessions and Real-time
    (println (colorize "Active Sessions" :bold) (str/join (repeat (- panel-width 25) " "))
             (colorize "Real-time Stats" :bold))

    (let [sessions-lines (render-sessions-panel analysis)
          realtime-lines (render-real-time-stats analysis (Instant/now))
          max-lines (max (count sessions-lines) (count realtime-lines))]
      (dotimes [i max-lines]
        (let [sessions-line (get sessions-lines i "")
              realtime-line (get realtime-lines i "")
              sessions-padded (str sessions-line
                                   (str/join (repeat (max 0 (- panel-width (count sessions-line) 2)) " ")))]
          (println sessions-padded " │ " realtime-line)))))

  (println)
  (println (str/join (repeat *dashboard-width* "═")))
  (println (colorize "Press 'q' to quit, 'r' to refresh" :cyan)))

(s/fdef render-dashboard
  :args (s/cat :analysis ::specs/analysis)
  :ret nil?)

(defn handle-input [input-chan stop-chan]
  (go-loop []
    (let [input (read-line)]
      (condp = input
        "q" (>! stop-chan :quit)
        "r" (>! input-chan :refresh)
        "h" (>! input-chan :help)
        (recur)))))

(s/fdef handle-input
  :args (s/cat :input-chan some? :stop-chan some?)
  :ret some?)

(defn launch [analysis]
  (log/info "Launching interactive dashboard")
  (let [refresh-chan (chan)
        input-chan (chan)
        stop-chan (chan)]

    ;; Initial render
    (render-dashboard analysis)

    ;; Input handler
    (handle-input input-chan stop-chan)

    ;; Auto-refresh every 5 seconds
    (go-loop []
      (async/alt!
        (timeout 5000) (do (>! refresh-chan :auto-refresh)
                           (recur))
        stop-chan :quit))

    ;; Main loop
    (go-loop [current-analysis analysis]
      (async/alt!
        refresh-chan ([_]
                      (render-dashboard current-analysis)
                      (recur current-analysis))

        input-chan ([cmd]
                    (case cmd
                      :refresh (do (render-dashboard current-analysis)
                                   (recur current-analysis))
                      :help (do (println "\nHelp: q=quit, r=refresh, h=help")
                                (Thread/sleep 2000)
                                (render-dashboard current-analysis)
                                (recur current-analysis))
                      (recur current-analysis)))

        stop-chan :quit))

    (println "\nDashboard closed.")))

(s/fdef launch
  :args (s/cat :analysis ::specs/analysis)
  :ret nil?)
