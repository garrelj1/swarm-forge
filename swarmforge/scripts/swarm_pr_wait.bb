#!/usr/bin/env bb

;; swarm_pr_wait — block until a pull request resolves, then report how.
;;
;; The PM cannot poll from its own turn loop without spending one agent turn per
;; check, so this helper owns the waiting. Run it detached with --notify and it
;; sends a note handoff when the pull request resolves; the daemon's wake-up
;; brings the PM back with no turns spent waiting.
;;
;; Usage:
;;   swarm_pr_wait.sh <pr-number> [--repo owner/name] [--interval seconds]
;;                    [--timeout seconds] [--notify role] [--task name]
;;
;; Exit codes: 0 merged, 2 closed unmerged, 3 timed out, 1 usage or gh failure.

(ns swarm-pr-wait
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def default-interval 60)
(def default-timeout 86400)

(defn- die! [code message]
  (binding [*out* *err*] (println message))
  (System/exit code))

(defn- sh-out [& args]
  (let [{:keys [out exit]} (apply process/sh {:continue true} args)]
    (when (zero? exit) (str/trim out))))

(defn origin-repo
  "owner/name from the origin remote.

   Never let gh work this out for itself: in a fork with an upstream remote it
   resolves the upstream, and every call then targets the wrong repository."
  []
  (when-let [url (sh-out "git" "remote" "get-url" "origin")]
    (let [trimmed (str/replace (str/trim url) #"\.git$" "")]
      (second (re-find #"[:/]([^:/]+/[^:/]+)$" trimmed)))))

(defn parse-args [args]
  (loop [remaining args
         opts {:interval default-interval :timeout default-timeout}]
    (if-let [arg (first remaining)]
      (case arg
        "--repo"     (recur (drop 2 remaining) (assoc opts :repo (second remaining)))
        "--interval" (recur (drop 2 remaining) (assoc opts :interval (parse-long (str (second remaining)))))
        "--timeout"  (recur (drop 2 remaining) (assoc opts :timeout (parse-long (str (second remaining)))))
        "--notify"   (recur (drop 2 remaining) (assoc opts :notify (second remaining)))
        "--task"     (recur (drop 2 remaining) (assoc opts :task (second remaining)))
        (if (:pr opts)
          (die! 1 (str "Unexpected argument: " arg))
          (recur (next remaining) (assoc opts :pr arg))))
      opts)))

(defn pr-state
  "MERGED, CLOSED, or OPEN. nil when gh cannot answer at all."
  [pr repo]
  (when-let [out (sh-out "gh" "pr" "view" pr "--repo" repo "--json" "state,mergedAt")]
    (try
      (:state (json/parse-string out true))
      (catch Exception _ nil))))

(defn wait-for
  "Poll until the pull request leaves OPEN or the deadline passes."
  [{:keys [pr repo interval timeout]}]
  (let [deadline (+ (System/currentTimeMillis) (* 1000 (long timeout)))]
    (loop []
      (let [state (pr-state pr repo)]
        (cond
          (nil? state)       :error
          (= "MERGED" state) :merged
          (= "CLOSED" state) :closed
          (>= (System/currentTimeMillis) deadline) :timeout
          :else (do
                  (Thread/sleep (* 1000 (long interval)))
                  (if (>= (System/currentTimeMillis) deadline)
                    :timeout
                    (recur))))))))

(defn message-for
  "A note body, truncated to the 80 characters swarm_handoff.sh allows."
  [outcome pr task]
  (let [suffix (if (str/blank? task) "" (str " for " task))
        text (case outcome
               :merged  (str "PR #" pr " merged" suffix)
               :closed  (str "PR #" pr " closed unmerged" suffix)
               :timeout (str "PR #" pr " still open after wait" suffix))]
    (subs text 0 (min 80 (count text)))))

(defn notify!
  "Queue a note through swarm_handoff.sh, the only sanctioned outbound path.
   A file written into the outbox directly is rejected to failed/ undelivered."
  [role outcome pr task]
  (let [draft (fs/path "tmp" (str "pr-wait-" pr ".draft"))
        helper (str (fs/path (fs/parent (fs/absolutize *file*)) "swarm_handoff.sh"))]
    (fs/create-dirs "tmp")
    (spit (str draft)
          (str "type: note\n"
               "to: " role "\n"
               "priority: 20\n"
               "message: " (message-for outcome pr task) "\n"))
    (let [{:keys [exit err out]} (process/sh {:continue true} helper (str draft))]
      (when-not (zero? exit)
        (binding [*out* *err*]
          (println "swarm_pr_wait: handoff failed:" (str/trim (str err out))))))))

(defn -main [& args]
  (let [{:keys [pr notify task] :as opts} (parse-args args)
        repo (or (:repo opts) (origin-repo))]
    (when (str/blank? pr)
      (die! 1 (str "Usage: swarm_pr_wait.sh <pr-number> [--repo owner/name] "
                   "[--interval s] [--timeout s] [--notify role] [--task name]")))
    (when (str/blank? repo)
      (die! 1 "Cannot determine the repository; pass --repo owner/name."))
    (let [outcome (wait-for (assoc opts :pr pr :repo repo))]
      (when (= :error outcome)
        (die! 1 (str "gh could not read pull request #" pr " in " repo)))
      (println (str/upper-case (name outcome)))
      (when notify (notify! notify outcome pr task))
      (System/exit (case outcome :merged 0 :closed 2 :timeout 3)))))

(apply -main *command-line-args*)
