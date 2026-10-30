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

## 分块驱动（已落地，2026-10-30）

`Gpu::dispatch_banded(kernel, args, n, TilePlan, error)`：

```cpp
struct TilePlan {
    uint32_t rows = 0;        // 整帧行数
    uint32_t columns = 0;     // 每行元素数（rows*columns 必须等于线程数）
    uint32_t band_rows = 0;   // 每带行数，0 = 不分块
    std::vector<uint32_t> stride;  // 每个参数的元素字节数，0 = 整块绑定
};
```

每个带把窗口设到它写出的那些行，**push constant 的 base 在每个窗口内从 0 重新开始**，
所以 shader 的 `spk_global_index()` 在本带内是从 0 数的——这正是窗口能工作的原因。

### 对齐比想象中更严格

`VkDescriptorBufferInfo.offset` 必须是 `minStorageBufferOffsetAlignment` 的倍数
（本机实测 **64 B**，不是 4）。每行的字节步长是 `columns * stride`，所以不是每一行
都能当带边界。驱动的做法是求各参数周期的**最小公倍数**当量子，再把带大小向上取整：

| 宽度 | stride | 每行字节 | 量子（行） | 实际每带 |
|---|---|---|---|---|
| 64 | 16 / 12 | 1024 / 768 | 1 | 请求值 |
| 65 | 16 / 12 | 1040 / 780 | 16 | 16 |
| 63 | 16 / 12 | 1008 / 756 | 16 | 16 |
| 100 | 16 / 12 | 1600 / 1200 | 4 | 4 |

量子为 1 时请求 1 行就真的是 1 行；否则自动放大到最近的合法边界。

### 验证

`band_test`（spk-debug，直接用 `Gpu` 跑 `spk_take_rgb`）：
1 行 / 8 行 / 32 行 / 64 行四种带大小，以及 63 / 64 / 65 / 100 四种宽度，
输出与整幅**逐字节相同**。整管线渲染哈希仍是 `0b3c0685f4c262d7`。

## push constant 变成 8 字节：{base, origin}

分带后 `base` 在每个窗口里从 0 重启。这对**只把索引当地址**的内核没问题，但对
**用索引播种或取几何**的内核是致命的——颗粒噪声会每带重来一次。

所以 push constant 现在有两个 word：

| 字段 | 含义 |
|---|---|
| `base` | 本 dispatch 在**窗口内**的起始元素（分块链用） |
| `origin` | 本窗口在**整帧**里的起始元素（分带时非 0） |

shader 侧新增 `spk_frame_index() = origin + base + local`，用索引播种/取几何的内核
必须用它，寻址仍用 `spk_global_index()`。改动到 5 个内核
（`grain_layer_one` / `grain_layers` / `grain_simple` / `lognormal_field` / `rng_probe`）
与 `scene_latitude`（空间梯度按整帧坐标）。整管线哈希仍 `0b3c0685f4c262d7`。

## 试过并撤掉的方案：按缓冲区大小推断步长

想法是 `stride = buffer_bytes / n`，省掉 30 多个调用点的改动。**行不通**：

缓冲池会把大块内存复用给小平面，`Buffer::bytes` 是分配大小而不是逻辑尺寸。
实测 `spk_lut2d_cubic` 被推断成 `a0=12, a1=4`，而 shader 实际读 8 和 12 字节/像素，
输出哈希立刻变了（分带反而更错）。所以自动分带已删除，只保留显式的
`dispatch_banded(...)`：步长必须由**知道内核布局的调用点**给出。

## 已接入分带的阶段（14 个）

`Pipeline::dispatch_plane(kernel, args, strides, shape, elements, error)`：只有当最宽的
平面超过 `maxStorageBufferRange` 时才分带；`SPK_FORCE_BAND_ROWS=n` 可强制分带，
用来在没有这个限制的设备上证明分带是精确的。

