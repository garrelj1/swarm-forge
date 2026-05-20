use axum::{
    extract::{Path, State},
    response::sse::{Event, Sse},
    routing::{get, post},
    Json, Router,
};
use clap::Parser;
use std::convert::Infallible;
use std::sync::Arc;
use tokio::sync::mpsc;
use tokio_stream::{wrappers::ReceiverStream, StreamExt};

#[derive(Parser)]
#[command(name = "swarmforge-ui")]
struct Args {
    #[arg(long, default_value = "7777")]
    port: u16,
    #[arg(long, default_value = ".")]
    working_dir: std::path::PathBuf,
}

#[derive(Clone)]
struct AppState {
    roles: Arc<Vec<sessions::Session>>,
    working_dir: std::path::PathBuf,
}

fn tmux_target(session: &str) -> String {
    format!("{}:0.0", session)
}

#[cfg(test)]
mod handler_tests {
    use super::*;

    #[test]
    fn formats_tmux_target() {
        assert_eq!(tmux_target("swarmforge-coder"), "swarmforge-coder:0.0");
    }
}

async fn sse_handler(
    State(state): State<AppState>,
) -> Sse<impl tokio_stream::Stream<Item = Result<Event, Infallible>>> {
    let (tx, rx) = mpsc::channel::<events::Payload>(256);

    // Send roles event immediately (synchronous so it's guaranteed first)
    let roles: Vec<String> = state.roles.iter().map(|s| s.role.clone()).collect();
    let _ = tx.try_send(events::Payload::Roles { roles });

    // Tail each pane log
    for session in state.roles.iter() {
        let log = state.working_dir.join(format!(".swarmforge/panes/{}.log", session.role));
        let role = session.role.clone();
        let tx2 = tx.clone();
        tokio::spawn(async move {
            let (ltx, mut lrx) = mpsc::channel(256);
            tokio::spawn(tailer::tail(log, ltx));
            while let Some(text) = lrx.recv().await {
                if tx2.send(events::Payload::Pane { role: role.clone(), text }).await.is_err() { break; }
            }
        });
    }

    // Tail message log — map session names back to role names
    {
        let log = state.working_dir.join("logs/agent_messages.log");
        let session_map: std::collections::HashMap<String, String> = state.roles.iter()
            .map(|s| (s.session.clone(), s.role.clone()))
            .collect();
        let tx2 = tx.clone();
        tokio::spawn(async move {
            let (ltx, mut lrx) = mpsc::channel(256);
            tokio::spawn(tailer::tail(log, ltx));
            while let Some(line) = lrx.recv().await {
                if let Some((session, text)) = events::parse_message_line(&line) {
                    let role = session_map.get(&session).cloned().unwrap_or(session);
                    if tx2.send(events::Payload::Message { role, text }).await.is_err() { break; }
                }
            }
        });
    }

    let stream = ReceiverStream::new(rx).map(|payload| {
        let name = events::event_name(&payload);
        let data = serde_json::to_string(&payload).unwrap();
        Ok::<Event, Infallible>(Event::default().event(name).data(data))
    });

    Sse::new(stream).keep_alive(axum::response::sse::KeepAlive::default())
}

#[derive(serde::Deserialize)]
struct SendBody { keys: String }

async fn send_handler(
    Path(role): Path<String>,
    State(state): State<AppState>,
    Json(body): Json<SendBody>,
) -> axum::http::StatusCode {
    let Some(session) = state.roles.iter().find(|s| s.role == role).map(|s| s.session.clone()) else {
        return axum::http::StatusCode::NOT_FOUND;
    };
    let target = tmux_target(&session);
    if !body.keys.is_empty() {
        std::process::Command::new("tmux")
            .args(["send-keys", "-t", &target, "-l", "--", &body.keys])
            .output().ok();
    }
    std::process::Command::new("tmux")
        .args(["send-keys", "-t", &target, "Enter"])
        .output().ok();
    axum::http::StatusCode::OK
}

const INDEX_HTML: &str = include_str!("index.html");

async fn index_handler() -> axum::response::Html<&'static str> {
    axum::response::Html(INDEX_HTML)
}

