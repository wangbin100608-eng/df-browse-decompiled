use di_core::*;
use std::collections::HashMap;

fn obs(id: &str, type_id: &str, region: &str, x: i32, y: i32, phash: u64, frame: u64, conf: f32, qty: u32) -> Observation {
    Observation {
        observation_id: id.into(), item_type_id: type_id.into(), container_id: region.into(),
        position: ContentPosition { region_id: region.into(), x, y, viewport_offset_y: 0 },
        visual_signature: VisualSignature { phash, color_hist: [0; 16], aspect_ratio_x1000: 1000 },
        frame_index: frame, confidence: conf, quantity: qty,
    }
}

#[test]
fn test_multiple_same_items_different_positions_kept_separate() {
    let mut t = InstanceTracker::new(TrackerConfig::default());
    // 同一帧两个相同类型物品，不同位置、不同视觉签名
    let obs = vec![
        obs("o1", "item_A", "bag", 1, 1, 0b0000_0001, 1, 0.9, 1),
        obs("o2", "item_A", "bag", 5, 5, 0b1111_1110, 1, 0.9, 1),
    ];
    let r = t.process_frame(1, &obs);
    assert_eq!(r.len(), 2);
    assert!(r.iter().all(|x| x.created_instance_id.is_some()));
    assert_eq!(t.ledger().len(), 2, "同类型不同位置必须是两个独立实例");
}

#[test]
fn test_same_item_repeated_frames_no_double_count() {
    let mut t = InstanceTracker::new(TrackerConfig::default());
    let o = obs("o1", "item_A", "bag", 2, 2, 12345, 1, 0.95, 1);
    t.process_frame(1, &[o.clone()]);
    // 连续 20 帧相同观测
    for f in 2..=20 { t.process_frame(f, &[o.clone()]); }
    assert_eq!(t.ledger().len(), 1);
    let inst = t.ledger().values().next().unwrap();
    assert_eq!(inst.quantity, 1, "重复滑动不得重复计数");
    assert_eq!(inst.last_seen_frame, 20);
}

#[test]
fn test_scroll_up_down_content_coordinates() {
    let mut t = InstanceTracker::new(TrackerConfig::default());
    // 物品在内容坐标 (3,10)，视口滚动导致屏幕位置变化
    let mut o1 = obs("o1", "item_A", "bag", 3, 10, 999, 1, 0.9, 1);
    o1.position.viewport_offset_y = 5;
    t.process_frame(1, &[o1]);
    let mut o2 = obs("o2", "item_A", "bag", 3, 10, 999, 2, 0.9, 1);
    o2.position.viewport_offset_y = 12; // 视口滚动了 7 格
    let r = t.process_frame(2, &[o2]);
    // 内容坐标相同（3,10），应匹配同一实例
    assert_eq!(r[0].action, TrackAction::Matched);
    assert_eq!(t.ledger().len(), 1);
}

#[test]
fn test_fast_scroll_partial_overlap() {
    let mut t = InstanceTracker::new(TrackerConfig::default());
    let o1 = obs("o1", "item_A", "ground", 4, 4, 0xABCD, 1, 0.8, 1);
    t.process_frame(1, &[o1]);
    // 快速滑动导致位置偏移 1 格（配置允许范围），视觉签名相同
    let o2 = obs("o2", "item_A", "ground", 4, 5, 0xABCD, 2, 0.8, 1);
    let r = t.process_frame(2, &[o2]);
    assert_eq!(r[0].action, TrackAction::Matched, "小偏移应关联同一实例");
}

#[test]
fn test_different_regions_independent() {
    let mut t = InstanceTracker::new(TrackerConfig::default());
    let o1 = obs("o1", "item_A", "bag", 1, 1, 777, 1, 0.9, 1);
    let o2 = obs("o2", "item_A", "ground", 1, 1, 777, 1, 0.9, 1);
    t.process_frame(1, &[o1, o2]);
    assert_eq!(t.ledger().len(), 2, "不同区域的相同物品必须独立");
}

#[test]
fn test_quantity_change() {
    let mut t = InstanceTracker::new(TrackerConfig::default());
    t.process_frame(1, &[obs("o1", "item_A", "bag", 1, 1, 555, 1, 0.9, 2)]);
    t.process_frame(2, &[obs("o2", "item_A", "bag", 1, 1, 555, 2, 0.9, 5)]);
    let inst = t.ledger().values().next().unwrap();
    assert_eq!(inst.quantity, 5, "数量增加应更新");
}