| 阶段 | 调度单位 | 步长 |
|---|---|---|
| `take_rgb` | 像素 | `c*4` / 12 |
| `curves` | 像素 | `c*4`…`c*4` |
| `matmul3` | 像素 | `c*4` / `c*4` |
| `couplers_correction` | 像素 | `c*4` / `c*4` |
| `highlight_headroom` | 像素 | `c*4` / `c*4` |
| `grain_layers` / `grain_layer_one` / `grain_simple` | 像素 | `c*4` / `c*4` |
| `cctf_decode` | 元素 | 4 / 4 |
| `print_exposure` | 元素 | 4 / 4 |
| `boost` / `log10_guarded` | 元素 | 4 / 4 |
| `glare_add` | 元素 | 4 / 4 / 4 |
| `mul` | 元素 | 4 / 4 / 4 |

**两种调度单位的步长不同**，这是踩过的坑：一个线程处理一个像素时，步长是 `c*4`
（12 字节）；一个线程处理一个元素时，步长是 4 字节。写错会让窗口是平面的 3 倍，
最后一带直接越界（`window argument 0 is outside its buffer`，渲染状态 -5、输出 NaN）。

验证：14 个阶段逐个单独强制分带，输出哈希全部等于整幅的 `0b3c0685f4c262d7`；
全部同时开启、每带 4 / 8 / 32 行，哈希同样不变。

## 2²⁴ 是一堵墙，不是预算

单个 dispatch 的元素数超过 16,777,216（= 2²⁴）时，**即使调用方已经把它切成分块**，
结果也是错的：尾巴的值会被写到头部，第 0 个元素就错。在这台设备上用两个完全不同的
kernel 测得边界一致（`spk_matmul3` 12 B/px、`spk_take_rgb` 16 B/px）：

| 元素数 | 结果 |
|---|---|
| 16,777,215 / 16,777,216 | 正确 |
| 16,777,217 起 | 错误 |

已排除：缓冲区大小（268 MB 正常、269 MB 出错）、分块数量（小图上强制 500 组一块完全一致）、
push constant 被后一块覆盖（改成每块单独提交，无变化）、矩阵绑定方式（内联同样错）、
"根本没写"（缓冲区先清零，坏值仍在）。

**分带在所有测试过的尺寸下都正确**，因为每个带的索引都从 0 重新开始。

这道墙正是 `DISPATCH_PIXEL_CAP = 16,490,040` 的来源：它卡在墙下方 287,176 像素处。
所以 1.2.3 的用户从未踩到——代价是超过 16.49 MP 的图会被静默缩小。

### 历史验证为什么没发现

1.2.3 的 20.3 MP 验证比的是「1 块 / 65 块 / 472 块切法是否相同」。墙带来的错位是对称的
——两种切法都按同样的方式越界——所以哈希一致，但一致地错。

## FIR：halo 与 read_bias

`spk_sep_fir_acc` 的纵向那一趟要读上下各 R 行，而窗口只能是它写出的那几行。做法：

- 输入窗口 = `[y0 - R, y1 + R)`（在帧边缘裁剪），输出窗口 = `[y0, y1)`
- 两者起点不同，所以 push constant 需要第三个字 `read_bias = (y0 - w0) × columns`：
  shader 里 `窗口内索引 = i + read_bias`，而 `绝对索引 = origin + i`（边缘反射要用绝对行号）
- 横向那一趟不跨行，halo 为 0，`read_bias` 也就恒为 0

未分带时 `origin = 0`、`read_bias = 0`，两个式子退化成原来的 `j * width + x`，
所以旧路径逐字节不变（整管线哈希仍是 `0b3c0685f4c262d7`）。

验证：全部阶段一起分带，每带 4 / 16 / 64 行，哈希均为 `0b3c0685f4c262d7`。

## cross_grid 的 8 个：源整块，输出分带

`stride_sample`、`zoom_bilinear_mirror`、`geometry_resample_df`、`view_geometry`、
`overscan_canvas` / `add_mask` / `film_present` / `light` 都是**跨网格取数**：按映射坐标
从另一张网格里读，写入自己这一格。所以它们的处理和其它阶段不同：

