#!/usr/bin/env bb

;; swarm_status — read-only view of what is in the pipeline right now.
;;
;; Every fact printed here is already on disk: .swarmforge/roles.tsv names the
;; roles and their worktrees, and each worktree's inbox carries handoff files
;; whose headers record task name, priority, and the enqueued/dequeued/completed
;; timestamps. This command only assembles them.
;;
;; Usage:
;;   swarm_status.sh                 role table + in-flight summary
;;   swarm_status.sh --json          same data as JSON
;;   swarm_status.sh --task <name>   one task's route, stage by stage
;;   swarm_status.sh --quiet         print nothing; exit 1 if anything is in flight

(ns swarm-status
  (:require [babashka.fs :as fs]
            [babashka.process]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; --- project discovery -------------------------------------------------------
;; Mirrors handoff_lib.bb: works from the main checkout and from any worktree,
;; so the PM (outside the swarm) and any role (inside one) both get an answer.

(defn project-root []
  (let [cwd (fs/cwd)
        direct (fs/path cwd ".swarmforge" "roles.tsv")]
    (if (fs/exists? direct)
      cwd
      (let [git-root (:out (babashka.process/sh {:continue true} "git" "rev-parse" "--show-toplevel"))
            root (when-not (str/blank? git-root) (fs/path (str/trim git-root)))]
        (if (and root (fs/exists? (fs/path root ".swarmforge" "roles.tsv")))
          root
          (let [common (:out (babashka.process/sh {:continue true} "git" "rev-parse" "--git-common-dir"))
                common-path (when-not (str/blank? common)
                              (let [path (fs/path (str/trim common))]
                                (if (fs/absolute? path) path (fs/absolutize path))))
                common-parent (some-> common-path fs/parent)]
            (if (and common-parent (fs/exists? (fs/path common-parent ".swarmforge" "roles.tsv")))
              common-parent
              (throw (ex-info "Cannot find SwarmForge project root" {:exit 1})))))))))

(defn roles
  "Rows of .swarmforge/roles.tsv in configured order:
   role, worktree name, worktree path, session, title, agent, receive mode."
  [root]
  (->> (str/split-lines (slurp (str (fs/path root ".swarmforge" "roles.tsv"))))
       (remove str/blank?)
       (map #(str/split % #"\t" -1))
       (map (fn [cols]
              {:role (nth cols 0 "")
               :worktree (nth cols 2 "")
               :agent (nth cols 5 "")
               :mode (let [m (nth cols 6 "")] (if (str/blank? m) "task" m))}))))

;; --- handoff files -----------------------------------------------------------

(defn headers
  "Header block of a handoff file: every line up to the first blank one."
  [file]
  (->> (str/split-lines (slurp (str file)))
       (take-while (complement str/blank?))
       (keep (fn [line]
               (when-let [[_ k v] (re-matches #"^([a-z_]+):\s*(.*)$" line)]
                 [(keyword k) v])))
       (into {})))

(defn handoff-files
  "Handoff files directly in dir, plus those inside batch_* containers.
   Batch containers are directories, so anything walking an inbox needs both."
  [dir]
  (if-not (fs/exists? dir)
    []
    (let [entries (fs/list-dir dir)
          direct (filter #(and (fs/regular-file? %)
                               (str/ends-with? (fs/file-name %) ".handoff"))
                         entries)
          batched (->> entries
                       (filter #(and (fs/directory? %)
                                     (str/starts-with? (fs/file-name %) "batch_")))
                       (mapcat #(filter (fn [f] (str/ends-with? (fs/file-name f) ".handoff"))
                                        (fs/list-dir %))))]
      (sort-by #(fs/file-name %) (concat direct batched)))))

(defn inbox [worktree kind]
  (fs/path worktree ".swarmforge" "handoffs" "inbox" kind))

(defn instant [s]
  (when-not (str/blank? s)
    (try (java.time.Instant/parse s) (catch Exception _ nil))))

(defn minutes-since [ts]
  (when ts
    (/ (.toSeconds (java.time.Duration/between ts (java.time.Instant/now))) 60.0)))

(defn duration-str [mins]
  (cond
    (nil? mins) "-"
    (< mins 60) (format "%d:%02d" 0 (int mins))
    (< mins 1440) (format "%d:%02d" (int (/ mins 60)) (int (mod mins 60)))
    :else (format "%.1fd" (/ mins 1440.0))))

;; A priority-00 handoff is QA's completion broadcast: delivered to everyone,
;; actionable by one. Counting it as pipeline occupancy would report the swarm
;; busy every time a task finishes.
(defn broadcast? [h] (= "00" (:priority h)))

(defn role-state
  "What this role is doing, from its own inbox."
  [{:keys [role worktree mode]}]
  (let [claimed (map (fn [f] (assoc (headers f) :_file (str f)))
                     (handoff-files (inbox worktree "in_process")))
        queued (map (fn [f] (assoc (headers f) :_file (str f)))
                    (handoff-files (inbox worktree "new")))
        work-queued (remove broadcast? queued)
        notices (filter broadcast? queued)
        current (first claimed)]
    (cond-> {:role role :mode mode :worktree worktree}
      current
      (assoc :state (if (broadcast? current) "ack" "working")
             :task (:task current)
             :from (:from current)
             :since-min (minutes-since (instant (:dequeued_at current))))

      (and (nil? current) (seq work-queued))
      (assoc :state (str "queued(" (count work-queued) ")")
             :task (:task (first work-queued))
             :from (:from (first work-queued))
             :since-min (minutes-since (instant (:enqueued_at (first work-queued)))))

      (and (nil? current) (empty? work-queued) (seq notices))
      (assoc :state (str "notice(" (count notices) ")")
             :task (:task (first notices)))

      (and (nil? current) (empty? queued))
      (assoc :state "idle")

      true
      (assoc :queued-work (count work-queued) :queued-notices (count notices)))))

(defn in-flight
  "Task names currently claimed or queued as real work, anywhere in the swarm."
  [states]
  (->> states
       (filter #(#{"working"} (:state %)))
       (concat (filter #(str/starts-with? (or (:state %) "") "queued") states))
       (keep :task)
       distinct
       sort))

;; --- --task: one task's route ------------------------------------------------

(defn all-deliveries
  "Every archived and live delivery of task-name across all role inboxes."
  [root role-rows task-name]
  (for [{:keys [role worktree]} role-rows
        kind ["completed" "in_process" "new"]
        f (handoff-files (inbox worktree kind))
        :let [h (headers f)]
        :when (= task-name (:task h))]
    (assoc h :recipient role :_state kind)))

(defn print-route [root role-rows task-name]
  (let [rows (->> (all-deliveries root role-rows task-name)
                  (remove broadcast?)
                  (sort-by #(or (:enqueued_at %) (:created_at %) "")))]
    (if (empty? rows)
      (println (str "No handoffs found for task: " task-name))
      (do
        (println (format "%-12s %-12s %-10s %-10s %s" "FROM" "TO" "STATE" "TOOK" "COMMIT"))
        (doseq [r rows]
          (let [d (instant (:dequeued_at r))
                c (instant (:completed_at r))
                took (when (and d c)
                       (/ (.toSeconds (java.time.Duration/between d c)) 60.0))]
            (println (format "%-12s %-12s %-10s %-10s %s"
                             (or (:from r) "?")
                             (or (:recipient r) "?")
                             (:_state r)
                             (if took (duration-str took) "-")
                             (or (:commit r) "-")))))
        (let [firsts (keep #(instant (or (:enqueued_at %) (:created_at %))) rows)
              lasts (keep #(instant (:completed_at %)) rows)]
          (when (and (seq firsts) (seq lasts))
            (println)
            (println (str "Elapsed: "
                          (duration-str (/ (.toSeconds (java.time.Duration/between
                                                        (first (sort firsts))
                                                        (last (sort lasts))))
                                           60.0))
                          " across " (count rows) " stages"))))))))

;; --- main --------------------------------------------------------------------

(defn print-table [states flight]
  (println (format "%-12s %-6s %-12s %-34s %s" "ROLE" "MODE" "STATE" "TASK" "SINCE"))
  (doseq [s states]
    (println (format "%-12s %-6s %-12s %-34s %s"
                     (:role s)
                     (:mode s)
                     (:state s)
                     (or (:task s) "-")
                     (duration-str (:since-min s)))))
  (println)
  (if (empty? flight)
    (println "IN FLIGHT: nothing")
    (println (str "IN FLIGHT: " (count flight) " task(s) — " (str/join ", " flight)))))

(defn -main [& args]
  (let [args (vec args)
        root (project-root)
        role-rows (roles root)
        task-idx (.indexOf args "--task")]
    (cond
      (>= task-idx 0)
      (let [name (get args (inc task-idx))]
        (if (str/blank? name)
          (do (binding [*out* *err*] (println "--task requires a task name")) (System/exit 2))
          (print-route root role-rows name)))

      :else
      (let [states (mapv role-state role-rows)
            flight (in-flight states)]
        (cond
          (some #{"--quiet"} args)
          (System/exit (if (seq flight) 1 0))

          (some #{"--json"} args)
          (println (json/generate-string
                    {:root (str root) :roles states :in_flight flight}
                    {:pretty true}))

          :else
          (print-table states flight))))))

(apply -main *command-line-args*)