#[test]
fn test_temporarily_hidden() {
    let cfg = TrackerConfig { hidden_after_frames: 2, ..Default::default() };
    let mut t = InstanceTracker::new(cfg);
    t.process_frame(1, &[obs("o1", "item_A", "bag", 1, 1, 111, 1, 0.9, 1)]);
    t.process_frame(2, &[]);
    t.process_frame(3, &[]);
    let inst = t.ledger().values().next().unwrap();
    assert_eq!(inst.tracking_status, TrackingStatus::TemporarilyHidden);
    // 重新出现
    t.process_frame(4, &[obs("o2", "item_A", "bag", 1, 1, 111, 4, 0.9, 1)]);
    let inst = t.ledger().values().next().unwrap();
    assert_eq!(inst.tracking_status, TrackingStatus::Confirmed);
}

#[test]
fn test_low_confidence_goes_pending() {
    let mut t = InstanceTracker::new(TrackerConfig::default());
    let r = t.process_frame(1, &[obs("o1", "item_A", "bag", 1, 1, 222, 1, 0.5, 1)]);
    assert!(r[0].needs_review);
    assert_eq!(t.pending_instances().len(), 1);
}

#[test]
fn test_human_confirm_and_split_merge() {
    let mut t = InstanceTracker::new(TrackerConfig::default());
    t.process_frame(1, &[obs("o1", "item_A", "bag", 1, 1, 333, 1, 0.5, 4)]);
    let id = t.ledger().keys().next().unwrap().clone();
    t.confirm_instance(&id, Some("item_B".into()), Some(4), None).unwrap();
    assert_eq!(t.pending_instances().len(), 0);
    let new_id = t.split_instance(&id, 2).unwrap();
    assert_eq!(t.ledger().len(), 2);
    assert_eq!(t.ledger()[&id].quantity, 2);
    assert_eq!(t.ledger()[&new_id].quantity, 2);
    t.merge_instances(&id, &new_id).unwrap();
    assert_eq!(t.ledger().len(), 1);
    assert_eq!(t.ledger()[&id].quantity, 4);
}

#[test]
fn test_visual_signature_conflict_not_merged() {
    let mut t = InstanceTracker::new(TrackerConfig::default());
    t.process_frame(1, &[obs("o1", "item_A", "bag", 1, 1, 0x0000, 1, 0.9, 1)]);
    // 视觉签名差异极大（汉明距离 > 8）
    let r = t.process_frame(2, &[obs("o2", "item_A", "bag", 1, 1, 0xFFFF, 2, 0.9, 1)]);
    assert_eq!(r[0].action, TrackAction::Created, "视觉证据冲突不得合并");
}

#[test]
fn test_valuation_pending_not_counted() {
    let mut t = InstanceTracker::new(TrackerConfig::default());
    t.process_frame(1, &[
        obs("o1", "A", "bag", 1, 1, 1, 1, 0.9, 1),   // confirmed
        obs("o2", "B", "bag", 2, 2, 2, 1, 0.5, 10),  // pending
    ]);
    let mut types = HashMap::new();
    types.insert("A".into(), ItemType { id: "A".into(), name: "A".into(), width: 1, height: 1, base_value_cents: Some(100), category: "normal".into() });
    types.insert("B".into(), ItemType { id: "B".into(), name: "B".into(), width: 1, height: 1, base_value_cents: Some(999), category: "normal".into() });
    let mut prices = HashMap::new();
    prices.insert("A".into(), PriceRecord { item_type_id: "A".into(), value_cents: 100, source: "local".into(), updated_at_ms: 0, valid_days: 36500 });
    let active: Vec<&ItemInstance> = t.active_instances();
    let report = value_instances(&active, &types, &prices, 1000);
    assert_eq!(report.total_value_cents, 100, "待确认物品不得计入确定价值");
    assert_eq!(report.missing_price_types, 0);
}

