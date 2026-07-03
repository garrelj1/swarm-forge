(ns swarmforge.script-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def repo-root (fs/cwd))
(def scripts-dir (fs/path repo-root "swarmforge" "scripts"))

(defn write-file [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn run
  [{:keys [dir env ok?]} & args]
  (let [result (apply sh/sh (concat args [:dir (str dir)
                                          :env (merge {"PATH" (System/getenv "PATH")
                                                       "GIT_CONFIG_NOSYSTEM" "1"}
                                                      env)]))]
    (when (and (not (false? ok?)) (not= 0 (:exit result)))
      (throw (ex-info (str "Command failed: " (str/join " " args))
                      (assoc result :args args))))
    result))

(defn init-repo! [root]
  (run {:dir root} "git" "init" "-q")
  (run {:dir root} "git" "config" "user.email" "test@example.com")
  (run {:dir root} "git" "config" "user.name" "Test User")
  (write-file (fs/path root "README.md") "initial\n")
  (run {:dir root} "git" "add" "README.md")
  (run {:dir root} "git" "commit" "-q" "-m" "Initial commit"))

(defn tmp-dir []
  (fs/create-temp-dir {:prefix "swarmforge-script-test."}))

(defn script [name]
  (str (fs/path scripts-dir name)))

(deftest handoff-lib-parses-and-prints-handoff-files
  (let [root (tmp-dir)
        handoff-file (fs/path root "task.handoff")]
    (try
      (write-file handoff-file
                  (str "id: 1\n"
                       "from: coder\n"
                       "to: cleaner\n"
                       "priority: 10\n"
                       "type: git_handoff\n"
                       "task: task-alpha\n"
                       "\n"
                       "merge_and_process coder abcdef1234\n"))
      (let [header (run {:dir root} (script "handoff_lib.bb") "header-field" "task.handoff" "task")
            body (run {:dir root} (script "handoff_lib.bb") "body" "task.handoff")
            task (run {:dir root} (script "handoff_lib.bb") "print-task" "task.handoff")]
        (is (str/includes? (:out header) "task-alpha"))
        (is (str/includes? (:out body) "merge_and_process coder abcdef1234"))
        (is (str/includes? (:out task) "TASK: task.handoff"))
        (is (str/includes? (:out task) "FROM: coder"))
        (is (str/includes? (:out task) "TASK_NAME: task-alpha")))
      (finally
        (fs/delete-tree root)))))

(deftest handoff-lib-updates-headers-and-reads-role-state
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "coder\tmaster\t" root "\tsession\tCoder\tcodex\ttask\n"
                       "cleaner\tcleaner\t" root "/.worktrees/cleaner\tsession\tCleaner\tcodex\tbatch\n"))
      (write-file (fs/path root ".swarmforge/handoffs/inbox/new/item.handoff")
                  (str "id: 1\n"
                       "from: coder\n"
                       "to: cleaner\n"
                       "priority: 20\n"
                       "type: note\n"
                       "\n"
                       "payload\n"))
      (run {:dir root} (script "handoff_lib.bb") "role-known" "cleaner")
      (run {:dir root} (script "handoff_lib.bb") "set-header" ".swarmforge/handoffs/inbox/new/item.handoff" "dequeued_at" "2026-06-16T00:00:00Z")
      (let [mode (run {:dir root} (script "handoff_lib.bb") "role-receive-mode" "cleaner")
            worktree (run {:dir root} (script "handoff_lib.bb") "role-worktree-name" "cleaner")
            dequeued (run {:dir root} (script "handoff_lib.bb") "header-field" ".swarmforge/handoffs/inbox/new/item.handoff" "dequeued_at")
            seq-1 (run {:dir root} (script "handoff_lib.bb") "next-sequence")
            seq-2 (run {:dir root} (script "handoff_lib.bb") "next-sequence")]
        (is (str/includes? (:out mode) "batch"))
        (is (str/includes? (:out worktree) "cleaner"))
        (is (str/includes? (:out dequeued) "2026-06-16T00:00:00Z"))
        (is (str/includes? (:out seq-1) "000001"))
        (is (str/includes? (:out seq-2) "000002")))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-launcher-parses-config-and-writes-state-files
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  (str "# comment\n"
                       "window coder codex master\n"
                       "window cleaner codex cleaner batch\n"))
      (write-file (fs/path root "swarmforge/roles/coder.prompt") "coder\n")
      (write-file (fs/path root "swarmforge/roles/cleaner.prompt") "cleaner\n")
      (let [result (run {:dir root :env {"SWARMFORGE_INSTANCE" "demo"}}
                        (script "swarmforge.bb") "--test-parse" (str root))]
        (is (str/includes? (:out result) "coder Coder"))
        (is (str/includes? (:out result) "cleaner Cleaner"))
        (is (str/includes? (:out result) "cleaner batch"))
        (is (str/includes? (:out result) "swarmforge-demo-coder"))
        (is (str/includes? (:out result) "swarmforge-demo-cleaner"))
        (is (fs/exists? (fs/path root ".swarmforge/tmux-socket"))))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-uses-portable-tmux-socket-dir
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  "window coder codex master\n")
      (write-file (fs/path root "swarmforge/roles/coder.prompt") "coder\n")
      (run {:dir root} (script "swarmforge.bb") "--test-parse" (str root))
      (let [socket-path (str/trim (slurp (str (fs/path root ".swarmforge/tmux-socket"))))]
        (is (str/starts-with? socket-path "/tmp/swarmforge-"))
        (is (not (str/starts-with? socket-path "/private/tmp/"))))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-launcher-rejects-invalid-config
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  (str "window coder codex master\n"
                       "window coder codex other\n"))
      (write-file (fs/path root "swarmforge/roles/coder.prompt") "coder\n")
      (let [result (run {:dir root :ok? false} (script "swarmforge.bb") "--test-parse" (str root))]
        (is (= 1 (:exit result)))
        (is (str/includes? (:err result) "Duplicate role 'coder'")))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-terminal-bridge-preserves-adapter-globals
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/scripts/swarm-terminal-adapter.sh")
                  (str "load_terminal_backend() {\n"
                       "  source \"$SCRIPT_DIR/terminal-adapters/$1.sh\"\n"
                       "}\n"))
      (write-file (fs/path root "swarmforge/scripts/terminal-adapters/probe.sh")
                  (str "terminal_open_session() {\n"
                       "  printf '%s\\n' \"$WORKING_DIR|$TMUX_SOCKET|$1|$2|$3\"\n"
                       "}\n"))
      (let [result (run {:dir root}
                        (script "swarmforge.bb")
                        "--test-terminal-bridge"
                        (str root)
                        "probe")]
        (is (str/includes? (:out result) (str root "|")))
        (is (str/includes? (:out result) "|swarmforge-specifier|SwarmForge Specifier|"))
        (is (not (str/includes? (:out result) "cd ''")))
        (is (not (str/includes? (:out result) "-S ''"))))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-agent-start-delay-is-configurable
  (let [default-result (run {:dir repo-root}
                            (script "swarmforge.bb")
                            "--test-agent-start-delay")
        configured-result (run {:dir repo-root
                                :env {"SWARMFORGE_AGENT_START_DELAY_MS" "2750"}}
                               (script "swarmforge.bb")
                               "--test-agent-start-delay")
        invalid-result (run {:dir repo-root
                             :env {"SWARMFORGE_AGENT_START_DELAY_MS" "fast"}}
                            (script "swarmforge.bb")
                            "--test-agent-start-delay")]
    (is (= "1500" (str/trim (:out default-result))))
    (is (= "2750" (str/trim (:out configured-result))))
    (is (= "1500" (str/trim (:out invalid-result))))))

