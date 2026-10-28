//! Neocari animation adapter, following BANDORI-PET-LITE's frame ordering.
//! Load through Resources so APK assets and archive:// models use the same path.
use std::collections::{BTreeMap, HashMap};
use std::sync::Arc;

use neocari::assets::DecodedTexture;
use neocari::expression::ExpressionManager;
use neocari::json::{Expression3, Model3, Motion3, Physics3, Pose3};
use neocari::moc2::{
    Moc2Expression, Moc2Model, Moc2Motion, Moc2Physics, Moc2Pose, Moc2Runtime, Moc2Settings,
};
use neocari::moc3::*;
use neocari::motion::MotionPlayer;
use neocari::runtime::ModelRuntime;

use crate::interaction::{self, Actions};
use crate::resources::Resources;

fn message(error: impl std::fmt::Display) -> String {
    error.to_string()
}

struct MotionRef {
    file: String,
    fade_in: f32,
    fade_out: f32,
}

enum Runtime {
    Moc2(Box<Moc2Runtime>),
    Moc3(Box<Moc3Animation>),
}

struct Moc3Animation {
    runtime: ModelRuntime,
    player: Option<MotionPlayer>,
    expressions: ExpressionManager,
    eye_ids: Vec<String>,
    time: f32,
    fade_from: Vec<f32>,
    fade_parts: Vec<Option<f32>>,
    fade_elapsed: f32,
}

impl Moc3Animation {
    fn begin_fade(&mut self) {
        self.fade_from = self.runtime.parameter_values().to_vec();
        self.fade_parts = (0..self.runtime.part_count())
            .map(|i| self.runtime.part_opacity_override_by_index(i))
            .collect();
        self.fade_elapsed = 0.0;
    }

    fn update(&mut self, dt: f32, drag: (f32, f32), mouth: (f32, f32)) -> Option<()> {
        self.time += dt;
        let runtime = &mut self.runtime;
        runtime.reset_parameters();
        runtime.reset_part_opacities();
        if let Some(player) = self.player.as_mut() {
            player.tick(dt);
            player.apply(runtime);
        }
        self.expressions.tick(dt);
        self.expressions.apply(runtime);
        // MotionPlayer applies clips at full weight. Ease from the visible pose,
        // including part opacity, before drag/blink/lip overrides and physics.
        self.fade_elapsed += dt;
        if self.fade_elapsed < 0.28 {
            let weight = neocari::json::easing_sine(self.fade_elapsed / 0.28);
            for (i, from) in self.fade_from.iter().enumerate() {
                if let Some(live) = runtime.parameter_value_by_index(i) {
                    runtime.set_parameter_by_index(i, from + (live - from) * weight);
                }
            }
            for (i, from) in self.fade_parts.iter().enumerate() {
                let live = runtime.part_opacity_override_by_index(i).unwrap_or(1.0);
                let from = from.unwrap_or(1.0);
                runtime.set_part_opacity_by_index(i, from + (live - from) * weight);
            }
        }
        // Screen y grows down; Cubism drag y grows up.
        let (x, y) = drag;
        let phase = self.time * std::f32::consts::TAU;
        for (id, value) in [
            ("ParamAngleX", x * 30.0),
            ("ParamAngleY", y * 30.0),
            ("ParamAngleZ", -x * y * 10.0),
            ("ParamBodyAngleX", x * 9.0),
            ("ParamEyeBallX", x),
            ("ParamEyeBallY", y),
            ("ParamBreath", 0.5 + 0.5 * (phase / 3.2).sin()),
            ("ParamMouthOpenY", mouth.0),
            ("ParamMouthForm", mouth.1),
        ] {
            runtime.set_parameter_override(id, value);
        }
        // A brief close/open cycle every four seconds, independent of frame rate.
        let blink_phase = self.time % 4.0;
        let eye_open = if blink_phase < 0.18 {
            ((blink_phase - 0.09).abs() / 0.09).min(1.0)
        } else {
            1.0
        };
        for id in &self.eye_ids {
            runtime.set_parameter_override(id, eye_open);
        }
        runtime.apply_parameter_overrides();
        runtime.apply_physics(dt);
        runtime.apply_pose(dt);
        runtime.update_meshes()
    }
}

pub struct PetModel {
    runtime: Runtime,
    resources: Resources,
    textures: Vec<DecodedTexture>,
    motions: BTreeMap<String, MotionRef>,
    expressions: BTreeMap<String, String>,
    moc2_motions: HashMap<String, Arc<Moc2Motion>>,
    moc3_motions: HashMap<String, Motion3>,
    actions: Actions,
    idle: Option<String>,
    hit_areas: Vec<(String, usize)>,
    drag: (f32, f32),
    bounds: [f32; 4],
}

