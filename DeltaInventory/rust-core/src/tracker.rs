//! 跨帧物品实例追踪器
//! 核心原则：以实例账本为准，绝不逐帧累加识别数量。

use crate::model::*;
use serde::Serialize;
use std::collections::{HashMap, HashSet};

pub struct InstanceTracker {
    pub config: TrackerConfig,
    /// 账本：instance_id -> ItemInstance
    ledger: HashMap<String, ItemInstance>,
    /// 每个实例的当前任务计数（用于 pending review）
    next_instance_seq: u64,
}

#[derive(Debug, Clone, Serialize)]
pub struct TrackResult {
    pub matched_instance_id: Option<String>,
    pub created_instance_id: Option<String>,
    pub is_duplicate: bool,
    pub needs_review: bool,
    pub action: TrackAction,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub enum TrackAction {
    Matched,
    Created,
    DuplicateSuppressed,
    CreatedPendingReview,
}

impl InstanceTracker {
    pub fn new(config: TrackerConfig) -> Self {
        Self {
            config,
            ledger: HashMap::new(),
            next_instance_seq: 0,
        }
    }

    /// 处理单帧的一批观测。返回每条观测的处理结果。
    pub fn process_frame(&mut self, frame_index: u64, observations: &[Observation]) -> Vec<TrackResult> {
        // 本帧已匹配的实例，防止两个观测匹配到同一实例
        let mut claimed: HashSet<String> = HashSet::new();
        let mut results = Vec::with_capacity(observations.len());

        for obs in observations {
            // 1) 在账本中寻找候选：严格匹配（类型+区域+位置+视觉+时间）
            let mut candidates: Vec<&ItemInstance> = self
                .ledger
                .values()
                .filter(|inst| {
                    !claimed.contains(&inst.instance_id)
                        && inst.tracking_status != TrackingStatus::Discarded
                        && inst.could_be_same(obs, &self.config, frame_index)
                })
                .collect();

            // 按视觉距离 + 位置距离排序，最优者优先
            candidates.sort_by_key(|inst| {
                let ph = hamming64(inst.visual_signature.phash, obs.visual_signature.phash) as i64;
                let pos = (inst.position.x - obs.position.x).abs() as i64
                    + (inst.position.y - obs.position.y).abs() as i64;
                ph * 10 + pos
            });

            if let Some(best) = candidates.first() {
                let id = best.instance_id.clone();
                let inst = self.ledger.get_mut(&id).unwrap();
                inst.last_seen_frame = frame_index;
                inst.confidence = (inst.confidence * 0.7 + obs.confidence * 0.3).min(1.0);
                inst.position = obs.position.clone();
                // 数量变化：以观测为准（但只有人工确认过的实例才允许减少）
                if obs.quantity > inst.quantity || inst.human_confirmed {
                    inst.quantity = obs.quantity.max(1);
                }
                if inst.tracking_status == TrackingStatus::TemporarilyHidden {
                    inst.tracking_status = if obs.confidence >= self.config.review_confidence_threshold {
                        TrackingStatus::Confirmed
                    } else {
                        TrackingStatus::PendingReview
                    };
                }
                claimed.insert(id.clone());
                results.push(TrackResult {
                    matched_instance_id: Some(id),
                    created_instance_id: None,
                    is_duplicate: false,
                    needs_review: inst.tracking_status == TrackingStatus::PendingReview,
                    action: TrackAction::Matched,
                });
            } else {
                // 2) 无候选 → 新实例（低置信度进入待确认）
                self.next_instance_seq += 1;
                let id = format!("inst_{:06}", self.next_instance_seq);
                let status = if obs.confidence >= self.config.review_confidence_threshold {
                    TrackingStatus::Confirmed
                } else {
                    TrackingStatus::PendingReview
                };
                let inst = ItemInstance {
                    instance_id: id.clone(),
                    item_type_id: obs.item_type_id.clone(),
                    container_id: obs.container_id.clone(),
                    position: obs.position.clone(),
                    visual_signature: obs.visual_signature.clone(),
                    first_seen_frame: frame_index,
                    last_seen_frame: frame_index,
                    confidence: obs.confidence,
                    tracking_status: status.clone(),
                    quantity: obs.quantity.max(1),
                    human_confirmed: false,
                };
                let needs_review = status == TrackingStatus::PendingReview;
                self.ledger.insert(id.clone(), inst);
                claimed.insert(id.clone());
                results.push(TrackResult {
                    matched_instance_id: None,
                    created_instance_id: Some(id),
                    is_duplicate: false,
                    needs_review,
                    action: if needs_review {
                        TrackAction::CreatedPendingReview
                    } else {
                        TrackAction::Created
                    },
                });
            }
        }

        // 3) 帧末：将连续未出现的实例标记为暂时不可见
        for inst in self.ledger.values_mut() {
            if !claimed.contains(&inst.instance_id)
                && inst.tracking_status != TrackingStatus::Discarded
                && frame_index.saturating_sub(inst.last_seen_frame) >= self.config.hidden_after_frames
            {
                inst.tracking_status = TrackingStatus::TemporarilyHidden;
            }
        }

        results
    }

