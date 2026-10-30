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

需要按 kernel 标注：

| kernel | halo（行） | 说明 |
|---|---|---|
| `spk_sep_fir_acc` | `R`（FIR 半径） | 横/纵两向，纵向需要 R 行 |
| `spk_grain_*` | ~4 px | 颗粒微结构模糊 |
| `spk_halation_*` | 由格式决定 | 可能很大，需要实测 |
| `spk_glare_*` | 由格式决定 | 同上 |
| `spk_couplers_*` | 由扩散半径决定 | 同上 |
| `spk_*_resample` | 1–2 行 | 双线性 |
| 其余点算子 | 0 | |

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
2. 给 kernel 表加 `halo_rows` 字段，默认 0；空间算子逐个标注并用
   「整幅 vs 分块」比对验证。
3. 点算子先切（零风险），逐步把 halo 为 0 的 kernel 纳入。
4. FIR / 颗粒 / 光晕 / 眩光 / 重采样：两基址 + halo 窗口。
5. IIR：状态续跑。
6. 全部 kernel 标注完成后，才允许对超过 binding 上限的帧启用分块；
   在此之前保持现状（按上限降分辨率并告知）。

## 验收

- **逐块独立**：任一 kernel 在自己的带内分块渲染，与整幅渲染逐字节相同。
- **整管线**：50 MP 帧在 2 GiB 设备上"分块"与"整幅"逐字节相同；
  在 128 MiB 设备上能完成，且与 2 GiB 设备上的输出一致。
- **性能**：分块带来的额外 dispatch 数不超过 `行带数 × kernel 数`，
  512 行/带时 50 MP 约 12 带。