(deftest swarmforge-notifications-toggle-defaults-on
  (let [default-result (run {:dir repo-root} (script "swarmforge.bb") "--test-notifications-enabled")
        off-result (run {:dir repo-root :env {"SWARMFORGE_NOTIFICATIONS" "off"}}
                        (script "swarmforge.bb") "--test-notifications-enabled")
        on-result (run {:dir repo-root :env {"SWARMFORGE_NOTIFICATIONS" "on"}}
                       (script "swarmforge.bb") "--test-notifications-enabled")]
    (is (= "true" (str/trim (:out default-result))))
    (is (= "false" (str/trim (:out off-result))))
    (is (= "true" (str/trim (:out on-result))))))

(deftest swarmforge-launcher-parses-extra-cli-args
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  (str "window coder copilot master --yolo\n"
                       "window cleaner copilot cleaner batch --allow-all-tools\n"))
      (write-file (fs/path root "swarmforge/roles/coder.prompt") "coder\n")
      (write-file (fs/path root "swarmforge/roles/cleaner.prompt") "cleaner\n")
      (let [result (run {:dir root} (script "swarmforge.bb") "--test-parse" (str root))]
        (is (str/includes? (:out result) "coder Coder"))
        (is (str/includes? (:out result) "task --yolo"))
        (is (str/includes? (:out result) "batch --allow-all-tools")))
      (finally
        (fs/delete-tree root)))))

