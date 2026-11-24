//! DeltaInventory 核心领域模型
//! 所有物品身份、追踪、统计以「实例账本」为唯一真相来源。

use serde::{Deserialize, Serialize};

/// 物品静态类型（来自物品数据库）
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ItemType {
    pub id: String,
    pub name: String,
    pub width: u32,
    pub height: u32,
    /// 基础价值（分为单位，避免浮点误差）；None = unknown
    pub base_value_cents: Option<i64>,
    pub category: String,
}

/// 追踪状态
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum TrackingStatus {
    /// 高置信度已确认
    Confirmed,
    /// 低置信度，等待人工确认
    PendingReview,
    /// 暂时不可见（仍在账本中）
    TemporarilyHidden,
    /// 已被人工删除
    Discarded,
}

/// 视觉签名：去重的视觉证据之一（不是唯一依据）
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct VisualSignature {
    /// 感知哈希（64bit，汉明距离比较）
    pub phash: u64,
    /// 颜色直方图摘要（简化为 16 桶）
    pub color_hist: [u16; 16],
    /// 宽高比（x1000）
    pub aspect_ratio_x1000: u32,
}

/// 内容坐标系中的位置（不是屏幕坐标）
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
pub struct ContentPosition {
    /// 区域标识（如 "bag", "ground_left", "safe_box"）
    pub region_id: String,
    /// 内容坐标 x,y（格子单位，非像素）
    pub x: i32,
    pub y: i32,
    /// 视口/滚动偏移，用于跨帧对齐
    pub viewport_offset_y: i32,
}

/// 物品实例（账本中的一行）
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ItemInstance {
    pub instance_id: String,
    pub item_type_id: String,
    /// 所属容器（bag/ground/safe_box/...）
    pub container_id: String,
    pub position: ContentPosition,
    pub visual_signature: VisualSignature,
    pub first_seen_frame: u64,
    pub last_seen_frame: u64,
    /// 0.0 ~ 1.0
    pub confidence: f32,
    pub tracking_status: TrackingStatus,
    /// 数量（同类型同位置的堆叠）
    pub quantity: u32,
    /// 人工确认标记
    pub human_confirmed: bool,
}

impl ItemInstance {
    /// 判断两个观测是否可能是同一实例。
    /// 严格：类型一致 + 区域一致 + 位置接近 + 视觉签名接近 + 时间连续。
    /// 禁止仅凭名称或图片相似度合并。
    /// `current_frame` 由采集器（process_frame）提供，代表观测所属帧，
    /// 不依赖识别结果自带的 frame_index —— 后者可能未正确设置。
    pub fn could_be_same(&self, other: &Observation, cfg: &TrackerConfig, current_frame: u64) -> bool {
        if self.item_type_id != other.item_type_id {
            return false;
        }
        if self.position.region_id != other.position.region_id {
            return false;
        }
        if self.container_id != other.container_id {
            return false;
        }
        let dx = (self.position.x - other.position.x).abs();
        let dy = (self.position.y - other.position.y).abs();
        if dx > cfg.max_position_delta_cells || dy > cfg.max_position_delta_cells {
            return false;
        }
        let dist = hamming64(self.visual_signature.phash, other.visual_signature.phash);
        if dist > cfg.max_phash_distance {
            return false;
        }
        // 时间连续性：观测帧不能早于实例最后出现帧
        current_frame >= self.last_seen_frame
    }
}

/// 单帧观测（视觉识别系统输出）
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Observation {
    pub observation_id: String,
    pub item_type_id: String,
    pub container_id: String,
    pub position: ContentPosition,
    pub visual_signature: VisualSignature,
    pub frame_index: u64,
    pub confidence: f32,
    pub quantity: u32,
}

/// 追踪器配置
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TrackerConfig {
    /// 允许的最大位置偏移（格子）
    pub max_position_delta_cells: i32,
    /// 感知哈希最大汉明距离
    pub max_phash_distance: u32,
    /// 连续多少帧不可见后标记为 TemporarilyHidden
    pub hidden_after_frames: u64,
    /// 进入人工确认队列的置信度阈值
    pub review_confidence_threshold: f32,
}

impl Default for TrackerConfig {
    fn default() -> Self {
        Self {
            max_position_delta_cells: 1,
            max_phash_distance: 8,
            hidden_after_frames: 3,
            review_confidence_threshold: 0.75,
        }
    }
}

/// 计算两个 u64 的汉明距离
pub fn hamming64(a: u64, b: u64) -> u32 {
    (a ^ b).count_ones()
}

/// 扫描会话
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ScanSession {
    pub session_id: String,
    pub started_at_ms: i64,
    pub ended_at_ms: Option<i64>,
    pub frame_count: u64,
}
