#!/usr/bin/env bb

;; swarm_project — the project as one table: tracker state joined to live
;; pipeline state.
;;
;; Pipeline facts come from `swarm_status.sh --json` rather than being
;; recomputed here, so swarm_status stays their single owner. Tracker facts come
;; from gh. The join key is the task name, which carries its issue number as a
;; prefix (`42-add-login`), so handoff, issue, branch, and pull request line up
;; without a side file.
;;
;; The tracker half is allowed to fail. A dashboard that goes blank when gh is
;; down or rate-limited is worse than one that shows the pipeline and says so.
;;
;; Usage:
;;   swarm_project.sh                    milestone-grouped table
;;   swarm_project.sh --json             the same state as JSON
;;   swarm_project.sh --repo owner/name  override the repository

(ns swarm-project
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def issue-limit "200")
(def pr-limit "100")

(defn brief-reason
  "One readable line from a failed command's stderr.

   A babashka stack trace runs to dozens of lines, and the whole thing ends up
   rendered as the reason a column is missing. Prefer the `Message:` line it
   prints, else the first line that says anything."
  [text]
  (let [lines (remove str/blank? (str/split-lines (str text)))
        message (first (filter #(str/starts-with? (str/trim %) "Message:") lines))
        line (str/trim (or message (first lines) ""))
        line (str/replace line #"^Message:\s*" "")]
    (if (> (count line) 160) (str (subs line 0 157) "...") line)))

(defn- sh-out [& args]
  (let [{:keys [out exit]} (apply process/sh {:continue true} args)]
    (when (zero? exit) (str/trim out))))

(defn origin-repo
  "owner/name from the origin remote. gh resolves the upstream remote in a fork,
   so it is never asked to work this out."
  []
  (when-let [url (sh-out "git" "remote" "get-url" "origin")]
    (let [trimmed (str/replace (str/trim url) #"\.git$" "")]
      (second (re-find #"[:/]([^:/]+/[^:/]+)$" trimmed)))))

;; --- pipeline half -----------------------------------------------------------

(defn pipeline-state
  "swarm_status.sh --json, parsed. Its sibling location is the same convention
   swarm_pr_wait uses to find swarm_handoff.sh."
  []
  (let [script (str (fs/path (fs/parent (fs/absolutize *file*)) "swarm_status.sh"))
        {:keys [out exit err]} (process/sh {:continue true} script "--json")]
    (if (zero? exit)
      (try
        (json/parse-string out true)
        (catch Exception e {:available false :reason (str "unparseable swarm_status output: " (ex-message e))}))
      {:available false :reason (brief-reason err)})))

(defn holder
  "The role currently holding this task, or nil. `working` and `queued(n)` both
   count as held; a priority-00 notice does not, which is why swarm_status
   reports it as its own state."
  [pipeline task]
  (->> (:roles pipeline)
       (filter (fn [{:keys [state] :as r}]
                 (and (= task (:task r))
                      (or (= "working" state)
                          (str/starts-with? (or state "") "queued")))))
       first
       :role))

;; --- tracker half ------------------------------------------------------------

(def board-query
  "query($owner:String!,$name:String!){
     repository(owner:$owner,name:$name){
       projectsV2(first:1){
         nodes{
           title
           items(first:100){
             nodes{
               content{ ... on Issue { number } }
               fieldValueByName(name:\"Status\"){
                 ... on ProjectV2ItemFieldSingleSelectValue { name }
               }
             }
           }
         }
       }
     }
   }")

(defn- gh-json
  "Run gh and parse its stdout. Returns {:ok? false :reason ...} rather than
   throwing, so one failed call degrades the tracker half instead of the run."
  [& args]
  (let [{:keys [out exit err]} (apply process/sh {:continue true} "gh" args)]
    (if (zero? exit)
      (try
        {:ok? true :value (json/parse-string out true)}
        (catch Exception e {:ok? false :reason (str "unparseable gh output: " (ex-message e))}))
      {:ok? false :reason (let [text (brief-reason err)]
                            (if (str/blank? text) (str "gh exited " exit) text))})))

(defn board-status-by-issue
  "Issue number -> board Status value. An unlinked project is not a failure:
   the column is simply absent."
  [repo]
  (let [[owner name] (str/split repo #"/" 2)
        result (gh-json "api" "graphql" "-f" (str "query=" board-query)
                        "-F" (str "owner=" owner) "-F" (str "name=" name))]
    (if-not (:ok? result)
      {}
      (->> (get-in (:value result) [:data :repository :projectsV2 :nodes])
           first
           :items
           :nodes
           (keep (fn [{:keys [content fieldValueByName]}]
                   (when-let [n (:number content)]
                     [n (:name fieldValueByName)])))
           (into {})))))

(defn tracker-state [repo]
  (let [issues (gh-json "issue" "list" "--repo" repo "--state" "all"
                        "--limit" issue-limit
                        "--json" "number,title,state,labels,milestone")]
    (if-not (:ok? issues)
      {:available false :reason (:reason issues) :repo repo}
      (let [prs (gh-json "pr" "list" "--repo" repo "--state" "all"
                         "--limit" pr-limit
                         "--json" "number,title,state,headRefName")]
        (if-not (:ok? prs)
          {:available false :reason (:reason prs) :repo repo}
          {:available true
           :repo repo
           :issues (:value issues)
           :pull_requests (:value prs)
           :board (board-status-by-issue repo)})))))

;; --- join --------------------------------------------------------------------

(defn issue-number
  "42 from `42-add-login`; nil when the task name is not issue-prefixed."
  [task-name]
  (some-> (re-find #"^(\d+)-" (or task-name "")) second parse-long))

(defn- slice-for [pipeline number]
  (->> (concat (keep :task (:roles pipeline)) (:in_flight pipeline))
       (filter #(= number (issue-number %)))
       first))

(defn- pr-for [tracker slice]
  (when slice
    (first (filter #(= (str "slice/" slice) (:headRefName %))
                   (:pull_requests tracker)))))

(defn- stage-for [pipeline issue slice pr]
  (cond
    (and slice (holder pipeline slice)) (holder pipeline slice)
    (and pr (= "OPEN" (:state pr)))     "review"
    (= "CLOSED" (:state issue))         "done"
    slice                               "handed off"
    :else                               "backlog"))

(defn rows [pipeline tracker]
  (for [issue (sort-by :number (:issues tracker))
        :let [number (:number issue)
              slice (slice-for pipeline number)
              pr (pr-for tracker slice)]]
    {:milestone (get-in issue [:milestone :title])
     :issue number
     :title (:title issue)
     :slice slice
     :stage (stage-for pipeline issue slice pr)
     :pr (:number pr)
     :pr_state (:state pr)
     :board (get (:board tracker) number)}))

(defn summarize
  "In-flight is the pipeline's own count, not a count of rows: work can be in
   the pipeline before its issue exists, and an issue can sit on the board with
   no slice at all."
  [pipeline rows]
  {:in_flight (count (or (:in_flight pipeline) []))
   :awaiting_merge (count (filter #(= "review" (:stage %)) rows))
   :backlog (count (filter #(= "backlog" (:stage %)) rows))})

(defn state [repo]
  (let [pipeline (pipeline-state)
        tracker (tracker-state repo)
        row-seq (if (:available tracker) (vec (rows pipeline tracker)) [])]
    {:repo repo
     :pipeline pipeline
     :tracker tracker
     :rows row-seq
     :summary (summarize pipeline row-seq)}))

;; --- output ------------------------------------------------------------------

(defn- dash [v] (if (or (nil? v) (str/blank? (str v))) "—" (str v)))

(defn print-table [{:keys [rows tracker pipeline summary]}]
  (if-not (:available tracker)
    (do
      (println (str "TRACKER UNAVAILABLE: " (:reason tracker)))
      (println)
      (println "Pipeline only:")
      (doseq [r (:roles pipeline)]
        (println (format "%-12s %-12s %s" (:role r) (:state r) (dash (:task r))))))
    (do
      (println (format "%-10s %-6s %-24s %-14s %-12s %s"
                       "MILESTONE" "ISSUE" "SLICE" "STAGE" "BOARD" "PR"))
      (doseq [group (partition-by :milestone (sort-by (juxt #(or (:milestone %) "~") :issue) rows))]
        (doseq [r group]
          (println (format "%-10s %-6s %-24s %-14s %-12s %s"
                           (dash (:milestone r))
                           (str "#" (:issue r))
                           (dash (:slice r))
                           (:stage r)
                           (dash (:board r))
                           (if (:pr r)
                             (str "#" (:pr r) " " (str/lower-case (str (:pr_state r))))
                             "—")))))
      (println)
      ;; "0 in flight" and "could not tell" are different answers, and reading
      ;; the first when the second is true is how a stalled swarm looks idle.
      (if (false? (:available pipeline))
        (println (format "pipeline unavailable (%s) · %d awaiting merge · %d backlog"
                         (:reason pipeline) (:awaiting_merge summary) (:backlog summary)))
        (println (format "%d in flight · %d awaiting merge · %d backlog"
                         (:in_flight summary) (:awaiting_merge summary) (:backlog summary)))))))

(defn -main [& args]
  (let [args (vec args)
        repo-idx (.indexOf args "--repo")
        repo (or (when (>= repo-idx 0) (get args (inc repo-idx))) (origin-repo))]
    (when (str/blank? repo)
      (binding [*out* *err*] (println "Cannot determine the repository; pass --repo owner/name."))
      (System/exit 1))
    (let [result (state repo)]
      (if (some #{"--json"} args)
        (println (json/generate-string result {:pretty true}))
        (print-table result)))))

(apply -main *command-line-args*)
