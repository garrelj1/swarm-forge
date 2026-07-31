#!/usr/bin/env bb

;; swarm_gates — run the project's declared verification gates for this role.
;;
;; Reads swarmforge/gates.conf, selects the gates whose role list and path glob
;; both match what this role is about to hand off, runs them, and exits non-zero
;; if any fail. A project with no gates.conf gets no gates and no behaviour change.
;;
;; gates.conf format — three fields, the command being everything after the glob:
;;
;;   <roles>            <path-glob>              <command...>
;;   *,!specifier       supabase/**              pnpm run db:test
;;   coder,hardener,QA  specs/**/*-worker-*.feature   pnpm run rust:test
;;
;; roles:  comma-separated. `*` means every role; `!role` excludes one.
;;         Roles genuinely gate on different things — the specifier does not run
;;         the coder's build — so a path-only rule would be wrong.
;; glob:   matched against each changed path, repo-root-relative. `**` spans
;;         directories. A gate fires when at least one changed path matches.
;;
;; Usage:
;;   swarm_gates.sh                 run the gates for $SWARMFORGE_ROLE
;;   swarm_gates.sh --role coder    run them for a named role
;;   swarm_gates.sh --since <ref>   compare against <ref> instead of the received commit
;;   swarm_gates.sh --list          show what would run, run nothing
;;   swarm_gates.sh --json          machine-readable result

(ns swarm-gates
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn sh-out [& args]
  (let [{:keys [out exit]} (apply process/sh {:continue true} args)]
    (when (zero? exit) (str/trim out))))

(defn worktree-root
  "Top of the tree this role is working in. Gates must run here, not in the main
   checkout: the diff being judged belongs to this worktree's branch."
  []
  (or (some-> (sh-out "git" "rev-parse" "--show-toplevel") fs/path)
      (fs/cwd)))

(defn main-checkout
  "The main checkout, even when called from a role worktree. gates.conf is read
   from here so one declaration governs every role: each worktree carries its own
   .swarmforge/roles.tsv, so resolving config relative to the worktree would give
   each role branch its own private — and usually missing — gate list."
  []
  (let [common (sh-out "git" "rev-parse" "--git-common-dir")]
    (some-> common fs/path fs/absolutize fs/parent fs/normalize)))

(defn project-root []
  (let [cwd (fs/cwd)]
    (if (fs/exists? (fs/path cwd ".swarmforge" "roles.tsv"))
      cwd
      (let [top (sh-out "git" "rev-parse" "--show-toplevel")]
        (if (and top (fs/exists? (fs/path top ".swarmforge" "roles.tsv")))
          (fs/path top)
          (let [common (sh-out "git" "rev-parse" "--git-common-dir")
                parent (some-> common fs/path fs/absolutize fs/parent)]
            (if (and parent (fs/exists? (fs/path parent ".swarmforge" "roles.tsv")))
              parent
              (throw (ex-info "Cannot find SwarmForge project root" {:exit 2})))))))))

;; --- what changed ------------------------------------------------------------