(deftest copilot-launch-command-passes-extra-cli-args
  (let [root (tmp-dir)]
    (try
      (let [result (run {:dir root}
                        (script "swarmforge.bb")
                        "--test-launch-command"
                        (str root)
                        "copilot"
                        "--yolo")
            command (:out result)]
        (is (str/includes? command "copilot -C "))
        (is (re-find #"--name 'SwarmForge test Coder' --yolo -i" command)))
      (finally
        (fs/delete-tree root)))))

(deftest grok-launch-command-passes-initial-prompt
  (let [root (tmp-dir)]
    (try
      (let [result (run {:dir root}
                        (script "swarmforge.bb")
                        "--test-launch-command"
                        (str root)
                        "grok")
            command (:out result)]
        (is (str/includes? command "grok --cwd "))
        (is (str/includes? command "--rules \"$(cat "))
        (is (str/includes? command "--verbatim \"$(cat "))
        (is (str/includes? command ".swarmforge/prompts/coder.md"))
        (is (fs/exists? (fs/path root ".swarmforge/prompts/coder.md"))))
      (finally
        (fs/delete-tree root)))))

(deftest window-watchdog-rewrites-window-state-and-id-list
  (let [root (tmp-dir)
        state-file (fs/path root "windows.tsv")
        ids-file (fs/path root "window-ids")]
    (try
      (write-file state-file
                  (str "1\told-a\tswarmforge-coder\tSwarmForge Coder\n"
                       "2\told-b\tswarmforge-cleaner\tSwarmForge Cleaner\n"))
      (write-file ids-file "old-a\nold-b\n")
      (run {:dir root} (script "swarm-window-watchdog.bb") "--rewrite-window-id" "windows.tsv" "window-ids" "2" "new-b")
      (let [state (slurp (str state-file))
            ids (slurp (str ids-file))]
        (is (str/includes? state "1\told-a\tswarmforge-coder\tSwarmForge Coder"))
        (is (str/includes? state "2\tnew-b\tswarmforge-cleaner\tSwarmForge Cleaner"))
        (is (= "old-a\nnew-b\n" ids)))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-detects-nonzero-pane-base-index
  (let [root (tmp-dir)
        sock (str root "/test.sock")
        conf (fs/path root "tmux.conf")]
    (try
      (write-file conf "set -g base-index 1\nset -g pane-base-index 1\n")
      (run {:dir root} "tmux" "-S" sock "-f" (str conf) "new-session" "-d" "-s" "probe" "sleep" "120")
      (let [result (run {:dir root}
                        (script "swarmforge.bb")
                        "--test-tmux-base-indexes"
                        sock)]
        (is (= "1 1" (str/trim (:out result)))))
      (finally
        (run {:dir root :ok? false} "tmux" "-S" sock "kill-server")
        (fs/delete-tree root)))))

(deftest swarm-cleanup-tolerates-missing-runtime-state
  (let [root (tmp-dir)
        ids-file (fs/path root ".swarmforge/window-ids")]
    (try
      (write-file ids-file "window-a\nwindow-b\n")
      (let [result (run {:dir root
                         :env {"SWARMFORGE_TERMINAL_BACKEND" "none"}}
                        (str (fs/path scripts-dir "swarm-cleanup.sh"))
                        "/tmp/nonexistent.sock"
                        (str ids-file))]
        (is (= 0 (:exit result)))
        (is (= "" (:err result))))
      (finally
        (fs/delete-tree root)))))

(def bell "")

(deftest notify-host-emits-bell-and-exits-zero
  (doseq [event ["needs-input" "task-done" "error" "unknown-event"]]
    (let [result (run {:dir repo-root} (script "notify-host.sh") event)]
      (is (= 0 (:exit result)) (str "event: " event))
      (is (str/includes? (:out result) bell) (str "event: " event)))))

(deftest notify-host-exits-zero-with-no-args
  (let [result (run {:dir repo-root} (script "notify-host.sh"))]
    (is (= 0 (:exit result)))
    (is (str/includes? (:out result) bell))))

(deftest notify-host-tolerates-missing-sound-players
  (let [stub-bin (fs/create-temp-dir {:prefix "swarmforge-notify-stub-bin."})
        zsh-path (str/trim (:out (sh/sh "which" "zsh")))]
    (try
      ;; A PATH with just zsh (so the #!/usr/bin/env zsh shebang still
      ;; resolves) and a stub true, but no afplay/paplay/aplay.
      (write-file (fs/path stub-bin "true") "#!/bin/sh\nexit 0\n")
      (fs/set-posix-file-permissions (fs/path stub-bin "true") "rwxr-xr-x")
      (fs/create-sym-link (fs/path stub-bin "zsh") zsh-path)
      (let [result (run {:dir repo-root :env {"PATH" (str stub-bin)}}
                        (script "notify-host.sh") "needs-input")]
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) bell)))
      (finally
        (fs/delete-tree stub-bin)))))

(deftest swarmforge-namespaces-sessions-by-instance
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/constitution.prompt") "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf") "window coder codex master\n")
      (write-file (fs/path root "swarmforge/roles/coder.prompt") "coder\n")
      (let [alpha (run {:dir root :env {"SWARMFORGE_INSTANCE" "alpha"}}
                       (script "swarmforge.bb") "--test-parse" (str root))
            beta (run {:dir root :env {"SWARMFORGE_INSTANCE" "beta"}}
                      (script "swarmforge.bb") "--test-parse" (str root))]
        ;; Two swarms on one machine get distinct session names so they never
        ;; collide on a shared tmux server or in the agent CLI's session store.
        (is (str/includes? (:out alpha) "swarmforge-alpha-coder"))
        (is (str/includes? (:out beta) "swarmforge-beta-coder"))
        (is (not (str/includes? (:out alpha) "swarmforge-beta-coder"))))
      (finally
        (fs/delete-tree root)))))

