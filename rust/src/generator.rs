use crate::config::{DEFAULT_CHARSET, ScannerConfig};
use anyhow::{Context, Result, bail};
use rand::{Rng, SeedableRng, rngs::StdRng};
use std::{fs, path::Path};

pub enum UsernameGenerator {
    Random {
        length: usize,
        charset: Vec<char>,
        rng: Box<StdRng>,
    },
    Sequential(ProductGenerator),
    Pattern(ProductGenerator),
    Dictionary {
        values: Vec<String>,
        index: usize,
    },
}

impl UsernameGenerator {
    pub fn from_config(config: &ScannerConfig, root: &Path) -> Result<Self> {
        match config.mode.as_str() {
            "random" => {
                let charset: Vec<char> = config.charset.chars().collect();
                if charset.is_empty() {
                    bail!("charset must not be empty");
                }
                let rng = match config.seed {
                    Some(seed) => StdRng::seed_from_u64(seed),
                    None => StdRng::from_entropy(),
                };
                Ok(Self::Random {
                    length: config.length,
                    charset,
                    rng: Box::new(rng),
                })
            }
            "sequential" => {
                let alphabet: Vec<char> = config.charset.chars().collect();
                if alphabet.is_empty() {
                    bail!("charset must not be empty");
                }
                Ok(Self::Sequential(ProductGenerator::new(vec![
                    alphabet;
                    config.length
                ])?))
            }
            "pattern" => {
                let custom: Vec<char> = config.charset.chars().collect();
                let mut alphabets = Vec::with_capacity(config.pattern.chars().count());
                for token in config.pattern.chars() {
                    let alphabet = match token {
                        '@' => ('a'..='z').collect(),
                        '#' => ('0'..='9').collect(),
                        '*' => custom.clone(),
                        literal => vec![literal],
                    };
                    if alphabet.is_empty() {
                        bail!("pattern alphabet must not be empty");
                    }
                    alphabets.push(alphabet);
                }
                if !(2..=32).contains(&alphabets.len()) {
                    bail!("pattern length must be between 2 and 32");
                }
                Ok(Self::Pattern(ProductGenerator::new(alphabets)?))
            }
            "dictionary" => {
                let path = root.join(&config.dictionary_file);
                let text = fs::read_to_string(&path)
                    .with_context(|| format!("failed to read dictionary {}", path.display()))?;
                let values = text
                    .lines()
                    .map(str::trim)
                    .map(str::to_lowercase)
                    .filter(|value| (2..=32).contains(&value.chars().count()))
                    .collect();
                Ok(Self::Dictionary { values, index: 0 })
            }
            other => bail!("unsupported scanner mode: {other}"),
        }
    }
}

impl Iterator for UsernameGenerator {
    type Item = String;

    fn next(&mut self) -> Option<Self::Item> {
        match self {
            Self::Random {
                length,
                charset,
                rng,
            } => Some(
                (0..*length)
                    .map(|_| charset[rng.gen_range(0..charset.len())])
                    .collect(),
            ),
            Self::Sequential(generator) | Self::Pattern(generator) => generator.next(),
            Self::Dictionary { values, index } => {
                let value = values.get(*index)?.clone();
                *index += 1;
                Some(value)
            }
        }
    }
}

pub struct ProductGenerator {
    alphabets: Vec<Vec<char>>,
    indices: Vec<usize>,
    first: bool,
    done: bool,
}

impl ProductGenerator {
    fn new(alphabets: Vec<Vec<char>>) -> Result<Self> {
        if alphabets.is_empty() || alphabets.iter().any(Vec::is_empty) {
            bail!("product alphabets must not be empty");
        }
        Ok(Self {
            indices: vec![0; alphabets.len()],
            alphabets,
            first: true,
            done: false,
        })
    }

    fn current(&self) -> String {
        self.alphabets
            .iter()
            .zip(&self.indices)
            .map(|(alphabet, index)| alphabet[*index])
            .collect()
    }

    fn advance(&mut self) {
        for position in (0..self.indices.len()).rev() {
            self.indices[position] += 1;
            if self.indices[position] < self.alphabets[position].len() {
                return;
            }
            self.indices[position] = 0;
        }
        self.done = true;
    }
}

impl Iterator for ProductGenerator {
    type Item = String;

    fn next(&mut self) -> Option<Self::Item> {
        if self.done {
            return None;
        }
        if self.first {
            self.first = false;
            return Some(self.current());
        }
        self.advance();
        if self.done {
            None
        } else {
            Some(self.current())
        }
    }
}

pub fn default_charset() -> &'static str {
    DEFAULT_CHARSET
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::ScannerConfig;
    use tempfile::tempdir;

    #[test]
    fn sequential_is_stable() {
        let mut config = ScannerConfig::default();
        config.mode = "sequential".into();
        config.length = 2;
        config.charset = "ab".into();
        let root = tempdir().unwrap();
        let values: Vec<_> = UsernameGenerator::from_config(&config, root.path())
            .unwrap()
            .take(4)
            .collect();
        assert_eq!(values, ["aa", "ab", "ba", "bb"]);
    }

    #[test]
    fn pattern_matches_reference_tokens() {
        let mut config = ScannerConfig::default();
        config.mode = "pattern".into();
        config.pattern = "a#".into();
        let root = tempdir().unwrap();
        let values: Vec<_> = UsernameGenerator::from_config(&config, root.path())
            .unwrap()
            .take(3)
            .collect();
        assert_eq!(values, ["a0", "a1", "a2"]);
    }
}
