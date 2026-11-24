package com.deltainventory.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deltainventory.app.domain.ScanViewModel

@Composable
fun SectionTitle(text: String) {
    Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

@Composable
fun HomeScreen(onNavigate: (String) -> Unit, extraActions: List<Pair<String, String>>) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionTitle("DeltaInventory · 智能物品盘点") }
        item {
            Card(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("本地视觉物品盘点 · 背包优化", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("范围：普通非武器物品。识别与数据全部本地处理，不上传云端。", color = Color.Gray)
                }
            }
        }
        item {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onNavigate(Routes.SCAN) }, Modifier.weight(1f)) { Text("开始盘点") }
                OutlinedButton(onClick = { onNavigate(Routes.REVIEW) }, Modifier.weight(1f)) { Text("待确认") }
            }
        }
        items(extraActions) { (label, route) ->
            ListItem(headlineContent = { Text(label) },
                trailingContent = { Text("›") },
                modifier = Modifier.padding(horizontal = 8.dp).clickableNoRipple { onNavigate(route) })
        }
        item {
            Text("版权声明：游戏数据/图片归腾讯及《三角洲行动》官方所有，仅个人本地研究使用。",
                color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(16.dp))
        }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

@Composable
fun ScanScreen(vm: ScanViewModel, onStart: () -> Unit, onStop: () -> Unit) {
    val status by vm.status.collectAsState()
    val progress by vm.progress.collectAsState()
    val stats by vm.recognitionStats.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("盘点会话", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("状态: $status   帧数: $progress")
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.startSession(); onStart() }, enabled = status == "idle") { Text("开始") }
                        OutlinedButton(onClick = vm::pause, enabled = status == "running") { Text("暂停") }
                        OutlinedButton(onClick = vm::resume, enabled = status == "paused") { Text("恢复") }
                        Button(onClick = { vm.stopSession(); onStop() }, enabled = status == "running" || status == "paused",
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C))) { Text("结束") }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
        item { Text("识别统计", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        item {
            listOf("帧数" to stats.frames, "新建实例" to stats.created, "关联匹配" to stats.matched,
                "待确认" to stats.pending, "去重抑制" to stats.suppressed).forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(k); Text("$v", fontWeight = FontWeight.Bold)
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
        item { Text("说明：屏幕采集需授权；悬浮球可拖动，点击进入/暂停。低置信度物品进入待确认队列。", color = Color.Gray, fontSize = 12.sp) }
    }
}

@Composable
fun ReviewScreen(vm: ScanViewModel, onDetail: (String) -> Unit) {
    val pending by vm.pending.collectAsState()
    if (pending.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无待确认物品") }
    } else {
        LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
            items(pending) { inst ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(inst.itemTypeId, fontWeight = FontWeight.Bold)
                            Text("置信度 ${(inst.confidence * 100).toInt()}%  数量 ${inst.quantity}  区域 ${inst.regionId}",
                                color = Color.Gray, fontSize = 12.sp)
                        }
                        Button(onClick = { vm.confirm(inst.instanceId) }) { Text("确认") }
                        TextButton(onClick = { vm.discard(inst.instanceId) }) { Text("删除") }
                    }
                }
            }
        }
    }
}

@Composable
fun StatsScreen(vm: ScanViewModel) {
    LaunchedEffect(Unit) { vm.computeValuation() }
    val v by vm.valuation.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SectionTitle("价值统计")
        if (v == null) { Text("点击开始盘点以统计"); return@Column }
        val view = v!!
        listOf("背包价值" to view.bagCents, "地面价值" to view.groundCents, "总价值" to view.totalCents).forEach { (k, c) ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(k); Text("${c / 100}.${String.format("%02d", c % 100)}", fontWeight = FontWeight.Bold, color = Color(0xFFFFB300))
                }
            }
        }
        Text("缺失价格种类: ${view.missing}   过期价格种类: ${view.stale}", color = Color.Gray)
        Text("价格更新时间: ${view.updatedAt}", color = Color.Gray, fontSize = 11.sp)
        Button(onClick = { vm.computeValuation() }, Modifier.padding(top = 12.dp)) { Text("刷新统计") }
    }
}

@Composable
fun GridScreen(vm: ScanViewModel) {
    var gw by remember { mutableIntStateOf(10) }
    var gh by remember { mutableIntStateOf(6) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SectionTitle("二维背包优化")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("网格 ${gw}×${gh}")
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { gw = (gw - 1).coerceAtLeast(3) }) { Text("-") }
            OutlinedButton(onClick = { gw = (gw + 1).coerceAtMost(20) }) { Text("+") }
        }
        Button(onClick = { vm.optimize(gw, gh) }, Modifier.fillMaxWidth()) { Text("计算最佳布局") }
        val result by vm.packResult.collectAsState()
        result?.let { r ->
            Text("算法: ${r.algorithm}  价值: ${r.totalValueCents / 100}  耗时: ${r.elapsedMs}ms")
            LazyVerticalGrid(GridCells.Fixed(gw), Modifier.fillMaxSize().padding(top = 8.dp)) {
                val cellMap = r.placed.associateBy { "${it.x},${it.y}" }
                items((0 until gw * gh).toList()) { i ->
                    val x = i % gw; val y = i / gw
                    val p = cellMap["$x,$y"]
                    Box(Modifier.aspectRatio(1f).padding(1.dp).background(
                        if (p != null) Color(0xFF2E7D32) else Color(0xFF21262D)
                    ), contentAlignment = Alignment.Center) {
                        if (p != null) Text(p.id.takeLast(4), fontSize = 9.sp)
                    }
                }
            }
            Text("未放入: ${r.unplaced}", color = Color.Gray)
        }
    }
}

@Composable
fun HistoryScreen() {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item { SectionTitle("历史记录") }
        item { Text("历史盘点会话保存在本地数据库，可在数据库页面管理。", color = Color.Gray) }
    }
}

@Composable
fun DatabaseScreen() {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SectionTitle("数据库管理")
        Text("9 张表：items / item_images / item_prices / containers / item_instances / scan_sessions / frame_records / recognition_events / optimization_results")
        Text("支持数据导入、备份与恢复。物品数据库从 item-database.json（893 件普通物品）导入。")
        Button(onClick = { /* 导入逻辑见 tools/import_assets.py */ }, Modifier.padding(top = 12.dp)) { Text("导入物品数据库") }
    }
}

@Composable
fun SettingsScreen() {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SectionTitle("设置")
        Text("视觉识别置信度阈值、追踪参数、价格过期天数等均可在本地配置。")
        Text("模型: untrained-stub-v1（未训练，仅验证链路）", color = Color.Gray)
    }
}

@Composable
fun InstanceDetailScreen(id: String, vm: ScanViewModel) {
    val inst = vm.getDetail(id)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SectionTitle("物品详情: $id")
        if (inst == null) { Text("未找到该实例") }
        else {
            Text("类型: ${inst.itemTypeId}")
            Text("区域: ${inst.regionId}  位置: (${inst.x}, ${inst.y})")
            Text("置信度: ${(inst.confidence * 100).toInt()}%")
            Text("数量: ${inst.quantity}   状态: ${inst.status}")
            var qty by remember { mutableIntStateOf(inst.quantity) }
            OutlinedTextField(value = qty.toString(), onValueChange = { qty = it.toIntOrNull() ?: 1 }, label = { Text("数量") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.confirm(id, qty = qty) }) { Text("保存确认") }
                OutlinedButton(onClick = { vm.split(id, 1) }) { Text("拆分") }
                TextButton(onClick = { vm.discard(id) }) { Text("删除") }
            }
        }
    }
}
