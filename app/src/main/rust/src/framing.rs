//! Stable neutral-pose framing. User offsets use half the shorter surface edge.
pub fn transform(
    bounds: [f32; 4],
    flips_y: bool,
    width: i32,
    height: i32,
    user: (f32, f32, f32),
) -> [f32; 4] {
    let (width, height) = (width.max(1) as f32, height.max(1) as f32);
    let pixel_scale = (width / (bounds[2] - bounds[0]).max(0.001))
        .min(height / (bounds[3] - bounds[1]).max(0.001))
        * 0.95
        * user.2.clamp(0.1, 8.0);
    let sx = 2.0 * pixel_scale / width;
    let sy = 2.0 * pixel_scale / height * if flips_y { -1.0 } else { 1.0 };
    [
        sx,
        sy,
        -(bounds[0] + bounds[2]) * 0.5 * sx + user.0 * width.min(height) / width,
        -(bounds[1] + bounds[3]) * 0.5 * sy + user.1 * width.min(height) / height,
    ]
}

pub fn model_point(transform: [f32; 4], x: f32, y: f32) -> [f32; 2] {
    [
        (x * 2.0 - 1.0 - transform[2]) / transform[0],
        (1.0 - y * 2.0 - transform[3]) / transform[1],
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn both_coordinate_systems_keep_the_neutral_center_and_aspect() {
        for flipped in [false, true] {
            let t = transform(
                [10.0, 20.0, 110.0, 220.0],
                flipped,
                400,
                800,
                (0.0, 0.0, 1.0),
            );
            let center = model_point(t, 0.5, 0.5);
            assert!((center[0] - 60.0).abs() < 0.001);
            assert!((center[1] - 120.0).abs() < 0.001);
            assert!((t[0] * 400.0 - t[1].abs() * 800.0).abs() < 0.001);
            assert_eq!(t[1] < 0.0, flipped);
        }
    }

    #[test]
    fn changing_render_resolution_preserves_transform_and_hit_testing() {
        let a = transform([0.0, 0.0, 1.0, 2.0], true, 400, 800, (0.2, -0.3, 1.5));
        let b = transform([0.0, 0.0, 1.0, 2.0], true, 800, 1600, (0.2, -0.3, 1.5));
        assert_eq!(a, b);
        assert_eq!(model_point(a, 0.2, 0.7), model_point(b, 0.2, 0.7));
    }
}