#[tokio::main]
async fn main() {
    let args = Args::parse();
    let sessions_path = args.working_dir.join(".swarmforge/sessions.tsv");
    let roles = Arc::new(sessions::parse(&sessions_path));

    println!("SwarmForge UI — {} agent(s) — http://localhost:{}", roles.len(), args.port);

    let state = AppState { roles, working_dir: args.working_dir };
    let app = Router::new()
        .route("/", get(index_handler))
        .route("/events", get(sse_handler))
        .route("/send/:role", post(send_handler))
        .with_state(state);

    let addr = format!("0.0.0.0:{}", args.port);
    let listener = tokio::net::TcpListener::bind(&addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}

mod events {
    use serde::Serialize;

    #[derive(Serialize, Clone, Debug)]
    #[serde(untagged)]
    pub enum Payload {
        Roles { roles: Vec<String> },
        Pane { role: String, text: String },
        Message { role: String, text: String },
    }

    pub fn event_name(payload: &Payload) -> &'static str {
        match payload {
            Payload::Roles { .. } => "roles",
            Payload::Pane { .. } => "pane",
            Payload::Message { .. } => "message",
        }
    }

    // Returns (session_name, text). Caller maps session_name → role.
    pub fn parse_message_line(line: &str) -> Option<(String, String)> {
        let after_ts = line.find("] [")?;
        let rest = &line[after_ts + 3..];
        let session_end = rest.find(']')?;
        let session = rest[..session_end].to_string();
        let text = rest[session_end + 2..].trim().to_string();
        Some((session, text))
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn serializes_roles() {
            let p = Payload::Roles { roles: vec!["coder".into()] };
            assert_eq!(serde_json::to_string(&p).unwrap(), r#"{"roles":["coder"]}"#);
        }

        #[test]
        fn serializes_pane() {
            let p = Payload::Pane { role: "coder".into(), text: "hi\r\n".into() };
            let s = serde_json::to_string(&p).unwrap();
            assert!(s.contains(r#""role":"coder""#));
        }

        #[test]
        fn event_names() {
            assert_eq!(event_name(&Payload::Roles { roles: vec![] }), "roles");
            assert_eq!(event_name(&Payload::Pane { role: "x".into(), text: "y".into() }), "pane");
            assert_eq!(event_name(&Payload::Message { role: "x".into(), text: "y".into() }), "message");
        }

        #[test]
        fn parses_message_log_line() {
            let line = "[2026-05-19 12:00:00] [swarmforge-myproject-coder] done, branch xyz";
            assert_eq!(
                parse_message_line(line),
                Some(("swarmforge-myproject-coder".to_string(), "done, branch xyz".to_string()))
            );
        }

        #[test]
        fn parse_returns_none_for_garbage() {
            assert_eq!(parse_message_line("not a log line"), None);
        }
    }
}

mod tailer {
    use std::path::PathBuf;
    use std::time::Duration;
    use tokio::io::{AsyncBufReadExt, BufReader};
    use tokio::sync::mpsc;
    use tokio::time;

    pub async fn tail(path: PathBuf, tx: mpsc::Sender<String>) {
        // Wait for file to exist (agents may start slightly after the UI)
        let deadline = time::Instant::now() + Duration::from_secs(10);
        while !path.exists() {
            if time::Instant::now() > deadline { return; }
            time::sleep(Duration::from_millis(500)).await;
        }

        let file = match tokio::fs::File::open(&path).await {
            Ok(f) => f,
            Err(_) => return,
        };
        let mut reader = BufReader::new(file);
        let mut line = String::new();

        loop {
            line.clear();
            match reader.read_line(&mut line).await {
                Ok(0) => time::sleep(Duration::from_millis(200)).await,
                Ok(_) => { if tx.send(line.clone()).await.is_err() { return; } }
                Err(_) => return,
            }
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;
        use std::io::Write;

        #[tokio::test]
        async fn reads_existing_lines() {
            let mut f = tempfile::NamedTempFile::new().unwrap();
            writeln!(f, "line one").unwrap();
            writeln!(f, "line two").unwrap();
            f.flush().unwrap();

            let (tx, mut rx) = mpsc::channel(10);
            tokio::spawn(tail(f.path().to_path_buf(), tx));

            let l1 = time::timeout(Duration::from_secs(2), rx.recv()).await
                .expect("timeout").expect("closed");
            let l2 = time::timeout(Duration::from_secs(2), rx.recv()).await
                .expect("timeout").expect("closed");

            assert_eq!(l1.trim_end(), "line one");
            assert_eq!(l2.trim_end(), "line two");
        }

        #[tokio::test]
        async fn picks_up_appended_lines() {
            let f = tempfile::NamedTempFile::new().unwrap();
            let path = f.path().to_path_buf();

            let (tx, mut rx) = mpsc::channel(10);
            tokio::spawn(tail(path.clone(), tx));
            time::sleep(Duration::from_millis(100)).await;

            let mut file = std::fs::OpenOptions::new().append(true).open(&path).unwrap();
            writeln!(file, "appended").unwrap();

            let line = time::timeout(Duration::from_secs(2), rx.recv()).await
                .expect("timeout").expect("closed");
            assert_eq!(line.trim_end(), "appended");
        }
    }
}

mod sessions {
    use std::path::Path;

    #[derive(Clone)]
    pub struct Session {
        pub role: String,
        pub session: String,
    }

    pub fn parse(path: &Path) -> Vec<Session> {
        let content = std::fs::read_to_string(path).unwrap_or_default();
        content.lines().filter_map(|line| {
            let cols: Vec<&str> = line.splitn(5, '\t').collect();
            if cols.len() >= 3 {
                Some(Session { role: cols[1].to_string(), session: cols[2].to_string() })
            } else {
                None
            }
        }).collect()
    }

    #[cfg(test)]
    mod tests {
        use super::*;
        use std::io::Write;

        #[test]
        fn parses_sessions_tsv() {
            let mut f = tempfile::NamedTempFile::new().unwrap();
            writeln!(f, "1\tspecifier\tswarmforge-specifier\tSpecifier\tcodex").unwrap();
            writeln!(f, "2\tcoder\tswarmforge-coder\tCoder\tcodex").unwrap();
            let s = parse(f.path());
            assert_eq!(s.len(), 2);
            assert_eq!(s[0].role, "specifier");
            assert_eq!(s[0].session, "swarmforge-specifier");
            assert_eq!(s[1].role, "coder");
        }

        #[test]
        fn skips_malformed_lines() {
            let mut f = tempfile::NamedTempFile::new().unwrap();
            writeln!(f, "bad line").unwrap();
            writeln!(f, "1\tcoder\tswarmforge-coder\tCoder\tcodex").unwrap();
            let s = parse(f.path());
            assert_eq!(s.len(), 1);
        }
    }
}