impl PetModel {
    pub fn load(path: &str, files: HashMap<String, Vec<u8>>) -> Result<Self, String> {
        let mut resources = Resources::new(path, files);
        let source = resources.text(path)?;
        let mut motions = BTreeMap::new();
        let mut expressions = BTreeMap::new();
        let mut hit_areas = Vec::new();
        let (runtime, texture_files) = if path.to_ascii_lowercase().ends_with(".model3.json") {
            let model = Model3::from_json_str(&source).map_err(message)?;
            let bytes = resources.take(&resources.resolve(model.moc()))?;
            let meshes = Moc3ArtMeshes::parse(&bytes).map_err(message)?;
            let keyforms = Moc3ArtMeshKeyforms::parse(&bytes).map_err(message)?;
            let deformers =
                Moc3Deformers::parse_with_positions(&bytes, Some(keyforms.shared_position_xys()))
                    .map_err(message)?;
            let bindings = Moc3KeyformBindings::parse(&bytes).map_err(message)?;
            let ids = Moc3Ids::parse(&bytes).map_err(message)?;
            let offscreen = Moc3OffscreenInfo::parse(&bytes).map_err(message)?;
            let glues = Moc3Glues::parse(&bytes).map_err(message)?;
            let parts = Moc3Parts::parse(&bytes).map_err(message)?;
            let canvas = Moc3CanvasInfo::parse(&bytes).map_err(message)?;
            let orders = Moc3DrawOrderGroups::parse(&bytes).map_err(message)?;
            drop(bytes);
            let pose = model
                .pose()
                .map(|file| {
                    Pose3::from_json_str(&resources.text(&resources.resolve(file))?)
                        .map_err(message)
                })
                .transpose()?;
            let physics = model
                .physics()
                .map(|file| {
                    Physics3::from_json_str(&resources.text(&resources.resolve(file))?)
                        .map_err(message)
                })
                .transpose()?;
            let texture_files = model.textures().to_vec();
            for (group, refs) in model.motions() {
                if let Some(reference) = refs.first() {
                    motions.insert(
                        group.clone(),
                        MotionRef {
                            file: reference.file().to_owned(),
                            fade_in: 500.0,
                            fade_out: 500.0,
                        },
                    );
                }
            }
            for reference in model.expressions() {
                expressions.insert(reference.name().to_owned(), reference.file().to_owned());
            }
            let mut eye_ids: Vec<_> = model
                .groups()
                .iter()
                .filter(|g| g.name().eq_ignore_ascii_case("EyeBlink"))
                .flat_map(|g| g.ids().iter().cloned())
                .collect();
            if eye_ids.is_empty() {
                eye_ids = vec!["ParamEyeLOpen".into(), "ParamEyeROpen".into()];
            }
            let mut runtime = ModelRuntime::new(
                model, canvas, meshes, keyforms, deformers, bindings, ids, offscreen, glues, parts,
                orders, pose,
            )
            .ok_or("Neocari 无法生成 MOC3 网格")?;
            if let Some(physics) = physics {
                runtime.set_physics(physics);
            }
            for area in runtime.model().hit_areas() {
                if let Some(index) = runtime.drawable_index(area.id()) {
                    hit_areas.push((area.name().to_owned(), index));
                }
            }
            (
                Runtime::Moc3(Box::new(Moc3Animation {
                    runtime,
                    player: None,
                    expressions: ExpressionManager::new(),
                    eye_ids,
                    time: 0.0,
                    fade_from: Vec::new(),
                    fade_parts: Vec::new(),
                    fade_elapsed: 1.0,
                })),
                texture_files,
            )
        } else {
            let settings = Moc2Settings::from_json_str(&source).map_err(message)?;
            let file = settings.model_file().ok_or("MOC2 配置缺少 model 字段")?;
            let bytes = resources.take(&resources.resolve(file))?;
            let model = Moc2Model::from_bytes(&bytes).map_err(message)?;
            drop(bytes);
            let mut runtime =
                Moc2Runtime::new(model.into_scene()).ok_or("Neocari 无法生成 MOC2 网格")?;
            for param in settings.init_params() {
                runtime.set_parameter(param.id(), param.value());
            }
            for part in settings.init_parts_visible() {
                runtime.set_part_opacity(part.id(), part.value());
            }
            if let Some(file) = settings.physics_file() {
                runtime.set_physics(
                    Moc2Physics::from_json_str(&resources.text(&resources.resolve(file))?)
                        .map_err(message)?,
                );
            }
            if let Some(file) = settings.pose_file() {
                runtime.set_pose(
                    Moc2Pose::from_json_str(&resources.text(&resources.resolve(file))?)
                        .map_err(message)?,
                );
            }
            runtime.set_layout(settings.layout().clone());
            runtime.save_parameters();
            runtime
                .update_meshes()
                .ok_or("Neocari 无法初始化 MOC2 网格")?;
            for (group, refs) in settings.motion_groups() {
                if let Some(reference) = refs.first().filter(|r| r.file().is_some()) {
                    motions.insert(
                        group.clone(),
                        MotionRef {
                            file: reference.file().unwrap().to_owned(),
                            fade_in: reference.fade_in_ms(),
                            fade_out: reference.fade_out_ms(),
                        },
                    );
                }
            }
            for reference in settings.expressions() {
                expressions.insert(reference.name().to_owned(), reference.file().to_owned());
            }
            for area in settings.hit_areas() {
                if let Some(index) = runtime.scene().drawable_index(area.id()) {
                    hit_areas.push((area.name().to_owned(), index));
                }
            }
            (
                Runtime::Moc2(Box::new(runtime)),
                settings.texture_files().to_vec(),
            )
        };
        let mut textures = Vec::with_capacity(texture_files.len());
        for file in texture_files {
            let path = resources.resolve(&file);
            let bytes = resources.take(&path)?;
            let rgba = image::load_from_memory_with_format(&bytes, image::ImageFormat::Png)
                .map_err(|error| format!("解码 {path} 失败: {error}"))?
                .into_rgba8();
            textures.push(DecodedTexture::new(
                rgba.width(),
                rgba.height(),
                rgba.into_raw(),
            ));
        }
        let groups: Vec<_> = motions.keys().cloned().collect();
        let mut model = Self {
            runtime,
            resources,
            textures,
            motions,
            expressions,
            moc2_motions: HashMap::new(),
            moc3_motions: HashMap::new(),
            actions: Actions::new(&groups),
            idle: interaction::default_group(&groups),
            hit_areas,
            drag: (0.0, 0.0),
            bounds: [0.0, 0.0, 1.0, 1.0],
        };
        model.bounds = mesh_bounds(model.meshes()).ok_or("模型没有可见网格")?;
        model.start_idle()?;
        Ok(model)
    }

