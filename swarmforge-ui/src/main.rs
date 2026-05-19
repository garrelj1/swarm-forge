fn main() {
    println!("swarmforge-ui");
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
