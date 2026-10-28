//! Resource access shared by loose APK assets and in-memory Zstd archives.
use std::collections::HashMap;

pub fn normalize_resource_path(path: &str) -> String {
    let path = path.replace('\\', "/");
    let (prefix, rest) = if let Some(rest) = path.strip_prefix("archive://") {
        ("archive://", rest)
    } else if let Some(rest) = path.strip_prefix('/') {
        ("/", rest)
    } else {
        ("", path.as_str())
    };
    let mut parts = Vec::new();
    for part in rest.split('/') {
        match part {
            "" | "." => {}
            ".." if parts.last().is_some_and(|part| *part != "..") => {
                parts.pop();
            }
            ".." if prefix.is_empty() => parts.push(part),
            ".." => {}
            _ => parts.push(part),
        }
    }
    format!("{prefix}{}", parts.join("/"))
}

pub struct Resources {
    directory: String,
    files: HashMap<String, Vec<u8>>,
}

impl Resources {
    pub fn new(model_path: &str, files: HashMap<String, Vec<u8>>) -> Self {
        let path = normalize_resource_path(model_path);
        Self {
            directory: path.rsplit_once('/').map_or(".", |(dir, _)| dir).to_owned(),
            files: files
                .into_iter()
                .map(|(path, bytes)| (normalize_resource_path(&path), bytes))
                .collect(),
        }
    }

    pub fn resolve(&self, file: &str) -> String {
        normalize_resource_path(&format!("{}/{file}", self.directory))
    }

    pub fn read(&self, path: &str) -> Result<Vec<u8>, String> {
        let path = normalize_resource_path(path);
        if let Some(bytes) = self.files.get(&path) {
            return Ok(bytes.clone());
        }
        if path.starts_with("archive://") {
            return Err(format!("模型归档中缺少资源: {path}"));
        }
        std::fs::read(&path).map_err(|error| format!("读取 {path} 失败: {error}"))
    }

    // Binary models and decoded PNGs are no longer needed in the archive map.
    pub fn take(&mut self, path: &str) -> Result<Vec<u8>, String> {
        let path = normalize_resource_path(path);
        self.files
            .remove(&path)
            .map_or_else(|| self.read(&path), Ok)
    }

    pub fn text(&self, path: &str) -> Result<String, String> {
        String::from_utf8(self.read(path)?)
            .map_err(|error| format!("资源 {path} 不是 UTF-8: {error}"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn archive_paths_preserve_scheme_and_resolve_relative_references() {
        let store = Resources::new("archive://香澄/costume/model.json", HashMap::new());
        assert_eq!(
            store.resolve(".\\textures\\../face.png"),
            "archive://香澄/costume/face.png"
        );
        assert_eq!(
            store.resolve("../shared/idle.mtn"),
            "archive://香澄/shared/idle.mtn"
        );
        assert!(
            store
                .read(&store.resolve("missing.png"))
                .unwrap_err()
                .contains("归档")
        );
    }

    #[test]
    fn consumed_resources_are_released_without_affecting_other_files() {
        let mut store = Resources::new(
            "archive://a/model.json",
            HashMap::from([
                ("archive://a/texture.png".into(), vec![1, 2]),
                ("archive://a/idle.mtn".into(), vec![3]),
            ]),
        );
        assert_eq!(store.take("archive://a/texture.png").unwrap(), [1, 2]);
        assert!(store.read("archive://a/texture.png").is_err());
        assert_eq!(store.read("archive://a/idle.mtn").unwrap(), [3]);
    }
}
