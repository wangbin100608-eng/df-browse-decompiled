# 特征库重建说明

`app/src/main/assets/item-features.json` 由 `tools/build_features.py` 生成，**必须与
`app/src/main/java/com/deltainventory/app/vision/StepHash.java` 逐位一致**，否则识别会错配。

## 三个组件必须保持同步

| 组件 | 作用 | 一致性要求 |
|---|---|---|
| `tools/build_features.py` | 下载 1663 张图标，算 phash/hist 生成特征库 | phash: 9x8 步长采样、灰度 `(R*77+G*150+B*29)>>8`、透明预乘 `v*alpha//255` |
| `StepHash.java` | Android 端 `phash/hist` 的唯一实现 | 与生成脚本逐位一致；Kotlin 的 `IconMatcher` 委托它，勿另写实现 |
| `item-features.json` | 生成产物 | 由脚本产出，勿手工编辑 |

## 重建步骤

```bash
pip install pillow
cd DeltaInventory
python3 tools/build_features.py \
  --db app/src/main/assets/item-database.json \
  --cache /tmp/df-icons \
  --out app/src/main/assets/item-features.json
```

脚本会缓存图标到 `--cache`，重复运行不重下。生成后 `features` 应为 1663 条，
每条 `ph` 是无符号 64 位十进制字符串。

## 关键坑（已踩过并修复）

1. **有符号解析**：`ph > Long.MAX_VALUE` 时 `toLong()/parseLong` 抛异常 → 必须用 `Long.parseUnsignedLong`。
2. **采样一致性**：原库用 PIL LANCZOS 灰度生成、运行时用 createScaledBitmap 双线性+Blue → 全部改成步长采样。
3. **透明预乘**：Android `Bitmap.getPixels` 对透明像素返回预乘 RGB，PIL 保留原始 RGB → 生成脚本和 StepHash 都按 `v*alpha//255` 预乘。
4. 1663 件中 1400 个唯一外观，87 组（350 件）图标完全相同（不同弹药/换色），dHash 无法区分，属物理上限。
