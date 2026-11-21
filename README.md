# SpektraLab for Android

**安卓上目前最完整的胶片模拟。**

两个独立的光谱引擎装在同一台手机里：RAW 进，按物理过程算出胶片与相纸，成片出。
81 个波长（380–780 nm，5 nm 步长）穿过三层染料，颗粒按像素微米尺度播种，光晕、成色剂耦合、
放大机染料的相互抑制各自建模。**不是套 LUT。**

> 社区移植，不是上游官方版本。第一引擎来自 SpektraLab 的 `assist/vulkan-engine-1.3.4`
> （commit `445e314`），而它是 [spektrafilm](https://github.com/andreavolpato/spektrafilm)
> 那个引擎的 C++/Metal 原生移植；第二引擎是 [Fotufilm](https://fotufilm.com) 的引擎，
> 由本仓库用自己写的 C++ 宿主接进来。来历见文末「致谢与许可」。

**下载** · [官网](https://spektralab.workshop.skin/) · [直链](https://spektralab.workshop.skin/download) · [Releases](https://github.com/SAINTTaiYi/SpektraLab-Android/releases)

**当前版本** `1.7.0 (743)` · arm64-v8a · minSdk 24 · 约 29 MB · GPL-3.0-or-later

## 凭什么说「最完整」

不是形容词，是九条能验的事：

| 做到的事 | 具体到数字 |
|---|---|
| **光谱，不是 LUT** | 380–780 nm / 5 nm / 81 波段穿三层染料；相纸按它自己实测的感光曲线显影 |
| **72 支胶片** | 第一引擎 25 支（含 4 支实测黑白），第二引擎 47 支光谱档案；同片合并后 54 张卡 |
| **23 种输出介质** | 9 张相纸 ＋ 11 张相纸/拷贝片 ＋ 数字参考 / 负片本身 / Cineon Log |
| **17 种真实片门** | 135 全画幅 / 半格 / XPan、645、6×6 / 6×7 / 6×8 / 6×9 / 6×12 / 6×17、Super 35、16mm、Super 8、4×5、Instax mini / SQ / wide |
| **片边本身是曝光** | 片门阴影、齿孔、厂标、DX 边码、张数、日期都过颗粒和相纸；4×5 页片有缺口码（FP4 Plus / HP5 Plus） |
| **相纸的物理天花板** | 实测 **7.03 档**（C200 + Crystal Archive）；对比蒙版自动遮挡/加深，把 12 档场景印进 7 档纸 |
| **Ultra HDR** | 主图与 SDR 导出**逐字节相同**，另附 ISO 21496-1 增益图；HDR 屏上纸白以上真的有值 |
| **实况照片** | MotionPhoto v1 / v2 都认，逐帧冲洗后回写；源音轨 PCM 逐位保留 |
| **逐像素校准** | 第二引擎 **434 个可达组合**逐个对过官方实现：平均差 **0.33/255**、最差 0.36，真实相纸全部 **max 1** |

## 两个引擎

|  | 第一引擎 SpektraLab | 第二引擎 fotufilm |
|---|---|---|
| 形态 | Vulkan compute（GPU） | CPU，Halide AOT kernel |
| 胶片 | 25 支，含 4 支实测黑白 | 47 支光谱实测档案 |
| 输出 | 9 张相纸 | 11 张相纸 ＋ 数字参考 / 负片本身 / Cineon Log |
| 强项 | 片边、日期、印刷边框、构图、批量、TIFF / DNG | 胶片自身的色彩、输出介质、真胶片颗粒模型 |

货架是合并的：两支引擎都有同一支胶片时，只有一张卡，点开选引擎（卡角标「双引擎」）。
两个引擎共用同一份光学层实现——HSL 八段、锐化、纹理、曲线、分级、混色，所以换引擎不会换手感。

## 能做什么

| | |
|---|---|
| 胶片 / 相纸 | 72 支胶片、23 种输出介质，运行时从资源目录读取；黑白自动配 Ilford，电影卷自动配 2383 |
| RAW 解码 | 原生 LibRaw。CR2 / NEF / ARW / RW2 / RAF / DNG 等；尼康 HE / HE★ 明确报 `E-1108` 并说明怎么改相机设置，不用内嵌 JPEG 糊弄 |
| 画幅 · 片门 | 17 种，各自带真实片门尺寸；换片门 = 想开片边（自动裁成片门比例） |
| 片边 | 齿孔、片夹、雾、漏光、张数、厂标、DX 条码、半格对；模式 strip / filed |
| 印刷边框 | 15 种：不印 / 胶片边（底片·正像）/ 相纸 4×6 · 5×7 · 8×10 · 5×5 / 幻灯卡纸 2×2 / 片夹边·暗房 / 乳剂边·片门外 / 白 · 黑留白 / 方形 1:1 / 竖版 4:5 / 故事 9:16；胶片边可套 7 种片门画框 |
| 日期水印 | LCD 七段 / 点阵 / 拍摄数据三种样式，画面内四角或片边，可在画布上直接拖 |
| 辉光 | 光华 / 漫射 / 光晕 / 动感 / 拖影，逐参数对 Lightroom 真值标定（光华平均差 1.2/255） |
| 局部 | 椭圆遮挡 / 加深，按档加减光，羽化无边界 |
| 光学 | 亮度 / 颜色 / 效果 / 细节 ＋ HSL 八段 ＋ 曲线 ＋ 颜色分级 ＋ 混色；两个引擎共用 |
| 相纸宽容度 | 对比蒙版印片，五档（标准 / 强 / 暴力 / 极限），中灰不动 |
| 测光 | 8 种 ＋ 关闭，含「亮部重点」（亮度平方加权，人像补光压得下来） |
| 实时预览 | 分档：拖动 448 px（约 60–80 ms）→ 停手 260 ms 补 800 px → 松手 2048 px；第二引擎 420 / 900 |
| 导出 | JPEG ／ TIFF 16 位 ／ TIFF 线性 ／ DNG（线性 16 位，给电脑上的 Lr / ACR）／ Ultra HDR；三档尺寸 |
| 实况照片 | 成片全尺寸，动图逐帧冲洗后回写，音频直通 |
| 构图 | 按片门比例一键裁切、±45° 任意角度旋转、四个方向的 90° 与翻转 |
| 批量 | 批量导入、批量导出（可中途停止）、批量删除 |
| 预设 | 保存、分享成一段文本、从剪贴板导入；也能把当前参数一键套用到其它照片 |
| 撤销 | 最多 400 步 |
| 屏幕 | 竖屏单栏 / 横屏两栏（画布在左、参数在右，货架变两列） |

## 1.7.0 相对 1.6.2m

- **一台新引擎**：fotufilm，47 支胶片、14 种输出介质，走它自己的光谱链。
- **7 种新片门**：Super 35、16mm、Super 8、4×5 页片、Instax mini / SQ / wide；
  4×5 的缺口码、135 的 DX 边码、半格对（37:24，两格各印各的高光与暗部）。
- **辉光**：五种样式，方向与软化度都在，参数逐条对过 Lightroom。
- **局部遮挡 / 加深**、**相纸宽容度**、**高光提升三件套**、**HSL 八段 / 锐化 / 纹理**。
- **Ultra HDR 导出**、**实况照片**、**横屏两栏**。

### 速度

|  | 1.6.2m | 1.7.0 |
|---|---|---|
| 导入到首帧（20.7 MP） | 3.2 s | **2.6 s** |
| 全尺寸导出 | ≈9.0 s | **≈4.8 s** |
| 同一张再导出 | ≈9.0 s | **≈4.3 s** |
| 58 MP 片边画布 | 123.8 s / 3333 MiB | **28.8 s / 1336 MiB** |
| fotufilm 拖动帧 / 画布帧 2048 | — | **82 ms / 1.2 s** |

## 在手机上真正难的四件事

**1. 驱动只肯一次绑 128 MiB。**
遥测里 32% 的设备（几乎全是 Adreno 7xx）`maxStorageBufferRange` 只有 128 MiB，
而一张 20 MP 的中间平面就是 240 MB。做法是给每个 kernel 的绑定加窗口
（`dispatch_window(kernel, args, n, origin, read_bias)`），按行带发射，push constant 带基址。
分带与整块**逐字节相同**——44.7 MP 的帧在 128 MiB / 256 MiB / 512 MiB / 1 GiB / 2 GiB
五档绑定下，`rgba16` 的 FNV 哈希完全一样。77 MP 片边画布（9134×8435）在这类机型上也能跑完。

**2. 一次 dispatch 不能超过 65 535 个 workgroup。**
超了这块驱动会静默丢块——不是报错，是顶部若干行呈现未初始化显存。
`kDispatchElementWall = maxComputeWorkGroupCount[0] × 256`：Adreno 825 是 16,776,960 元素，
Mali-G925 报 1.1e12（一次就够，分带反而每带留一条缝）。所以片边四处手写分带全部改成
「装得下就不切」。

**3. 显存要自己管。**
池子是帧内复用的，`Gpu::alloc` 返回带引用计数的平面；导出结束后显式 `spk_trim_pool()`
把整幅中间产物还回去，否则下一次导出前系统会把桌面换出。

**4. 两个引擎不能同时进内核。**
第二引擎的 host 是文件级全局状态（场景缓存、选项块、Halide 状态都没有实例化），
画布渲染和货架卡片预取会撞在一起——症状是画布停在「渲染中」不回来。
所以 `FotuFilm.process` 持一把 `FOTU_HOST_LOCK`，预取在渲染期间让路，
再加 90 秒看门狗把状态还回去。

## 已知限制

- **任意角度旋转在 `maxStorageBufferRange = 128 MiB` 的机型上超过约 18° 会被拒绝**
  （一个输出行要读源图的一条斜线，必然绑整张源）。裁切、90° 与翻转不受影响。
- **第二引擎的 100 MP 全尺寸要 70–90 秒**（CPU 链，没有更快的档）；第一引擎不受影响。
- **第二引擎的片边不能改漏光、光圈值、日期亮度**：它的片边是画出来的，没有乳剂。
- **`.negative` / `.cineon-log` 是给调色用的**，不是成品。
- **相纸的宽容度是物理的 7 档**；要超过它只能靠 EDR 或 Ultra HDR 的输出域。
- **导出的 DNG 手机版 Lightroom 打不开**（Adobe 已知问题），定位是给电脑上的 Lr / ACR；
  手机端请导出 TIFF 后点「分享」发给 Lightroom。
- **Ilford 黑白纸不支持 EDR**（没有标定曲线），开关保留但对该纸不生效。
- **7 支胶片的色相校正被锁定**（Vision3 四支、CineStill 800T、Verita 200D、Pro 400H）：
  它们的绿区偏移在实测里方向与其它胶片相反，强行校正会更偏；可以关掉校正用原始色彩。
- 仓库不含约 80 MB 的二进制资源（光谱表、LUT、profile、47 支胶片的 cfg 骨架），见下面「构建」。

## 构建

前置：Termux（aarch64）、Android SDK 36 / NDK 28、上游引擎源码、约 20 GB 空闲。

```bash
# 1) 上游引擎 + 本项目的 Android 补丁
git clone https://github.com/JamesQiu2005/SpektraLab
cd SpektraLab && git checkout 445e314
git apply /path/to/patches/engine-android.patch

# 2) 资源：把 engine/resources/ 下的 profiles/、vulkan/、DI/、*.bin、*.json
#    拷到 app/src/main/assets/resources/
#    fotufilm 的资产（cfg/、stocks.bin、paper_*.bin、catalog.txt 等 2290 个文件）从
#    fotufilm-android-probe 的 CI 产物按 docs/REPRODUCING.md 生成

# 3) 六步构建，顺序不能省
bash app/build/build-engine.sh       # 引擎 TU（改过头文件会自动全量重编）
bash app/build/build-native.sh       # 三个 JNI + lrglow + ultrahdr → .so
bash app/build/build-fotu.sh         # 第二引擎的三个库（见下）
bash app/build/build-libs.sh         # staging 依赖库 + SONAME 等长改名
bash app/build/build-dex.sh          # javac → d8
bash build-apk.sh                    # aapt2 + 签名 + 依赖闭包检查
```

`build-libs.sh` 会把 `libjpeg.so.8` / `libz.so.1` 改名成 `libjpeg_8.so` / `libz_1.so`
并就地改写 `.dynstr`：AOSP 的安装器只解压 `lib/` 下以 `.so` 结尾的条目，带版本号的名字
会被静默跳过，然后在 `dlopen` 时报缺库。`check-libs.py` 会遍历每个 `.so` 的 `DT_NEEDED`
阻止这种包被打出来。

### 第二引擎（fotufilm）

它是两半：**上游 fotufilm**（Halide AOT kernel 归档 + `libfotufilmcodecs.so`，第三方，
不随本仓库分发）与**本项目的移植**（host、片边合成、印刷边框、标定头）。移植的源码就是
`app/src/main/cpp/fotufilm/`——`host.cpp`、`tone.hpp`、`rebate/`（由 `rebate/extract.py`
从引擎的 `overscan.cpp` 生成）、`print_frame.cpp` 等，共 39 个文件。

```bash
export FOTU_ART=/path/to/fotufilm/art      # include/ engine/ kernels/ jniLibs/
FOTU_FROM_SOURCE=1 bash app/build/build-fotu.sh
```

不设 `FOTU_FROM_SOURCE=1` 时，`build-fotu.sh` 从预编好的目录 stage 同一份字节（脚本会
打印它走了哪条路）。两条路的产物一致：仓库里的那份 `host.cpp` 就是编出当前 `.so` 的源文件。

## 目录

| 路径 | 内容 |
|---|---|
| `app/src/main/java/app/spektralab/` | Activity（UI 全部手写，无 AppCompat）、引擎 Java 门面、调试上报 |
| `app/src/main/cpp/` | JNI：会话、渲染、导出（libjpeg 直写 + ICC／EXIF）、RAW 打开、辉光、Ultra HDR |
| `app/src/main/cpp/fotufilm/` | 第二引擎的 C++ 宿主、片边合成、印刷边框、标定头 |
| `app/build/` | 引擎对象、JNI、依赖库、dex 的构建脚本 |
| `patches/engine-android.patch` | 对上游引擎的改动 |
| `docs/` | 分块渲染（`tiling.md`）、回归基线（`REGRESSION.md`）、资产复现（`REPRODUCING.md`）、已知问题 |
| `tools/` | `fx_test` 回归探针、资产生成器 |
| `build-apk.sh` | aapt2 + apksigner 打包 |

## 致谢与许可

这个引擎不是从零写的，它有明确的四段来历：

**1. 物理模型与数据 — [spektrafilm](https://github.com/andreavolpato/spektrafilm) by Andrea Volpato**
胶片过程模型、28 款实测彩色 profile、放大 LUT，原实现是 Python + Metal。
以 **CC BY-SA 4.0** 分发（见 `LICENSE-SPEKTRAFILM.txt`）。

**2. 原生移植 — [SpektraLab](https://github.com/JamesQiu2005/SpektraLab)**
把上面那个引擎重写成 C++ / Metal，并加上 macOS 应用；另有 4 款黑白 profile 由 Hanze Qiu 实测。
以 **GPL-3.0-or-later** 分发。本仓库的上游即其 `assist/vulkan-engine-1.3.4`（commit `445e314`）。

**3. 第二引擎 — [Fotufilm](https://fotufilm.com) by MUAStudio Inc.**
47 支胶片的 profile 与光谱样本、Halide kernel 与编解码库。引擎代码以 **Apache-2.0** 分发，
胶片档案以 **CC BY-SA 4.0** 分发（保留版权、注明改动）。本仓库通过自己写的 C++ 宿主调用它，
不修改其引擎内核。

**4. Android / Vulkan 移植 — 本仓库**
Vulkan 后端、JNI、UI、分带渲染与显存管理、色相校正标定、第二引擎宿主与片边/画框合成。
同样以 **GPL-3.0-or-later** 分发。

第一引擎的 `src/film`、`src/print`、`src/scan` 与 `resources/`（光谱表、profile、LUT）
保持上游原样；本仓库的改动集中在执行层——`src/gpu`、`src/pipeline` 的调度部分、
`src/shaders` 的索引与分带、`src/io` 的编码——共 64 个文件 / +8379 −731 行
（对比 `445e314` 可复验）。

代码以 **GPL-3.0-or-later** 分发（见 `LICENSE`）；胶片与相纸配置以 **CC BY-SA 4.0** 分发
（见 `LICENSE-SPEKTRAFILM.txt`）。

---

### English

An unofficial Android port of two spectral film engines.

RAW in, physically modelled film and paper out, entirely on the phone. 81 wavelengths
(380–780 nm, 5 nm steps) through three dye layers, grain seeded per pixel at micrometre scale,
halation, coupler coupling and dye-layer inhibition each modelled — not a LUT, not a preset.

**Two engines, one shelf.** The first is the SpektraLab engine (Vulkan compute): 25 films,
9 papers, the rebate, the date back, print frames, batch work, TIFF/DNG. The second is the
Fotufilm engine (CPU, Halide AOT kernels): 47 films and 14 output media, including two
Ilfochrome papers, a digital reference, the negative itself, and Cineon Log. Where both
engines carry the same film, the shelf shows one card and asks which engine to use.
The optics layer (HSL across eight bands, sharpening, texture, curves, grading, colour mixer)
is one shared implementation, so switching engines doesn't change the controls.

**Numbers that are checkable.** 434 reachable film × medium combinations matched against the
reference implementation: mean 0.33/255, worst 0.36, every real paper at max 1. Paper latitude
measured at 7.03 stops for C200 on Crystal Archive — a contrast mask prints a 12-stop scene
into that. Ultra HDR writes a gain map on top of a byte-identical SDR image. Live photos
(MotionPhoto v1 and v2) are developed frame by frame and written back with the original audio
bit-exact.

**What the Android port had to solve.** A 77 MP rebate canvas on drivers that refuse to bind
more than 128 MiB — banded dispatches with windowed bindings, byte-identical to the whole-plane
path (44.7 MP hashes the same across five binding tiers from 128 MiB to 2 GiB). The
65,535-workgroup wall, past which this driver silently drops blocks. An explicit pool trim after
every export so the launcher is not evicted. And two engines that cannot be inside the kernel at
the same time, because the second engine's host keeps file-level global state — one lock, and
the shelf prefetch yields to the canvas.

**Speed.** 20.7 MP from import to first frame: 3.2 s → 2.6 s. Full-size export: 9 s → 4.8 s.
A 58 MP rebate canvas: 123.8 s / 3333 MiB → 28.8 s / 1336 MiB.

**Known limits.** Arbitrary-angle rotation beyond about 18° is refused on 128 MiB devices
(it needs a whole-source binding). The second engine takes 70–90 s for a 100 MP frame, and its
drawn rebate can't change light leaks, aperture or date brightness. `.negative` and
`.cineon-log` are for grading, not finished prints. Paper latitude is physically 7 stops —
beyond that only EDR or the Ultra HDR output domain can go.

**Provenance, in four steps.** The model, its 28 measured colour profiles and the print LUTs
are [spektrafilm](https://github.com/andreavolpato/spektrafilm) by Andrea Volpato (CC BY-SA 4.0).
SpektraLab is a native C++/Metal port of that engine (GPL-3.0-or-later), and this repository
forks that port. The second engine is [Fotufilm](https://fotufilm.com) by MUAStudio Inc.
(Apache-2.0 engine, CC BY-SA 4.0 film archives), called through a host written here. The work
in this repository is the execution layer — `src/gpu`, the scheduling half of `src/pipeline`,
shader indexing and banding, `src/io`, the UI, and the second-engine host.

---

## 二次开发 / AI

- **改了就发。** 本仓库是 GPL-3.0-or-later：分发修改版（APK、上架、网盘、群文件、预装）必须同时公开**修改版的完整源码**，并保留全部声明。
- AI 编程助手请先读 [`AGENTS.md`](AGENTS.md) 与 [`AI-NOTICE.md`](AI-NOTICE.md)；机器可读版本在 [`.ai/notice.json`](.ai/notice.json)。
- 胶片与相纸的 profile、光谱数据走 CC BY-SA 4.0（spektrafilm、Fotufilm），改动后分发同样要求以相同许可公开。