#[test]
fn test_valuation_stale_and_missing() {
    let mut types = HashMap::new();
    types.insert("A".into(), ItemType { id: "A".into(), name: "A".into(), width: 1, height: 1, base_value_cents: Some(100), category: "n".into() });
    let inst = ItemInstance {
        instance_id: "i1".into(), item_type_id: "A".into(), container_id: "bag".into(),
        position: ContentPosition::default(), visual_signature: VisualSignature { phash: 0, color_hist: [0;16], aspect_ratio_x1000: 1000 },
        first_seen_frame: 1, last_seen_frame: 1, confidence: 1.0,
        tracking_status: TrackingStatus::Confirmed, quantity: 2, human_confirmed: true,
    };
    let mut prices = HashMap::new();
    // 过期价格
    prices.insert("A".into(), PriceRecord { item_type_id: "A".into(), value_cents: 50, source: "old".into(), updated_at_ms: 0, valid_days: 1 });
    let r = value_instances(&[&inst], &types, &prices, 10 * 86_400_000);
    assert_eq!(r.lines[0].freshness, PriceFreshness::Stale);
    assert_eq!(r.stale_price_types, 1);
    // 缺失价格
    let r2 = value_instances(&[&inst], &types, &HashMap::new(), 1000);
    assert_eq!(r2.lines[0].freshness, PriceFreshness::Missing);
    assert_eq!(r2.total_value_cents, 200, "缺失价格回退到基础价值");
}

#[test]
fn test_bag_ground_separate() {
    let types: HashMap<String, ItemType> = HashMap::new();
    let prices: HashMap<String, PriceRecord> = HashMap::new();
    let mk = |id: &str, c: &str, q: u32, v: i64| ItemInstance {
        instance_id: id.into(), item_type_id: id.into(), container_id: c.into(),
        position: ContentPosition::default(), visual_signature: VisualSignature { phash: 0, color_hist: [0;16], aspect_ratio_x1000: 1000 },
        first_seen_frame: 1, last_seen_frame: 1, confidence: 1.0,
        tracking_status: TrackingStatus::Confirmed, quantity: q, human_confirmed: true,
    };
    let a = mk("bagitem", "bag", 2, 0); let b = mk("grounditem", "ground", 3, 0);
    let mut p = HashMap::new();
    p.insert("bagitem".into(), PriceRecord { item_type_id: "bagitem".into(), value_cents: 10, source: "x".into(), updated_at_ms: 0, valid_days: 99999 });
    p.insert("grounditem".into(), PriceRecord { item_type_id: "grounditem".into(), value_cents: 20, source: "x".into(), updated_at_ms: 0, valid_days: 99999 });
    let r = value_instances(&[&a, &b], &types, &p, 1000);
    assert_eq!(r.bag_value_cents, 20);
    assert_eq!(r.ground_value_cents, 60);
    assert_eq!(r.total_value_cents, 80);
}

#[test]
fn test_packing_no_overlap_no_out_of_bounds() {
    let items = vec![
        PackItem { id: "a".into(), width: 3, height: 2, value_cents: 100, quantity: 2, rotatable: true },
        PackItem { id: "b".into(), width: 2, height: 2, value_cents: 80, quantity: 2, rotatable: false },
        PackItem { id: "c".into(), width: 1, height: 1, value_cents: 10, quantity: 3, rotatable: false },
    ];
    for r in [maxrects(6, 5, &items), bottom_left(6, 5, &items), optimize_all(6, 5, &items)] {
        assert!(validate_layout(&r).is_empty(), "{:?} 不得重叠或越界", r.algorithm);
        assert_eq!(r.occupied_cells, r.placed.iter().map(|p| p.w*p.h).sum());
        assert_eq!(r.free_cells + r.occupied_cells, 30);
    }
}

#[test]
fn test_packing_rotation_and_unplaced() {
    let items = vec![PackItem { id: "big".into(), width: 5, height: 4, value_cents: 500, quantity: 2, rotatable: true }];
    let r = optimize_all(5, 5, &items);
    assert_eq!(r.placed.len(), 1, "只能放下一个 5x4");
    assert!(!r.unplaced.is_empty(), "超出的物品必须列入 unplaced");
    assert_eq!(r.placed[0].w * r.placed[0].h, 20);
}

#[test]
fn test_exact_search_matches_maxrects_on_small_case() {
    let items = vec![
        PackItem { id: "a".into(), width: 2, height: 2, value_cents: 100, quantity: 1, rotatable: false },
        PackItem { id: "b".into(), width: 1, height: 2, value_cents: 60, quantity: 1, rotatable: false },
    ];
    let exact = exact_search(3, 2, &items);
    let greedy = maxrects(3, 2, &items);
    assert_eq!(exact.total_value_cents, 160);
    assert!(greedy.total_value_cents <= exact.total_value_cents, "贪心不应超过精确最优");
}

#[test]
fn test_yuan_to_cents() {
    assert_eq!(yuan_to_cents("123.45"), Some(12345));
    assert_eq!(yuan_to_cents("0.1"), Some(10));
    assert_eq!(yuan_to_cents("100"), Some(10000));
    assert_eq!(yuan_to_cents("abc"), None);
}