(defn headers [file]
  (->> (str/split-lines (slurp (str file)))
       (take-while (complement str/blank?))
       (keep (fn [line]
               (when-let [[_ k v] (re-matches #"^([a-z_]+):\s*(.*)$" line)]
                 [(keyword k) v])))
       (into {})))

(defn received-commit
  "The commit this role was handed. Everything from it to HEAD is this role's
   own change, which is exactly the scope its gates should judge."
  []
  (let [dir (fs/path (worktree-root) ".swarmforge" "handoffs" "inbox" "in_process")]
    (when (fs/exists? dir)
      (->> (fs/list-dir dir)
           (mapcat (fn [e] (if (fs/directory? e) (fs/list-dir e) [e])))
           (filter #(str/ends-with? (fs/file-name %) ".handoff"))
           (keep #(:commit (headers %)))
           first))))

(defn base-ref [root]
  (let [f (fs/path root ".swarmforge" "base-branch")]
    (if (fs/exists? f)
      (str/trim (slurp (str f)))
      (some #(when (sh-out "git" "rev-parse" "--verify" "--quiet" %) %)
            ["origin/trunk" "origin/main" "origin/master" "trunk" "main" "master"]))))

(defn changed-paths [since]
  (if-let [out (sh-out "git" "diff" "--name-only" (str since "...HEAD"))]
    (remove str/blank? (str/split-lines out))
    []))

;; --- gate config -------------------------------------------------------------

(defn parse-gates [file]
  (->> (str/split-lines (slurp (str file)))
       (map str/trim)
       (remove #(or (str/blank? %) (str/starts-with? % "#")))
       (keep (fn [line]
               (let [[roles glob & rest] (str/split line #"\s+")]
                 (when (and roles glob (seq rest))
                   {:roles roles :glob glob :command (str/join " " rest)}))))))

(defn role-matches? [spec role]
  (let [tokens (str/split spec #",")
        excluded (some #(= % (str "!" role)) tokens)
        included (some #(or (= % "*") (= % role)) tokens)]
    (boolean (and included (not excluded)))))

(defn glob-matches? [glob paths]
  (let [matcher (.getPathMatcher (java.nio.file.FileSystems/getDefault) (str "glob:" glob))]
    (boolean (some #(.matches matcher (java.nio.file.Paths/get % (into-array String []))) paths))))

(defn select-gates
  "Gates matching this role and these paths, one entry per distinct command.
   Two globs can legitimately name the same command — a live-render check is owed
   for both an app change and a database change — and running it twice because a
   commit touched both would just double the cost. The triggering globs are kept
   so the report still says why it ran. Gates run in gates.conf order, so an
   author can sequence deliberately."
  [gates role paths]
  (let [matched (filter #(and (role-matches? (:roles %) role)
                              (glob-matches? (:glob %) paths))
                        gates)
        globs-for (reduce (fn [acc g] (update acc (:command g) (fnil conj []) (:glob g)))
                          {} matched)]
    (->> matched
         (reduce (fn [{:keys [seen out]} g]
                   (if (seen (:command g))
                     {:seen seen :out out}
                     {:seen (conj seen (:command g))
                      :out (conj out (assoc g :glob (str/join ", " (globs-for (:command g)))))}))
                 {:seen #{} :out []})
         :out)))

;; --- run ---------------------------------------------------------------------

(defn run-gate
  "Runs one gate at the top of the role's worktree. Streams the gate's output by
   default so a watching agent sees progress on a long run; captures it under
   --json, where inherited output would land in the middle of the JSON document."
  [wt gate capture?]
  (when-not capture?
    (println (str "GATE  " (:command gate))))
  (let [opts (merge {:dir (str wt) :continue true}
                    (when capture? {:out :string :err :string}))
        {:keys [exit out err]} (process/shell opts "zsh" "-c" (:command gate))
        passed (zero? exit)]
    (when-not capture?
      (println (str (if passed "PASS  " "FAIL  ") (:command gate))))
    (cond-> (assoc gate :exit exit :passed passed)
      ;; Keep the tail only: enough to see why a gate failed without embedding a
      ;; full build log in the result.
      (and capture? (not passed))
      (assoc :output (->> (str (or out "") (or err ""))
                          str/split-lines
                          (take-last 40)
                          (str/join "\n"))))))

(defn -main [& args]
  (let [args (vec args)
        arg-of (fn [flag] (let [i (.indexOf args flag)] (when (>= i 0) (get args (inc i)))))
        root (project-root)
        wt (worktree-root)
        role (or (arg-of "--role") (System/getenv "SWARMFORGE_ROLE"))
        ;; gates.conf comes from the main checkout so every role shares one
        ;; declaration; the gates themselves run in this role's worktree.
        conf (fs/path (or (main-checkout) root) "swarmforge" "gates.conf")]
    (when (str/blank? role)
      (binding [*out* *err*] (println "No role: set SWARMFORGE_ROLE or pass --role <role>."))
      (System/exit 2))
    (when-not (fs/exists? conf)
      (println "NO_GATES: swarmforge/gates.conf not found; nothing to check.")
      (System/exit 0))
    (let [since (or (arg-of "--since") (received-commit) (base-ref root))
          _ (when (str/blank? (str since))
              (binding [*out* *err*] (println "Cannot determine a comparison ref; pass --since <ref>."))
              (System/exit 2))
          paths (changed-paths since)
          gates (parse-gates conf)
          selected (select-gates gates role paths)
          dirty (seq (or (some-> (sh-out "git" "status" "--porcelain") str/split-lines) []))]
      (cond
        (some #{"--json"} args)
        (let [results (if (some #{"--list"} args)
                        selected
                        (mapv #(run-gate wt % true) selected))]
          (println (json/generate-string
                    {:role role :worktree (str wt) :since (str since) :changed_paths paths
                     :gates results
                     :passed (every? #(get % :passed true) results)}
                    {:pretty true}))
          (System/exit (if (every? #(get % :passed true) results) 0 1)))

        :else
        (do
          (println (str "role=" role "  worktree=" (fs/file-name wt) "  since=" since "  changed=" (count paths) " path(s)"))
          (when dirty
            (println "WARN  working tree is dirty; gates judge the committed state only."))
          (cond
            (empty? selected)
            (do (println "NO_GATES_MATCHED: nothing this role must verify for these paths.")
                (System/exit 0))

            (some #{"--list"} args)
            (do (doseq [g selected] (println (str "WOULD RUN  [" (:glob g) "]  " (:command g))))
                (System/exit 0))

            :else
            (let [results (mapv #(run-gate wt % false) selected)
                  failed (remove :passed results)]
              (println)
              (if (seq failed)
                (do (println (str "GATES FAILED: " (count failed) " of " (count results)))
                    (doseq [f failed] (println (str "  - " (:command f))))
                    (println "Do not hand off until these pass.")
                    (System/exit 1))
                (do (println (str "GATES PASSED: " (count results) " of " (count results)))
                    (System/exit 0))))))))))

(apply -main *command-line-args*)
