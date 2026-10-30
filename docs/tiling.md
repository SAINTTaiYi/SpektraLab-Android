# 管线分块（Tiling）

目标：让任意尺寸的底片在任何设备上都能整幅渲染，而不是按设备上限降分辨率。

## 两个不同的上限

| | 限制 | 现状 |
|---|---|---|
| **A. dispatch** | 一次 `vkCmdDispatch` ≤ `maxComputeWorkGroupCount[0]`（65 535）× 256 线程 = 16,776,960 元素 | ✅ 已修（1.2.3）：按 65 535 组切块 + push constant 基址 |
| **B. binding** | 单个 descriptor 范围 ≤ `maxStorageBufferRange`（128 MiB ≈ 11 MP） | ❌ 未做 |

本文只讨论 B。

## 为什么 B 不能靠"绑大一点"绕过

`VkDescriptorBufferInfo` 的 `range` 就是那个上限。缓冲区本身可以更大（受
`maxMemoryAllocationSize` 约束），但**一次只能绑定其中一段**。所以任何 kernel 想读到
第 12 MB 之后的元素，就必须换一次绑定——也就是换一次 dispatch。

## 机制

一次 dispatch 覆盖一个**行带**（row band），每个参数按窗口绑定：

```
Arg::window(buf, offset_bytes, range_bytes)   // offset 必须 4 字节对齐
```

shader 侧**已经就绪**：1.2.3 的 `spk_pc.base` 就是"本块第一个元素"，
`spk_global_index()` 返回 `base + local`。当窗口从元素 `base` 开始时，shader 的
局部索引与窗口内偏移一致，无需再改。

### 点算子（无 halo）

直接切。窗口 = 本带的行，`base = y0 * W`。

### 空间算子（有 halo）

读邻居，所以**窗口要比写出区域大**：窗口 = `[y0 - H, y1 + H)`，dispatch 只覆盖
`[y0, y1)`，shader 的写索引要额外加 `H * W`。这需要**两个基址**：

- `read_base`：窗口起点（索引用）
- `write_base`：本带起点（写回用）

### 审计结果（2026-10-30，逐个读 shader 源码）

48 个 kernel 里只有 **2 个**有真正的行内纵向依赖。证据是源码里的索引算式：

| 规则 | 数量 | kernel | 纵向 halo |
|---|---|---|---|
| `pointwise` | 36 | 其余全部 | 0 |
| `fir_radius` | 1 | `spk_sep_fir_acc` | `meta[2]`（仅 `axis == 0`） |
| `iir_column` | 1 | `spk_iir_df_acc` | 无界（仅 `axis == 0`） |
| `cross_grid` | 8 | `stride_sample`、`zoom_bilinear_mirror`、`geometry_resample_df`、`view_geometry`、`overscan_canvas` / `add_mask` / `film_present` / `light` | 不适用，见下 |
| `global` | 2 | `reduce_max`、`split_probe` | 不可切 |

**与先前猜测不同的四处：**

- **颗粒没有 halo。** `spk_grain_layer_one` / `spk_grain_simple` 都是 `dst[3i+c]`
  只读 `cmy[3i+c]`，噪声由 `rng_init(i, …)` 以像素索引播种——逐像素独立。
  `spk_lognormal_field` 同理。原先估的"~4 px 模糊"不存在。
- **眩光与耦合是点算子。** `spk_glare_add` 是 `out[i] = in[i] + field[i]/100·illum[c]`；
  `spk_couplers_correction` 是逐像素矩阵。场由别的 pass 预生成。
- **FIR 只在纵向需要 halo。** `axis == 0` 读 `j = y ± radius`；`axis == 1` 只沿一行走，
  一行在窗口内天然完整。
- **IIR 的无限依赖只在纵向那趟。** `axis == 0` 时每条"线"是一列
  （`stride = width * 3`，长度 = height）；`axis == 1` 时每条线是一整行
  （`stride = 3`，长度 = width），行与行之间无关。而且它已经是"一次 dispatch 算
  若干整条线"的结构，本身就是为长依赖分块的。

结论：**真正挡住行带分块的只有 `spk_iir_df_acc` 的纵向趟**，外加
`spk_sep_fir_acc` 的一个半径。其余 46 个可直接切。

### cross_grid 不等于 halo

这 8 个 kernel 按映射坐标**跨网格取数**（重采样、步进采样、画布合成），读的可能是
很远的一行。它们不是"本带 ± H 行"，而是"**源必须整块可见、输出可分带**"。
若把源窗口也缩小到行带，它们会读到窗口外。

处理：这类 kernel 的源参数保持整块绑定（`Arg` 默认），只窗口化输出。前提是源缓冲区
本身 ≤ `maxStorageBufferRange`；超过时它们需要另一套取块策略（本轮不做，届时该帧
仍走降分辨率）。

### 落码

`KernelSpec` 增加 `TileRule tile` 字段，48 条全部标注
（pointwise 36 / fir_radius 1 / iir_column 1 / cross_grid 8 / global 2）。
本轮**只加标注不改行为**：重编后渲染哈希仍为 `0b3c0685f4c262d7`。

### IIR 是唯一的真难点

`spk_iir_df_acc` 的**纵向**递推是无限依赖：第 y 行依赖 0..y 全部历史。
按行切块必须**块间续跑状态**：

1. 每块结束把最后 2 行的递推状态（每个通道 2 个 float/列）写进一个小 buffer；
2. 下一块从该状态接着算；
3. 状态 buffer 极小（W × 3 × 2 × 4 B），任何设备都放得下。

这样能保持与整幅渲染**逐字节相同**。另一条路是"高像素时改用 FIR"，
实现简单但输出会变（不是 bit-exact），不作为默认。

## 实施顺序

1. `Arg` 增加 `(offset, range)`；`dispatch()` 绑定窗口；size 检查用 `range`。
   —— 不影响现有调用（默认 offset 0、range 全域）。
2. ✅ kernel 表加 `TileRule`，48 条逐个标注（审计结论见上）。
3. 点算子先切（36 个，零风险），用「整幅 vs 分块」逐字节比对。
4. `spk_sep_fir_acc` 纵向：两基址 + `meta[2]` 半径的 halo 窗口。
   cross_grid 的 8 个：源整块绑定，只切输出。
5. IIR：状态续跑。
6. 全部 kernel 标注完成后，才允许对超过 binding 上限的帧启用分块；
   在此之前保持现状（按上限降分辨率并告知）。

## 验收

- **逐块独立**：任一 kernel 在自己的带内分块渲染，与整幅渲染逐字节相同。
- **整管线**：50 MP 帧在 2 GiB 设备上"分块"与"整幅"逐字节相同；
  在 128 MiB 设备上能完成，且与 2 GiB 设备上的输出一致。
- **性能**：分块带来的额外 dispatch 数不超过 `行带数 × kernel 数`，
  512 行/带时 50 MP 约 12 带。
