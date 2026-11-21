# 回归基线

三个引擎、三条基线。**第一条是硬闸门，后两条是发布前的人工步骤。**

## 0. 一条判据省掉两次重跑

引擎的 `.so` 逐字节相同 ⇒ 第一条基线不用重跑。

```bash
unzip -p SpektraLab-dev.apk lib/arm64-v8a/libspektrafilm_engine.so | md5sum
```

包内 md5 只要和上一个出过包的版本一致，`fx_test` 的哈希必然一致，因为哈希是那份
二进制的函数。真正变过引擎才重跑。

## 1. 第一引擎：一个哈希

```bash
SPK_LIB=<built>/lib/arm64-v8a SPK_RES=<built>/assets/resources bash tools/regress.sh
# ok  913ed9890ef57520
```

`tools/fx_test.cpp` 跑整条第一引擎，输出 16 位结果的 FNV 哈希 + 三个通道均值。

| 输入 | 均值 | 哈希 |
|---|---|---|
| `nef/z50_lossless.nef`（Nikon Z50，无损 NEF，26 MB，不随仓库分发） | 77.03 / 90.02 / 86.64 | **`913ed9890ef57520`** |

**这个哈希只属于这个文件。** 换一张照片工具照样能跑，但那个数就不是基线了 ——
用 `SPK_EXPECT=` 传你自己那张的基准值。

## 2. 第二引擎：1:1 对官方实现

fotufilm 的端到端真值不是哈希，是**逐像素对照上游 Swift 引擎**（`fotufilm_probe`）。
运行它需要 fotufilm 的 API 层与资产，见 `docs/REPRODUCING.md`。

已验收的数字（`IMG_20260205_165748.jpg`，675×900）：

| 场景 | 结果 |
|---|---|
| **434 个可达 (stock × medium) 组合** | 0 失败，平均的均值 **0.3315**，最差 0.36，max ≤ 2（只有 `screen` 到 2） |
| 10 张真实相纸 | 全部 **max 1 / >1 0.00%** |
| `.negative` / `.cineon-log` | 0.33 / max 1 |
| 胶片颗粒 tiles（`grainModel: film`） | 0.332 / max 1 |
| 包内 `.so` vs CLI | mean 0.0000 / max 0 |

## 3. 组合审计：368 个可达组合全部能出图

```bash
# 用**包内**的 .so 与**包内**的 assets，cwd 随便，paper 传裸名
fh_slots <assets>/cfg/<stock>/<medium>/ <photo> out.jpg <stock> <paper.bin> 0 [screen] [cache] "<slots>"
```

判据：`done: 368, 0 failures`，且每个产物 > 1 KB。清单与脚本在
`~/spk-debug`（还没搬进仓库，见 `docs/REPRODUCING.md` 的「缺口」）。

## 4. 逐位不变的判据

任何「只改调度 / 只改缓存」的优化，必须给出**同一输入、同一输出**的证据：

```bash
cmp before.ppm after.ppm      # 不是"看起来一样"
```

历史上有两次「优化」改动了像素而没人发现：`kSmallSigmaMax` 把 FIR/IIR 的分支
阈值改了，以及分带窗口的 `read_bias` 用了未对齐的起点。两次都只有在 `cmp` 面前
才现形。
