//! Keep the Android motion tag and seven touch-region conventions.
use std::collections::HashMap;

const ACTION_ORDER: &[&str] = &[
    "surprised01",
    "shame01",
    "pui01",
    "smile01",
    "kandou01",
    "kime01",
    "nf_left01",
    "nnf_left01",
    "nf_right01",
    "nnf_right01",
    "nf01",
    "nnf01",
    "angry01",
    "sad01",
    "tap_body",
    "tap_head",
    "bye01",
];

pub fn is_idle(group: &str) -> bool {
    group.to_ascii_lowercase().contains("idle")
}

pub fn default_group(groups: &[String]) -> Option<String> {
    groups
        .iter()
        .find(|g| matches!(g.to_ascii_lowercase().as_str(), "idle" | "idle01"))
        .or_else(|| groups.iter().find(|g| is_idle(g)))
        .cloned()
}

pub fn motion_matches(name: &str, tag: &str) -> bool {
    let name = name.replace('\\', "/").to_ascii_lowercase();
    let mut base = name.rsplit('/').next().unwrap_or(&name);
    for suffix in [".motion3.json", ".motion.json", ".mtn"] {
        base = base.strip_suffix(suffix).unwrap_or(base);
    }
    base = base.strip_prefix("mtn_").unwrap_or(base);
    let mut side = None;
    for (suffix, direction) in [("_l", "left"), ("_c", "center"), ("_r", "right")] {
        if let Some(value) = base.strip_suffix(suffix) {
            base = value;
            side = Some(direction);
            break;
        }
    }
    base = base.trim_end_matches(|c: char| c.is_ascii_digit());
    let tag = tag.to_ascii_lowercase();
    for direction in ["left", "center", "right"] {
        if let Some(tag_base) = tag.strip_suffix(&format!("_{direction}")) {
            return base == tag || (base == tag_base && side == Some(direction));
        }
    }
    base == tag
}

pub fn touch_region(x: f32, y: f32, hit_head: bool) -> &'static str {
    if hit_head || y < 0.38 {
        return "head";
    }
    match (y < 0.64, x < 1.0 / 3.0, x < 2.0 / 3.0) {
        (true, true, _) => "upper_body_left",
        (true, false, true) => "upper_body_center",
        (true, false, false) => "upper_body_right",
        (false, true, _) => "lower_body_left",
        (false, false, true) => "lower_body_center",
        (false, false, false) => "lower_body_right",
    }
}

pub fn touch_buckets(region: &str) -> [&'static [&'static str]; 2] {
    match region {
        "upper_body_left" => [&["nf_left", "nnf_left"], &["shame", "surprised", "smile"]],
        "upper_body_center" => [
            &["smile", "kime", "surprised", "shame"],
            &["angry", "pui", "nf"],
        ],
        "upper_body_right" => [&["nf_right", "nnf_right"], &["shame", "surprised", "smile"]],
        "lower_body_left" => [&["nf_left", "nnf_left"], &["surprised", "sad", "smile"]],
        "lower_body_center" => [&["shame", "surprised", "angry"], &["smile", "kime"]],
        "lower_body_right" => [&["nf_right", "nnf_right"], &["surprised", "sad", "smile"]],
        _ => [
            &["surprised", "shame", "pui", "smile"],
            &["kandou", "kime", "nf"],
        ],
    }
}

pub struct Actions {
    groups: Vec<String>,
    indices: HashMap<String, usize>,
}

impl Actions {
    pub fn new(groups: &[String]) -> Self {
        let mut groups: Vec<_> = groups.iter().filter(|g| !is_idle(g)).cloned().collect();
        groups.sort_by(|a, b| {
            let rank = |s: &String| {
                ACTION_ORDER
                    .iter()
                    .position(|name| *name == s)
                    .unwrap_or(usize::MAX)
            };
            rank(a).cmp(&rank(b)).then_with(|| a.cmp(b))
        });
        Self {
            groups,
            indices: HashMap::new(),
        }
    }

    // Return a rotated candidate list so a missing/broken motion can be skipped.
    pub fn candidates(&mut self, tags: &[&str], key: &str) -> Vec<String> {
        let mut candidates = Vec::new();
        for tag in tags {
            for group in &self.groups {
                if (tags.is_empty() || motion_matches(group, tag)) && !candidates.contains(group) {
                    candidates.push(group.clone());
                }
            }
        }
        if tags.is_empty() {
            candidates.clone_from(&self.groups);
        }
        if !candidates.is_empty() {
            let index = self.indices.entry(key.to_owned()).or_default();
            let count = candidates.len();
            candidates.rotate_left(*index % count);
            *index = (*index + 1) % count;
        }
        candidates
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn directional_tags_match_both_model_generations() {
        assert!(motion_matches("nf_left01", "nf_left"));
        assert!(motion_matches("motions/mtn_nf01_L.motion3.json", "nf_left"));
        assert!(!motion_matches("mtn_nf01_R", "nf_left"));
        assert!(motion_matches("smile01", "smile"));
        assert!(!motion_matches("nnf01", "nf"));
    }

    #[test]
    fn region_edges_and_named_head_hits_are_preserved() {
        assert_eq!(touch_region(0.9, 0.8, true), "head");
        assert_eq!(touch_region(0.0, 0.379, false), "head");
        assert_eq!(touch_region(0.1, 0.38, false), "upper_body_left");
        assert_eq!(touch_region(0.5, 0.64, false), "lower_body_center");
        assert_eq!(touch_region(0.9, 0.9, false), "lower_body_right");
    }

    #[test]
    fn idle_is_excluded_from_actions_and_candidates_cycle() {
        let groups = ["idle01", "smile02", "smile01"].map(str::to_owned);
        assert_eq!(default_group(&groups).as_deref(), Some("idle01"));
        let mut actions = Actions::new(&groups);
        assert_eq!(
            actions.candidates(&["smile"], "test"),
            ["smile01", "smile02"]
        );
        assert_eq!(
            actions.candidates(&["smile"], "test"),
            ["smile02", "smile01"]
        );
    }
}
