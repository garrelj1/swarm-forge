fn main() {
    println!("swarmforge-ui");
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

    pub fn parse_message_line(line: &str) -> Option<(String, String)> {
        // Format: [timestamp] [swarmforge-role] message text
        let after_ts = line.find("] [")?;
        let rest = &line[after_ts + 3..];
        let session_end = rest.find(']')?;
        let session = &rest[..session_end];
        let text = rest[session_end + 2..].trim().to_string();
        let role = session.strip_prefix("swarmforge-").unwrap_or(session).to_string();
        Some((role, text))
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
            let line = "[2026-05-19 12:00:00] [swarmforge-coder] done, branch xyz";
            assert_eq!(
                parse_message_line(line),
                Some(("coder".to_string(), "done, branch xyz".to_string()))
            );
        }

        #[test]
        fn parse_returns_none_for_garbage() {
            assert_eq!(parse_message_line("not a log line"), None);
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