    pub fn meshes(&self) -> &[Moc3DrawableMesh] {
        match &self.runtime {
            Runtime::Moc2(runtime) => runtime.meshes(),
            Runtime::Moc3(animation) => animation.runtime.meshes(),
        }
    }

    pub fn take_textures(&mut self) -> Vec<DecodedTexture> {
        std::mem::take(&mut self.textures)
    }
    pub fn bounds(&self) -> [f32; 4] {
        self.bounds
    }
    pub fn flips_y(&self) -> bool {
        matches!(self.runtime, Runtime::Moc2(_))
    }
    pub fn look_at(&mut self, x: f32, y: f32) {
        self.drag = ((x - 0.5) * 2.0, (0.5 - y) * 2.0);
    }

    fn start_motion(&mut self, group: &str, looping: bool) -> Result<bool, String> {
        let Some(reference) = self.motions.get(group) else {
            return Ok(false);
        };
        let path = self.resources.resolve(&reference.file);
        match &mut self.runtime {
            Runtime::Moc2(runtime) => {
                let motion = match self.moc2_motions.get(group) {
                    Some(motion) => Arc::clone(motion),
                    None => {
                        let motion = Arc::new(
                            Moc2Motion::from_bytes(&self.resources.read(&path)?)
                                .map_err(message)?,
                        );
                        self.moc2_motions
                            .insert(group.to_owned(), Arc::clone(&motion));
                        motion
                    }
                };
                runtime.start_motion(motion, reference.fade_in, reference.fade_out);
            }
            Runtime::Moc3(animation) => {
                let motion = match self.moc3_motions.get(group) {
                    Some(motion) => motion.clone(),
                    None => {
                        let motion = Motion3::from_json_str(&self.resources.text(&path)?)
                            .map_err(message)?;
                        self.moc3_motions.insert(group.to_owned(), motion.clone());
                        motion
                    }
                };
                animation.begin_fade();
                animation.player = Some(MotionPlayer::with_looping(motion, looping));
            }
        }
        Ok(true)
    }

