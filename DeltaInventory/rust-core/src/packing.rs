//! 二维矩形装箱优化：MaxRects / Bottom-Left / 小规模精确搜索基准

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Rect {
    pub x: u32,
    pub y: u32,
    pub w: u32,
    pub h: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PackItem {
    pub id: String,
    pub width: u32,
    pub height: u32,
    pub value_cents: i64,
    pub quantity: u32,
    /// 是否允许旋转 90°
    pub rotatable: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PlacedItem {
    pub id: String,
    pub x: u32,
    pub y: u32,
    pub w: u32,
    pub h: u32,
    pub rotated: bool,
    pub value_cents: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PackResult {
    pub algorithm: String,
    pub grid_width: u32,
    pub grid_height: u32,
    pub placed: Vec<PlacedItem>,
    pub unplaced: Vec<(String, u32)>,
    pub total_value_cents: i64,
    pub occupied_cells: u32,
    pub free_cells: u32,
    pub elapsed_ms: u128,
}

// ---------- MaxRects ----------

#[derive(Clone)]
struct FreeRect { x: u32, y: u32, w: u32, h: u32 }

fn split_free_rect(free: &mut Vec<FreeRect>, used: &Rect) {
    let mut next = Vec::with_capacity(free.len() * 4);
    for f in free.iter() {
        // 相交则切分
        let ix = f.x.max(used.x);
        let iy = f.y.max(used.y);
        let ax = (f.x + f.w).min(used.x + used.w);
        let ay = (f.y + f.h).min(used.y + used.h);
        let intersect = ax > ix && ay > iy;
        if !intersect {
            next.push(f.clone());
            continue;
        }
        // 上
        if used.y > f.y {
            next.push(FreeRect { x: f.x, y: f.y, w: f.w, h: used.y - f.y });
        }
        // 下
        if used.y + used.h < f.y + f.h {
            next.push(FreeRect { x: f.x, y: used.y + used.h, w: f.w, h: f.y + f.h - (used.y + used.h) });
        }
        // 左
        if used.x > f.x {
            next.push(FreeRect { x: f.x, y: f.y, w: used.x - f.x, h: f.h });
        }
        // 右
        if used.x + used.w < f.x + f.w {
            next.push(FreeRect { x: used.x + used.w, y: f.y, w: f.x + f.w - (used.x + used.w), h: f.h });
        }
    }
    // 去除被完全包含的自由矩形
    let mut dedup: Vec<FreeRect> = Vec::new();
    for f in next {
        if !dedup.iter().any(|g| g.x <= f.x && g.y <= f.y && g.x + g.w >= f.x + f.w && g.y + g.h >= f.y + f.h) {
            dedup.push(f);
        }
    }
    *free = dedup;
}

pub fn maxrects(grid_w: u32, grid_h: u32, items: &[PackItem]) -> PackResult {
    let start = std::time::Instant::now();
    let mut free = vec![FreeRect { x: 0, y: 0, w: grid_w, h: grid_h }];
    let mut placed = Vec::new();
    let mut unplaced: HashMap<String, u32> = HashMap::new();

    // 价值密度降序，其次面积降序
    let mut order: Vec<&PackItem> = items.iter().collect();
    order.sort_by(|a, b| {
        let da = a.value_cents.max(1) as f64 / ((a.width * a.height).max(1) as f64);
        let db = b.value_cents.max(1) as f64 / ((b.width * b.height).max(1) as f64);
        db.partial_cmp(&da).unwrap_or(std::cmp::Ordering::Equal)
            .then_with(|| (b.width * b.height).cmp(&(a.width * a.height)))
    });

    for item in order {
        for _ in 0..item.quantity {
            let mut best: Option<(usize, u32, u32, bool)> = None;
            for (idx, f) in free.iter().enumerate() {
                let orientations: &[(u32, u32, bool)] = if item.rotatable && item.width != item.height {
                    &[(item.width, item.height, false), (item.height, item.width, true)]
                } else {
                    &[(item.width, item.height, false)]
                };
                for &(w, h, rot) in orientations {
                    if f.w >= w && f.h >= h {
                        let short_fit = (f.w - w).min(f.h - h);
                        if best.map_or(true, |(_, bs, _, _)| short_fit < bs) {
                            best = Some((idx, short_fit, f.y, rot));
                        }
                    }
                }
            }
            if let Some((idx, _, _, rot)) = best {
                let f = free[idx].clone();
                let (w, h) = if rot { (item.height, item.width) } else { (item.width, item.height) };
                let r = Rect { x: f.x, y: f.y, w, h };
                placed.push(PlacedItem {
                    id: item.id.clone(), x: r.x, y: r.y, w, h, rotated: rot,
                    value_cents: item.value_cents,
                });
                split_free_rect(&mut free, &r);
            } else {
                *unplaced.entry(item.id.clone()).or_insert(0) += 1;
            }
        }
    }
    make_result("maxrects", grid_w, grid_h, placed, unplaced, start)
}

// ---------- Bottom-Left ----------

pub fn bottom_left(grid_w: u32, grid_h: u32, items: &[PackItem]) -> PackResult {
    let start = std::time::Instant::now();
    let mut occupied = vec![vec![false; grid_w as usize]; grid_h as usize];
    let mut placed = Vec::new();
    let mut unplaced: HashMap<String, u32> = HashMap::new();

    let mut order: Vec<&PackItem> = items.iter().collect();
    order.sort_by(|a, b| (b.value_cents).cmp(&a.value_cents)
        .then_with(|| (b.height * b.width).cmp(&(a.height * a.width))));

    for item in order {
        for _ in 0..item.quantity {
            let mut best: Option<(u32, u32, u32, u32, bool)> = None; // y,x,w,h,rot
            let orientations: &[(u32, u32, bool)] = if item.rotatable && item.width != item.height {
                &[(item.width, item.height, false), (item.height, item.width, true)]
            } else { &[(item.width, item.height, false)] };
            for &(w, h, rot) in orientations {
                if w > grid_w || h > grid_h { continue; }
                for y in 0..=(grid_h - h) {
                    for x in 0..=(grid_w - w) {
                        let fits = (0..h).all(|dy| (0..w).all(|dx| !occupied[(y + dy) as usize][(x + dx) as usize]));
                        if fits {
                            if best.map_or(true, |(by, bx, _, _, _)| y < by || (y == by && x < bx)) {
                                best = Some((y, x, w, h, rot));
                            }
                        }
                    }
                }
            }
            if let Some((y, x, w, h, rot)) = best {
                for dy in 0..h { for dx in 0..w { occupied[(y + dy) as usize][(x + dx) as usize] = true; } }
                placed.push(PlacedItem {
                    id: item.id.clone(), x, y, w, h, rotated: rot, value_cents: item.value_cents,
                });
            } else {
                *unplaced.entry(item.id.clone()).or_insert(0) += 1;
            }
        }
    }
    make_result("bottom_left", grid_w, grid_h, placed, unplaced, start)
}

fn make_result(algo: &str, gw: u32, gh: u32, placed: Vec<PlacedItem>,
               unplaced: HashMap<String, u32>, start: std::time::Instant) -> PackResult {
    let total: i64 = placed.iter().map(|p| p.value_cents).sum();
    let occupied: u32 = placed.iter().map(|p| p.w * p.h).sum();
    PackResult {
        algorithm: algo.into(), grid_width: gw, grid_height: gh,
        placed, unplaced: unplaced.into_iter().collect(),
        total_value_cents: total, occupied_cells: occupied,
        free_cells: gw * gh - occupied,
        elapsed_ms: start.elapsed().as_millis(),
    }
}

// ---------- 小规模精确搜索（测试基准，<=8 件） ----------

/// 精确搜索：枚举每个物品的位置与旋转，最大化价值。仅用于测试基准。
pub fn exact_search(grid_w: u32, grid_h: u32, items: &[PackItem]) -> PackResult {
    let start = std::time::Instant::now();
    let flat: Vec<(String, u32, u32, i64, bool)> = items.iter()
        .flat_map(|it| (0..it.quantity).map(move |_| (it.id.clone(), it.width, it.height, it.value_cents, it.rotatable)))
        .collect();
    if flat.len() > 8 {
        // 超出测试基准范围，退回 MaxRects 并标注
        let mut r = maxrects(grid_w, grid_h, items);
        r.algorithm = "exact_search_fallback(maxrects)".into();
        return r;
    }
    let mut best_value = 0i64;
    let mut best: Vec<PlacedItem> = Vec::new();
    let mut occupied = vec![vec![false; grid_w as usize]; grid_h as usize];

    fn dfs(idx: usize, flat: &Vec<(String, u32, u32, i64, bool)>, gw: u32, gh: u32,
           occ: &mut Vec<Vec<bool>>, cur: &mut Vec<PlacedItem>, best: &mut Vec<PlacedItem>, best_val: &mut i64) {
        if idx == flat.len() {
            let v: i64 = cur.iter().map(|p| p.value_cents).sum();
            if v > *best_val { *best_val = v; *best = cur.clone(); }
            return;
        }
        let (id, w, h, val, rot) = &flat[idx];
        // 不放
        dfs(idx + 1, flat, gw, gh, occ, cur, best, best_val);
        // 放（含旋转）
        let orientations: &[(u32, u32, bool)] = if *rot && w != h { &[(*w, *h, false), (*h, *w, true)] } else { &[(*w, *h, false)] };
        for &(rw, rh, r) in orientations {
            if rw > gw || rh > gh { continue; }
            for y in 0..=(gh - rh) {
                for x in 0..=(gw - rw) {
                    let fits = (0..rh).all(|dy| (0..rw).all(|dx| !occ[(y+dy) as usize][(x+dx) as usize]));
                    if fits {
                        for dy in 0..rh { for dx in 0..rw { occ[(y+dy) as usize][(x+dx) as usize] = true; } }
                        cur.push(PlacedItem { id: id.clone(), x, y, w: rw, h: rh, rotated: r, value_cents: *val });
                        dfs(idx + 1, flat, gw, gh, occ, cur, best, best_val);
                        cur.pop();
                        for dy in 0..rh { for dx in 0..rw { occ[(y+dy) as usize][(x+dx) as usize] = false; } }
                    }
                }
            }
        }
    }
    dfs(0, &flat, grid_w, grid_h, &mut occupied, &mut Vec::new(), &mut best, &mut best_value);
    let placed_count = best.len();
    make_result("exact_search", grid_w, grid_h, best, HashMap::new(), start).into_iter_result(placed_count, flat.len())
}

trait IterResultExt {
    fn into_iter_result(self, placed_count: usize, total_count: usize) -> PackResult;
}
impl IterResultExt for PackResult {
    fn into_iter_result(mut self, placed: usize, total: usize) -> PackResult {
        if placed < total {
            // 精确搜索默认把所有未放入的记入 unplaced；这里用占位标记
            self.unplaced.push(("__unplaced__".into(), (total - placed) as u32));
        }
        self
    }
}

/// 校验摆放结果：返回冲突列表（重叠/越界）
pub fn validate_layout(result: &PackResult) -> Vec<String> {
    let mut errors = Vec::new();
    let mut cells = vec![vec![false; result.grid_width as usize]; result.grid_height as usize];
    for p in &result.placed {
        if p.x + p.w > result.grid_width || p.y + p.h > result.grid_height {
            errors.push(format!("{} out of bounds", p.id));
            continue;
        }
        for dy in 0..p.h {
            for dx in 0..p.w {
                let cell = &mut cells[(p.y + dy) as usize][(p.x + dx) as usize];
                if *cell { errors.push(format!("{} overlap", p.id)); }
                *cell = true;
            }
        }
    }
    errors
}

/// 生成多个方案并选最佳
pub fn optimize_all(grid_w: u32, grid_h: u32, items: &[PackItem]) -> PackResult {
    let mut results = vec![
        maxrects(grid_w, grid_h, items),
        bottom_left(grid_w, grid_h, items),
    ];
    results.into_iter().max_by_key(|r| (r.total_value_cents, r.occupied_cells)).unwrap()
}
