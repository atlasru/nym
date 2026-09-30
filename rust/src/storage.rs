use crate::model::{CheckResult, CheckStatus};
use anyhow::{Context, Result};
use rusqlite::{Connection, params};
use std::{
    collections::HashSet,
    fs::{self, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
};

const SCHEMA: &str = r#"
PRAGMA journal_mode=WAL;
PRAGMA synchronous=NORMAL;

CREATE TABLE IF NOT EXISTS results (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL,
    status TEXT NOT NULL,
    checked_at TEXT NOT NULL,
    http_status INTEGER,
    retry_after REAL,
    error TEXT
);

CREATE INDEX IF NOT EXISTS idx_results_username ON results(username);
CREATE INDEX IF NOT EXISTS idx_results_status ON results(status);

CREATE TABLE IF NOT EXISTS checked_usernames (
    username TEXT PRIMARY KEY,
    last_status TEXT NOT NULL,
    checked_at TEXT NOT NULL
);
"#;

pub struct Storage {
    conn: Connection,
    available_file: PathBuf,
}

impl Storage {
    pub fn open(root: &Path) -> Result<Self> {
        let data_dir = root.join("data");
        fs::create_dir_all(&data_dir)
            .with_context(|| format!("failed to create {}", data_dir.display()))?;
        let database = data_dir.join("nym.db");
        let conn = Connection::open(&database)
            .with_context(|| format!("failed to open {}", database.display()))?;
        conn.execute_batch(SCHEMA).context("failed to initialize sqlite schema")?;
        Ok(Self {
            conn,
            available_file: root.join("available.txt"),
        })
    }

    pub fn load_checked(&self) -> Result<HashSet<String>> {
        let mut stmt = self
            .conn
            .prepare("SELECT username FROM checked_usernames")
            .context("failed to prepare checked username query")?;
        let rows = stmt
            .query_map([], |row| row.get::<_, String>(0))
            .context("failed to query checked usernames")?;
        let mut checked = HashSet::new();
        for row in rows {
            checked.insert(row.context("failed to decode checked username")?);
        }
        Ok(checked)
    }

    pub fn save(&mut self, result: &CheckResult) -> Result<()> {
        let checked_at = result.checked_at.to_rfc3339();
        let transaction = self
            .conn
            .transaction()
            .context("failed to begin result transaction")?;
        transaction
            .execute(
                "INSERT INTO results (username, status, checked_at, http_status, retry_after, error) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                params![
                    &result.username,
                    result.status.as_str(),
                    &checked_at,
                    result.http_status,
                    result.retry_after,
                    result.error.as_deref(),
                ],
            )
            .context("failed to insert result")?;

        if matches!(
            result.status,
            CheckStatus::Available | CheckStatus::Taken | CheckStatus::Invalid
        ) {
            transaction
                .execute(
                    "INSERT INTO checked_usernames (username, last_status, checked_at) VALUES (?1, ?2, ?3) ON CONFLICT(username) DO UPDATE SET last_status = excluded.last_status, checked_at = excluded.checked_at",
                    params![&result.username, result.status.as_str(), &checked_at],
                )
                .context("failed to upsert checked username")?;
        }

        transaction.commit().context("failed to commit result")?;

        if result.status == CheckStatus::Available {
            let mut file = OpenOptions::new()
                .create(true)
                .append(true)
                .open(&self.available_file)
                .with_context(|| format!("failed to open {}", self.available_file.display()))?;
            writeln!(file, "{}", result.username).context("failed to append available username")?;
            file.flush().context("failed to flush available username")?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::CheckResult;
    use tempfile::tempdir;

    #[test]
    fn stores_final_results_and_available_file() {
        let root = tempdir().unwrap();
        let mut storage = Storage::open(root.path()).unwrap();
        let result = CheckResult::new("nym_test", CheckStatus::Available);
        storage.save(&result).unwrap();
        assert!(storage.load_checked().unwrap().contains("nym_test"));
        assert_eq!(
            fs::read_to_string(root.path().join("available.txt")).unwrap(),
            "nym_test\n"
        );
    }
}
