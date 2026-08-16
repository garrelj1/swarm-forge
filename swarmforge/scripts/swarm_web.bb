#!/usr/bin/env bb

;; swarm_web — the project dashboard, served read-only on localhost.
;;
;; org.httpkit.server ships inside babashka, so this adds no dependency and no
;; build step. Two routes and a polling page.
;;
;; The tracker half is cached because gh costs a second or two per call; the
;; pipeline half is filesystem-cheap and is recomputed on every request, so
;; occupancy stays live even between tracker refreshes.
;;
;; Read-only by design: anything actionable links out to GitHub. No auth, no
;; non-local binding, no writes, no websockets.
;;
;; Usage:
;;   swarm_web.sh [--port 7777] [--cache-seconds 30] [--repo owner/name]

(ns swarm-web
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [org.httpkit.server :as http]))

(def default-port 7777)
(def default-cache-seconds 30)

(defn- sibling [name]
  (str (fs/path (fs/parent (fs/absolutize *file*)) name)))

(defn brief-reason
  "One readable line from a failed command's stderr; a babashka stack trace is
   dozens of lines and would be rendered whole as the reason a panel is empty."
  [text]
  (let [lines (remove str/blank? (str/split-lines (str text)))
        message (first (filter #(str/starts-with? (str/trim %) "Message:") lines))
        line (str/replace (str/trim (or message (first lines) "")) #"^Message:\s*" "")]
    (if (> (count line) 160) (str (subs line 0 157) "...") line)))

(defn- run-json [script & args]
  (let [{:keys [out exit err]} (apply process/sh {:continue true} script args)]
    (if (zero? exit)
      (try
        (json/parse-string out true)
        (catch Exception e {:available false :reason (str "unparseable output: " (ex-message e))}))
      {:available false :reason (brief-reason err)})))

(def cache (atom {:at 0 :value nil}))

(defn project-state
  "swarm_project --json, cached. The whole tracker half sits behind this."
  [repo ttl-ms]
  (let [{:keys [at value]} @cache
        now (System/currentTimeMillis)]
    (if (and value (< (- now at) ttl-ms))
      value
      (let [fresh (apply run-json (sibling "swarm_project.sh")
                         (cond-> ["--json"] repo (conj "--repo") repo (conj repo)))]
        (reset! cache {:at now :value fresh})
        fresh))))

(defn state [repo ttl-ms]
  (assoc (project-state repo ttl-ms)
         :pipeline_live (run-json (sibling "swarm_status.sh") "--json")))

(def page
  "<!doctype html>
<html lang=\"en\">
<head>
<meta charset=\"utf-8\">
<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">
<title>SwarmForge Project</title>
<style>
  :root {
    --bg: #fbfbfa; --panel: #ffffff; --line: #e4e2dd; --ink: #1a1917;
    --muted: #6f6b63; --accent: #3d6b53; --warn: #8a5a2b;
  }
  @media (prefers-color-scheme: dark) {
    :root {
      --bg: #16161a; --panel: #1e1e23; --line: #2e2e35; --ink: #eceae5;
      --muted: #9a958c; --accent: #7fb79a; --warn: #d0a06a;
    }
  }
  * { box-sizing: border-box; }
  body {
    margin: 0; padding: 2rem 1.5rem; background: var(--bg); color: var(--ink);
    font: 15px/1.55 ui-sans-serif, system-ui, -apple-system, 'Segoe UI', sans-serif;
  }
  main { max-width: 1000px; margin: 0 auto; }
  h1 { font-size: 1.1rem; letter-spacing: .02em; margin: 0 0 .25rem; }
  .repo { color: var(--muted); font-size: .85rem; margin-bottom: 1.5rem; }
  .strip {
    display: flex; gap: 2rem; padding: .9rem 1.1rem; margin-bottom: 1.75rem;
    background: var(--panel); border: 1px solid var(--line); border-radius: 8px;
  }
  .strip div { font-size: .8rem; color: var(--muted); }
  .strip b { display: block; font-size: 1.35rem; color: var(--ink); font-weight: 600; }
  h2 {
    font-size: .78rem; text-transform: uppercase; letter-spacing: .09em;
    color: var(--muted); margin: 1.75rem 0 .6rem; font-weight: 600;
  }
  .card {
    display: grid; grid-template-columns: 4.5rem 1fr auto auto;
    gap: 1rem; align-items: center;
    padding: .7rem 1.1rem; background: var(--panel);
    border: 1px solid var(--line); border-radius: 8px; margin-bottom: .4rem;
  }
  .num { color: var(--muted); font-variant-numeric: tabular-nums; font-size: .85rem; }
  .title { font-weight: 500; }
  .slice { display: block; color: var(--muted); font-size: .78rem;
           font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
  .stage {
    font-size: .74rem; padding: .18rem .55rem; border-radius: 999px;
    border: 1px solid var(--line); color: var(--muted); white-space: nowrap;
  }
  .stage.live { color: var(--accent); border-color: var(--accent); }
  .stage.review { color: var(--warn); border-color: var(--warn); }
  a { color: inherit; }
  .note { color: var(--warn); font-size: .85rem; margin-bottom: 1.5rem; }
  .empty { color: var(--muted); font-size: .9rem; }
</style>
</head>
<body>
<main>
  <h1>SwarmForge Project</h1>
  <div class=\"repo\" id=\"repo\"></div>
  <div id=\"note\"></div>
  <div class=\"strip\">
    <div><b id=\"flight\">–</b>in flight</div>
    <div><b id=\"review\">–</b>awaiting merge</div>
    <div><b id=\"backlog\">–</b>backlog</div>
  </div>
  <div id=\"board\"></div>
</main>
<script>
const esc = s => String(s ?? '').replace(/[&<>\"]/g, c =>
  ({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[c]));

function stageClass(stage) {
  if (stage === 'review') return 'stage review';
  if (['backlog', 'done', 'handed off'].includes(stage)) return 'stage';
  return 'stage live';
}

function render(s) {
  document.getElementById('repo').textContent = s.repo || '';
  const live = (s.pipeline_live && s.pipeline_live.in_flight) || [];
  document.getElementById('flight').textContent = live.length;
  document.getElementById('review').textContent = (s.summary && s.summary.awaiting_merge) ?? 0;
  document.getElementById('backlog').textContent = (s.summary && s.summary.backlog) ?? 0;

  const note = document.getElementById('note');
  note.innerHTML = (s.tracker && s.tracker.available === false)
    ? '<div class=\"note\">Tracker unavailable: ' + esc(s.tracker.reason) +
      ' — showing pipeline state only.</div>'
    : '';

  const rows = s.rows || [];
  const board = document.getElementById('board');
  if (!rows.length) {
    board.innerHTML = '<p class=\"empty\">No issues to show.</p>';
    return;
  }
  const groups = {};
  for (const r of rows) (groups[r.milestone || 'No milestone'] ??= []).push(r);

  board.innerHTML = Object.entries(groups).map(([milestone, items]) =>
    '<h2>' + esc(milestone) + '</h2>' + items.map(r =>
      '<div class=\"card\">' +
        '<span class=\"num\">#' + esc(r.issue) + '</span>' +
        '<span class=\"title\">' + esc(r.title) +
          (r.slice ? '<span class=\"slice\">' + esc(r.slice) + '</span>' : '') +
        '</span>' +
        '<span class=\"' + stageClass(r.stage) + '\">' + esc(r.stage) + '</span>' +
        '<span class=\"num\">' + (r.pr ? '#' + esc(r.pr) + ' ' + esc((r.pr_state||'').toLowerCase()) : '—') + '</span>' +
      '</div>').join('')).join('');
}

async function tick() {
  try {
    const res = await fetch('/api/state');
    render(await res.json());
  } catch (e) { /* keep the last good render */ }
}
tick();
setInterval(tick, 5000);
</script>
</body>
</html>")

(defn handler [repo ttl-ms]
  (fn [req]
    (case (:uri req)
      "/" {:status 200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body page}
      "/api/state" {:status 200
                    :headers {"Content-Type" "application/json"}
                    :body (json/generate-string (state repo ttl-ms))}
      {:status 404
       :headers {"Content-Type" "text/plain"}
       :body "not found"})))

(defn parse-args [args]
  (loop [remaining args
         opts {:port default-port :cache-seconds default-cache-seconds}]
    (if-let [arg (first remaining)]
      (case arg
        "--port"          (recur (drop 2 remaining) (assoc opts :port (parse-long (str (second remaining)))))
        "--cache-seconds" (recur (drop 2 remaining) (assoc opts :cache-seconds (parse-long (str (second remaining)))))
        "--repo"          (recur (drop 2 remaining) (assoc opts :repo (second remaining)))
        (recur (next remaining) opts))
      opts)))

(defn -main [& args]
  (let [{:keys [port cache-seconds repo]} (parse-args args)]
    (http/run-server (handler repo (* 1000 (long cache-seconds)))
                     {:ip "127.0.0.1" :port port})
    (println (str "SwarmForge dashboard on http://127.0.0.1:" port))
    @(promise)))

(apply -main *command-line-args*)