    fn start_idle(&mut self) -> Result<(), String> {
        if let Some(group) = self.idle.clone() {
            self.start_motion(&group, true)?;
        }
        Ok(())
    }

    fn try_candidates(&mut self, tags: &[&str], key: &str) -> Result<bool, String> {
        let mut error = None;
        for group in self.actions.candidates(tags, key) {
            match self.start_motion(&group, false) {
                Ok(true) => return Ok(true),
                Ok(false) => {}
                Err(value) => error = Some(value),
            }
        }
        error.map_or(Ok(false), Err)
    }

    pub fn action(&mut self, tag: &str) -> Result<(), String> {
        let tag = tag.trim().trim_start_matches('[').trim_end_matches(']');
        let name = tag.strip_suffix(".exp").unwrap_or(tag);
        let expression = self
            .expressions
            .iter()
            .find(|(key, _)| key.eq_ignore_ascii_case(tag) || key.eq_ignore_ascii_case(name));
        if let Some((_, file)) = expression {
            let source = self.resources.text(&self.resources.resolve(file))?;
            match &mut self.runtime {
                Runtime::Moc2(runtime) => runtime.start_expression(Arc::new(
                    Moc2Expression::from_json_str(&source).map_err(message)?,
                )),
                Runtime::Moc3(animation) => animation
                    .expressions
                    .play(Expression3::from_json_str(&source).map_err(message)?),
            }
        } else if !tag.is_empty() {
            self.try_candidates(&[tag], &format!("llm:{tag}"))?;
        }
        Ok(())
    }

    pub fn touch(&mut self, x: f32, y: f32, point: [f32; 2]) -> Result<(), String> {
        let head = self.hit_areas.iter().any(|(name, index)| {
            let name = name.to_ascii_lowercase();
            (name.contains("head") || name.contains("face"))
                && self
                    .meshes()
                    .get(*index)
                    .and_then(|mesh| mesh_bounds(std::slice::from_ref(mesh)))
                    .is_some_and(|b| {
                        point[0] >= b[0] && point[0] <= b[2] && point[1] >= b[1] && point[1] <= b[3]
                    })
        });
        let region = interaction::touch_region(x, y, head);
        for (index, tags) in interaction::touch_buckets(region).iter().enumerate() {
            if self.try_candidates(tags, &format!("{region}:{index}"))? {
                return Ok(());
            }
        }
        self.try_candidates(&[], "fallback")?;
        Ok(())
    }

    pub fn update(&mut self, dt: f32, mouth: (f32, f32)) -> Result<(), String> {
        let finished = match &mut self.runtime {
            Runtime::Moc2(runtime) => {
                runtime.set_drag(self.drag.0, self.drag.1);
                runtime.set_parameter("PARAM_MOUTH_OPEN_Y", 0.0);
                runtime.set_parameter("PARAM_MOUTH_FORM", 0.0);
                runtime.update(dt).ok_or("Neocari MOC2 动画更新失败")?;
                runtime.set_parameter("PARAM_MOUTH_OPEN_Y", mouth.0);
                runtime.set_parameter("PARAM_MOUTH_FORM", mouth.1);
                runtime.update_meshes().ok_or("Neocari MOC2 口型更新失败")?;
                runtime.is_motion_finished()
            }
            Runtime::Moc3(animation) => {
                animation
                    .update(dt, self.drag, mouth)
                    .ok_or("Neocari MOC3 动画更新失败")?;
                let finished = animation
                    .player
                    .as_ref()
                    .is_some_and(MotionPlayer::is_finished);
                if finished {
                    animation.begin_fade();
                    animation.player = None;
                }
                finished
            }
        };
        if finished {
            self.start_idle()?;
        }
        Ok(())
    }
}

pub fn mesh_bounds(meshes: &[Moc3DrawableMesh]) -> Option<[f32; 4]> {
    let mut bounds = [
        f32::INFINITY,
        f32::INFINITY,
        f32::NEG_INFINITY,
        f32::NEG_INFINITY,
    ];
    for vertex in meshes
        .iter()
        .filter(|mesh| mesh.opacity() > 0.0)
        .flat_map(|mesh| mesh.vertices())
    {
        let [x, y] = vertex.position();
        if !x.is_finite() || !y.is_finite() {
            continue;
        }
        bounds = [
            bounds[0].min(x),
            bounds[1].min(y),
            bounds[2].max(x),
            bounds[3].max(y),
        ];
    }
    (bounds[2] > bounds[0] && bounds[3] > bounds[1]).then_some(bounds)
}
