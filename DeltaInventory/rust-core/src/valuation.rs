//! 价值统计与价格管理

use crate::model::{ItemInstance, ItemType, TrackingStatus};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PriceRecord {
    pub item_type_id: String,
    /// 分为单位
    pub value_cents: i64,
    pub source: String,
    /// Unix 毫秒
    pub updated_at_ms: i64,
    /// 超过此天数视为过期
    pub valid_days: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum PriceFreshness {
    Fresh,
    Stale,
    Missing,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ValuationLine {
    pub instance_id: String,
    pub item_type_id: String,
    pub name: String,
    pub quantity: u32,
    pub unit_value_cents: i64,
    pub line_value_cents: i64,
    pub container_id: String,
    pub freshness: PriceFreshness,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ValuationReport {
    pub lines: Vec<ValuationLine>,
    /// 背包内价值
    pub bag_value_cents: i64,
    /// 地面价值
    pub ground_value_cents: i64,
    /// 其他容器价值
    pub other_value_cents: i64,
    /// 总价值
    pub total_value_cents: i64,
    /// 缺失价格的种类数
    pub missing_price_types: u32,
    /// 过期价格的种类数
    pub stale_price_types: u32,
}

/// 计算价值。规则：
/// - 只有 Confirmed 或 human_confirmed 的实例计入；PendingReview 不计入确定价值。
/// - 缺失/过期价格标记但不猜测价值（按 0 计，同时计数上报）。
pub fn value_instances(
    instances: &[&ItemInstance],
    types: &HashMap<String, ItemType>,
    prices: &HashMap<String, PriceRecord>,
    now_ms: i64,
) -> ValuationReport {
    let mut report = ValuationReport {
        lines: Vec::new(),
        bag_value_cents: 0,
        ground_value_cents: 0,
        other_value_cents: 0,
        total_value_cents: 0,
        missing_price_types: 0,
        stale_price_types: 0,
    };

    for inst in instances {
        if inst.tracking_status != TrackingStatus::Confirmed && !inst.human_confirmed {
            continue;
        }
        let t = types.get(&inst.item_type_id);
        let name = t.map(|x| x.name.clone()).unwrap_or_else(|| "unknown".into());

        let (unit, freshness) = match prices.get(&inst.item_type_id) {
            None => {
                report.missing_price_types += 1;
                (t.and_then(|x| x.base_value_cents).unwrap_or(0), PriceFreshness::Missing)
            }
            Some(p) => {
                let age_days = (now_ms - p.updated_at_ms).max(0) / 86_400_000;
                if age_days > p.valid_days as i64 {
                    report.stale_price_types += 1;
                    (p.value_cents, PriceFreshness::Stale)
                } else {
                    (p.value_cents, PriceFreshness::Fresh)
                }
            }
        };

        let line_value = unit.saturating_mul(inst.quantity as i64);
        report.lines.push(ValuationLine {
            instance_id: inst.instance_id.clone(),
            item_type_id: inst.item_type_id.clone(),
            name,
            quantity: inst.quantity,
            unit_value_cents: unit,
            line_value_cents: line_value,
            container_id: inst.container_id.clone(),
            freshness,
        });

        if inst.container_id == "bag" {
            report.bag_value_cents += line_value;
        } else if inst.container_id == "ground" {
            report.ground_value_cents += line_value;
        } else {
            report.other_value_cents += line_value;
        }
        report.total_value_cents += line_value;
    }
    report
}

/// 将元（字符串）转为分，避免浮点误差。失败返回 None。
pub fn yuan_to_cents(s: &str) -> Option<i64> {
    let s = s.trim();
    if let Some((a, b)) = s.split_once('.') {
        let yuan: i64 = a.parse().ok()?;
        let mut frac = b.chars().take(2).collect::<String>();
        while frac.len() < 2 { frac.push('0'); }
        let cents: i64 = frac.parse().ok()?;
        Some(yuan * 100 + cents)
    } else {
        s.parse::<i64>().ok().map(|v| v * 100)
    }
}
