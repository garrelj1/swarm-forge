(ns swarmforge.support
  "Shared fixtures for the script tests.

   Both handoff-test and script-test need to drive scripts that shell out to
   `gh`, and neither may reach the network, so the stub lives here rather than
   being copied into each."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(def open-pr "{\"state\":\"OPEN\",\"mergedAt\":null}")
(def merged-pr "{\"state\":\"MERGED\",\"mergedAt\":\"2026-08-14T16:37:55Z\"}")
(def closed-pr "{\"state\":\"CLOSED\",\"mergedAt\":null}")

(defn- invocation
  "Normalize a stub entry: a bare string is stdout with a zero exit."
  [entry]
  (if (map? entry)
    (merge {:out "" :err "" :exit 0} entry)
    {:out entry :err "" :exit 0}))

(defn stub-gh!
  "Write a fake `gh` into <dir>/bin and return that directory for prepending to
   PATH.

   Each invocation emits the next entry in `invocations`, holding the last one
   once they run out, and appends its argv to <dir>/calls.log so a test can
   assert on what was asked for — `--repo` in particular, which every call must
   pass because gh resolves the upstream remote in this fork otherwise."
  [dir invocations]
  (let [bin (fs/path dir "bin")
        entries (mapv invocation invocations)
        calls-log (str (fs/path dir "calls.log"))]
    (fs/create-dirs bin)
    (doseq [[i {:keys [out err exit]}] (map-indexed vector entries)]
      (spit (str (fs/path dir (str "out-" i))) out)
      (spit (str (fs/path dir (str "err-" i))) err)
      (spit (str (fs/path dir (str "exit-" i))) (str exit)))
    (let [gh (fs/path bin "gh")]
      (spit (str gh)
            (str/join "\n"
                      ["#!/usr/bin/env bash"
                       (str "echo \"$@\" >> " calls-log)
                       (str "n=$(wc -l < " calls-log ")")
                       "i=$((n-1))"
                       (str "last=" (dec (count entries)))
                       "if [ \"$i\" -gt \"$last\" ]; then i=\"$last\"; fi"
                       (str "cat " dir "/out-$i")
                       (str "cat " dir "/err-$i" " >&2")
                       (str "exit \"$(cat " dir "/exit-$i)\"")
                       ""]))
      (fs/set-posix-file-permissions gh "rwxr-xr-x"))
    (str bin)))

(defn gh-calls
  "Every argv line the stub recorded, in order."
  [dir]
  (let [log (fs/path dir "calls.log")]
    (if (fs/exists? log)
      (remove str/blank? (str/split-lines (slurp (str log))))
      [])))

(defn path-with
  "PATH with `bin` in front, so the stub shadows any real gh."
  [bin]
  (str bin ":" (System/getenv "PATH")))
