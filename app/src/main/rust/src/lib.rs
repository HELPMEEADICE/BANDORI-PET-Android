#![allow(non_snake_case)]
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

mod framing;
mod interaction;
mod model;
mod resources;

#[cfg(target_os = "android")]
mod android;
#[cfg(any(target_os = "android", test))]
mod ffi;
#[cfg(any(target_os = "android", test))]
mod gles;
#[cfg(any(target_os = "android", test))]
mod overlays;
#[cfg(target_os = "android")]
mod renderer;
