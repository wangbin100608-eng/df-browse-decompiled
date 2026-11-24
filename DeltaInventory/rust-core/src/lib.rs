#![allow(dead_code)]
pub mod model;
pub mod tracker;
pub mod valuation;
pub mod packing;
pub mod jni;

pub use model::*;
pub use tracker::{InstanceTracker, TrackResult, TrackAction};
pub use valuation::{value_instances, PriceRecord, ValuationReport, ValuationLine, PriceFreshness, yuan_to_cents};
pub use packing::*;

/// 返回核心库版本，用于 JNI 自检
pub fn version() -> &'static str { "di_core 1.0.0" }