(deftest swarm-cleanup-spares-foreign-pids
  ;; A stale pid-file whose PID was recycled must not let cleanup kill an
  ;; unrelated process (e.g. another swarm's daemon).
  (let [root (tmp-dir)
        ids-file (fs/path root ".swarmforge/window-ids")
        pid-file (fs/path root ".swarmforge/daemon/handoffd.pid")
        victim (p/process ["sleep" "30"])
        victim-pid (.pid (:proc victim))]
    (try
      (write-file ids-file "")
      (write-file pid-file (str victim-pid "\n"))
      (run {:dir root :env {"SWARMFORGE_TERMINAL_BACKEND" "none"}}
           (str (fs/path scripts-dir "swarm-cleanup.sh"))
           "/tmp/nonexistent.sock"
           (str ids-file))
      (Thread/sleep 300)
      (is (.isAlive (:proc victim))
          "cleanup must not TERM a non-handoffd PID")
      (finally
        (p/destroy victim)
        (fs/delete-tree root)))))

(defn read-settings [root]
  (json/parse-string (slurp (str (fs/path root ".claude" "settings.json"))) true))

(deftest claude-hooks-merged-into-empty-worktree
  (let [root (tmp-dir)]
    (try
      (run {:dir root} (script "swarmforge.bb") "--test-ensure-claude-hooks" (str root))
      (let [settings (read-settings root)
            notification (first (get-in settings [:hooks :Notification]))
            stop (first (get-in settings [:hooks :Stop]))
            failure (first (get-in settings [:hooks :PostToolUseFailure]))]
        (is (= "permission_prompt|idle_prompt" (:matcher notification)))
        (is (str/includes? (get-in notification [:hooks 0 :command]) "notify-host.sh needs-input"))
        (is (str/includes? (get-in stop [:hooks 0 :command]) "notify-host.sh task-done"))
        (is (str/includes? (get-in failure [:hooks 0 :command]) "notify-host.sh error")))
      (finally
        (fs/delete-tree root)))))

(deftest claude-hooks-merge-preserves-existing-settings
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root ".claude" "settings.json")
                  (json/generate-string
                   {:permissions {:allow ["Bash(npm run *)"]}
                    :hooks {:Notification [{:matcher "auth_success"
                                             :hooks [{:type "command" :command "echo mine"}]}]}}))
      (run {:dir root} (script "swarmforge.bb") "--test-ensure-claude-hooks" (str root))
      (let [settings (read-settings root)]
        (is (= ["Bash(npm run *)"] (get-in settings [:permissions :allow])))
        (is (= 2 (count (get-in settings [:hooks :Notification]))))
        (is (some #(= "echo mine" (get-in % [:hooks 0 :command]))
                  (get-in settings [:hooks :Notification]))))
      (finally
        (fs/delete-tree root)))))

(deftest claude-hooks-merge-is-idempotent
  (let [root (tmp-dir)]
    (try
      (run {:dir root} (script "swarmforge.bb") "--test-ensure-claude-hooks" (str root))
      (run {:dir root} (script "swarmforge.bb") "--test-ensure-claude-hooks" (str root))
      (let [settings (read-settings root)]
        (is (= 1 (count (get-in settings [:hooks :Notification]))))
        (is (= 1 (count (get-in settings [:hooks :Stop]))))
        (is (= 1 (count (get-in settings [:hooks :PostToolUseFailure])))))
      (finally
        (fs/delete-tree root)))))

(deftest claude-hooks-merge-skips-on-malformed-json
  (let [root (tmp-dir)
        settings-file (fs/path root ".claude" "settings.json")]
    (try
      (write-file settings-file "{not valid json")
      (let [result (run {:dir root} (script "swarmforge.bb") "--test-ensure-claude-hooks" (str root))]
        (is (= 0 (:exit result)))
        (is (str/includes? (:err result) "skipping hook wiring"))
        (is (= "{not valid json" (slurp (str settings-file)))))
      (finally
        (fs/delete-tree root)))))