- 源参数**保持整块绑定**（`stride = 0`）
- 只有输出分带
- shader 里几何用 `origin + i`（`abs_i`），写回仍用 `i`

未分带时 `origin = 0`，`abs_i` 就是 `i`，与旧行为完全一致。

验证：全部阶段一起分带（含这 8 个），每带 4 / 16 / 64 行，哈希都是 `0b3c0685f4c262d7`。

## 剩下的未分带阶段

`iir_df_acc`（一个线程负责整列，线程数只有 width，**碰不到 2²⁴ 的墙**）、
`reduce_max`（全局归约，不可切）、以及若干编码/打包阶段
（`cctf_encode_matrix`、`to_rgba16`、`view_pack`、`view_pair`、`lut3d_trilinear`、
`di_normalise`、`spectral_epilogue`、`lognormal_field`、`scene_latitude`、`cctf_decode`、`matmul3`）。

## 导出不再复制：结果直接是映射内存

以前导出的峰值是两个整幅缓冲：GPU 里的 RGBA16（50 MP 约 400 MB）+ 读回到 malloc 的同样一份，
再加上一次全幅拷贝的时间。现在：

- `alloc_host_visible()` 分配 `host_storage` 缓冲（保留 STORAGE 用法，换成
  DEVICE_LOCAL|HOST_VISIBLE 的内存类型并映射）——统一内存上，shader 写的就是调用方读的
- `texture()` 对映射缓冲只做一次 invalidate，返回映射指针，并在 `retained_` 里持有一份引用
- `spk_result` 多一个 `owner`：释放时走后端虚函数，而不是静态的 `free()`。
  少了这一步会把映射指针当 malloc 释放，进程直接 abort（`Pointer tag … was truncated`）
- 指针键用**去掉 top-byte tag** 的地址比较：Android 会在同一次映射上带/不带 tag 返回

实测 50 MP：**103 s → 90 s**（-13%），峰值 RSS 792 → 788 MB。

### 峰值不在导出

内存没有明显下降，因为峰值出现在管线中段而不是导出：50 MP 时输入本身 612 MB，
加上管线里同时存活的几个平面。要让 64 MP 通过，下一个目标是**管线内平面数量**，
不是导出缓冲。

## 显存账：7 个平面，与尺寸无关

`frame_high_water_` 只统计 refs>0 的非持久缓冲，也就是**真正同时活着**的平面：

| 帧 | 单个平面 | 峰值 live | 折算 |
|---|---|---|---|
| 20.3 MP | 244 MiB | **1706 MiB** | 7.0 个平面 |
| 50.0 MP | 572 MiB | **4005 MiB** | 7.0 个平面 |

倍数恒定在 7。所以：

- 64 MP 需要约 5.1 GB
- 100 MP 需要约 8.4 GB

这台手机给不了。**继续按“整幅常驻”推进是死路**，再怎么算带内并行都不解决——
带内分块只解决单次绑定/调度，不解决平面总数。

试过并无效的一条：把底片缓存（`negative_for` 里的 `kept` 副本）在大图上关掉。
它对**进程内存**有意义，但那一份是 persistent，不进 live 统计，峰值一点没动。
现在留成 `SPK_NEGATIVE_CACHE_MAX_MP` 开关，默认关。

### 要支持 64 / 100 MP 只有一条路

**把整条链按带跑**：不是“每个 kernel 分带”，而是“取一个行带，依次跑完所有节点，
只把这一带的输出留下”。这样显存 ∝ 带高，而不是 ∝ 帧高。

按 512 行一带、100 MP（8660 行）算：内存约为整幅的 6%，7 个平面 ≈ 0.5 GB。

代价是每个节点都要接受行窗口，几何类节点（裁剪/旋转/重采样/画布）还要把带映射过去；
FIR 用 halo、IIR 用状态续跑——这两样的基础已经在 `dispatch_banded` 里了。

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