    /// 人工确认实例（修正类型/数量/位置），写入账本
    pub fn confirm_instance(
        &mut self,
        instance_id: &str,
        item_type_id: Option<String>,
        quantity: Option<u32>,
        position: Option<ContentPosition>,
    ) -> Result<(), String> {
        let inst = self
            .ledger
            .get_mut(instance_id)
            .ok_or_else(|| format!("instance {} not found", instance_id))?;
        if let Some(t) = item_type_id {
            inst.item_type_id = t;
        }
        if let Some(q) = quantity {
            inst.quantity = q.max(1);
        }
        if let Some(p) = position {
            inst.position = p;
        }
        inst.human_confirmed = true;
        inst.confidence = 1.0;
        inst.tracking_status = TrackingStatus::Confirmed;
        Ok(())
    }

    /// 拆分错误合并的实例：原实例数量 -n，新实例数量 n
    pub fn split_instance(&mut self, instance_id: &str, split_quantity: u32) -> Result<String, String> {
        let src = self
            .ledger
            .get_mut(instance_id)
            .ok_or_else(|| format!("instance {} not found", instance_id))?;
        if split_quantity == 0 || split_quantity >= src.quantity {
            return Err("split quantity out of range".into());
        }
        src.quantity -= split_quantity;
        self.next_instance_seq += 1;
        let new_id = format!("inst_{:06}", self.next_instance_seq);
        let mut new_inst = src.clone();
        new_inst.instance_id = new_id.clone();
        new_inst.quantity = split_quantity;
        new_inst.human_confirmed = true;
        new_inst.confidence = 1.0;
        // 新实例偏移一格，避免与源实例重叠
        new_inst.position.x += 1;
        self.ledger.insert(new_id.clone(), new_inst);
        Ok(new_id)
    }

    /// 合并确认的重复实例（必须人工调用）
    pub fn merge_instances(&mut self, id_a: &str, id_b: &str) -> Result<(), String> {
        let a = self.ledger.get(id_a).cloned().ok_or("instance A not found")?;
        let b = self.ledger.get(id_b).cloned().ok_or("instance B not found")?;
        if a.item_type_id != b.item_type_id || a.container_id != b.container_id {
            return Err("cannot merge instances of different type/container".into());
        }
        let merged = ItemInstance {
            instance_id: id_a.to_string(),
            quantity: a.quantity + b.quantity,
            first_seen_frame: a.first_seen_frame.min(b.first_seen_frame),
            last_seen_frame: a.last_seen_frame.max(b.last_seen_frame),
            confidence: 1.0,
            human_confirmed: true,
            tracking_status: TrackingStatus::Confirmed,
            ..a
        };
        self.ledger.insert(id_a.to_string(), merged);
        self.ledger.remove(id_b);
        Ok(())
    }

    /// 丢弃实例（人工删除错误识别）
    pub fn discard_instance(&mut self, instance_id: &str) -> Result<(), String> {
        let inst = self.ledger.get_mut(instance_id).ok_or("not found")?;
        inst.tracking_status = TrackingStatus::Discarded;
        Ok(())
    }

    pub fn ledger(&self) -> &HashMap<String, ItemInstance> {
        &self.ledger
    }

    /// 有效实例（未丢弃）
    pub fn active_instances(&self) -> Vec<&ItemInstance> {
        self.ledger
            .values()
            .filter(|i| i.tracking_status != TrackingStatus::Discarded)
            .collect()
    }

    /// 待确认实例
    pub fn pending_instances(&self) -> Vec<&ItemInstance> {
        self.ledger
            .values()
            .filter(|i| i.tracking_status == TrackingStatus::PendingReview)
            .collect()
    }
}
