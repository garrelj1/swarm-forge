#!/usr/bin/env bb

(ns stop-handoff-daemon
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def default-timeout-ms 5000)
(def poll-ms 100)

(defn usage []
  (binding [*out* *err*]
    (println "Usage: stop_handoff_daemon.bb <project-root>"))
  (System/exit 1))

(defn live-handoffd?
  "True only when pid names a running handoffd process for THIS project. Guards
  every teardown path (swarm cleanup, window watchdog) against TERM/KILL of a
  stale/recycled PID that now belongs to an unrelated process or another swarm's
  daemon. Portable (no /proc dependency)."
  [project-root pid]
  (and (re-matches #"[0-9]+" (str pid))
       (let [{:keys [exit out]} (process/sh {:continue true} "ps" "-p" (str pid) "-o" "args=")]
         (and (zero? exit)
              (str/includes? out "handoffd.bb")
              (str/includes? out (str project-root))))))

(defn stop! [project-root & {:keys [timeout-ms] :or {timeout-ms default-timeout-ms}}]
  (let [daemon-dir (fs/path project-root ".swarmforge" "daemon")
        pid-file (fs/path daemon-dir "handoffd.pid")
        stop-file (fs/path daemon-dir "stop")]
    (fs/create-dirs daemon-dir)
    ;; The stop-file only affects THIS project's daemon (it watches this path),
    ;; so writing it is always safe; only the kill needs an identity guard.
    (when-not (fs/exists? stop-file)
      (spit (str stop-file) ""))
    (when (fs/exists? pid-file)
      (let [pid (str/trim (slurp (str pid-file)))]
        (when (live-handoffd? project-root pid)
          (process/sh {:continue true} "kill" "-TERM" pid)
          (loop [waited 0]
            (when (and (< waited timeout-ms) (live-handoffd? project-root pid))
              (Thread/sleep poll-ms)
              (recur (+ waited poll-ms))))
          (when (live-handoffd? project-root pid)
            (process/sh {:continue true} "kill" "-KILL" pid)
            (Thread/sleep poll-ms))))
      (fs/delete-if-exists pid-file))
    (fs/delete-if-exists stop-file)))

(defn -main [& args]
  (stop! (or (first args) (usage)))
  (System/exit 0))

(apply -main *command-line-args*)