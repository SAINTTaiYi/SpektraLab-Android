# SpektraLab for Android

把 [SpektraLab](https://github.com/JamesQiu2005/SpektraLab) 的胶片／相纸模拟引擎搬到 Android 的移植。
打开一张 RAW，选胶片和相纸，得到一张按物理过程算出来的照片：81 个波长（380–780 nm，5 nm 步长）
穿过三层染料，颗粒按像素微米尺度播种，光晕、成色剂耦合、放大机染料的相互抑制各自建模。
**不是套 LUT。**

> 社区移植，不是上游官方版本。这份 C++ 引擎来自 SpektraLab 的 `assist/vulkan-engine-1.3.4`
> （commit `445e314`），而它是 [spektrafilm](https://github.com/andreavolpato/spektrafilm)
> 那个引擎的 C++/Metal 原生移植。来历见文末「致谢与许可」。

**下载** · [官网](https://spektralab.workshop.skin/) · [直链](https://spektralab.workshop.skin/download) · [Releases](https://github.com/SAINTTaiYi/SpektraLab-Android/releases)

**当前版本** `1.6.2m (385)` · arm64-v8a · minSdk 24 · 约 15.9 MB · GPL-3.0-or-later

## 能做什么

| | |
|---|---|
| 胶片／相纸 | 25 款胶片、9 款相纸，运行时从资源目录读取；黑白片自动配 Ilford，电影卷自动配 2383 |
| RAW 解码 | 原生 LibRaw。CR2／NEF／ARW／RW2／RAF／DNG 等；尼康 HE／HE★ 明确报 `E-1108` 并说明怎么改相机设置，不用内嵌 JPEG 糊弄 |
| 画幅 | 135 全画幅／半格／XPan、645、6×6、6×7、6×8、6×9、6×12、6×17，各自带真实片门尺寸 |
| 片边 | 齿孔、片夹、雾、漏光、张数、厂标、DX 条码；模式 strip／filed；画幅与片门绑定 |
| 日期水印 | LCD／点阵／数据背三种样式，可在画布上直接拖动定位 |
| 实时预览 | 拖参数约 **70 ms** 一帧（重印层 16 ms），松手再算全尺寸 |
| 导出 | JPEG／TIFF（16 位 + sRGB ICC）／DNG（线性 16 位，给电脑上的 Lightroom／ACR）；三档尺寸 |
| 构图 | 按片边比例一键裁切、±45° 任意角度旋转、四个方向的 90° 与翻转 |
| 批量 | 批量导入、批量导出（可中途停止）、批量删除 |
| 预设 | 保存、分享成一段文本、从剪贴板导入；也能把当前参数一键套用到其它照片 |
| 测光 | 7 种方式可选；「配光」按钮重新测光并显示三色滤镜结果 |
| 撤销 | 最多 400 步 |

## 这一版（1.6.2m）修了什么

- **片边文字**：全尺寸导出有，预览和竖幅照片也有。之前预览尺寸下笔画只剩 1–2 px、
  断成点被当成「刻度尺」，此前的处理是干脆不画；现在改成把笔画加粗 1 px 留着字。
- **导出花屏**：整幅变成噪声那种。现在导出完会拿成品和画布做灰度相关（正常 ≈0.97），
  不合格就不报「已保存」，并自动重导一次。
- **旋转松手跳回原位**：提交时快照几何，渲染线程不再读到被清零的字段。
- 竖幅片边空白、套用预设带着上一张的片门、批量导出后报错、批量导出途中点缩略图崩溃、
  小尺寸照片导入后无限闪退、切换界面必崩、Mali-G76／Adreno 630 完全无法渲染。

完整清单见 [Releases](https://github.com/SAINTTaiYi/SpektraLab-Android/releases)。

## 在手机上真正难的三件事

**1. 驱动只肯一次绑 128 MiB。**
遥测里 34.5% 的设备（几乎全是 Adreno 7xx）`maxStorageBufferRange` 只有 128 MiB，
而一张 20 MP 的中间平面就是 240 MB。做法是给每个 kernel 的绑定加窗口
（`dispatch_window(kernel, args, n, origin, read_bias)`），按行带发射，push constant 带基址。
分带与整块**逐字节相同**：63／64／65／100 宽 × 1／8／32 行全程验证，77 MP 片边画布
（9134×8435）在这类机型上也能跑完，峰值 1335 MiB。
顺带把导入预算从 8.95 MP 放开到约 32 MP（此后由可用内存决定，不再由绑定上限决定）。

**2. 一次 dispatch 不能超过 65 535 个 workgroup。**
超了这块驱动会静默丢块——不是报错，是顶部若干行呈现未初始化显存。
`kDispatchElementWall = maxComputeWorkGroupCount[0] × 256`：Adreno 825 是 16,776,960 元素，
Mali-G925 报 1.1e12（一次就够，分带反而每带留一条缝）。所以片边四处手写分带全部改成
「装得下就不切」。

**3. 显存要自己管。**
池子是帧内复用的，`Gpu::alloc` 返回带引用计数的平面；导出结束后显式 `spk_trim_pool()`
把整幅中间产物还回去，否则下一次导出前系统会把桌面换出。
58 MP 片边画布（9102×6336）实测 **28.8 秒**、峰值 **1335 MiB**；20.9 MP 带片边导出
（5973×3222）渲染 10.1 秒。

## 已知限制

- **任意角度旋转在 `maxStorageBufferRange = 128 MiB` 的机型上会失败**（`E-1001`）。
  一个输出行要读源图的一条斜线，必然绑整张源。裁切、90° 与翻转不受影响。
- **对比度蒙版**（`contrast_mask_*`）上游 Vulkan 后端未移植，接口里显式拒绝，不静默忽略。
- **导出的 DNG 手机版 Lightroom 打不开**（Adobe 已知问题），定位是给电脑上的 Lr／ACR 用；
  手机端请导出 TIFF 后点「分享」发给 Lightroom。
- **Ilford 黑白纸不支持 EDR**（没有标定曲线），开关保留但对该纸不生效。
- **7 支胶片的色相校正被锁定**（Vision3 四支、CineStill 800T、Verita 200D、Pro 400H）：
  它们的绿区偏移在实测里方向与其它胶片相反，强行校正会更偏；可以关掉校正用原始色彩。
- 仓库不含约 30 MB 的二进制资源（光谱表、LUT、profile），见下面「构建」。

## 构建

前置：Termux（aarch64）、Android SDK 36 / NDK 28、上游引擎源码、约 15 GB 空闲。

```bash
# 1) 上游引擎 + 本项目的 Android 补丁
git clone https://github.com/JamesQiu2005/SpektraLab
cd SpektraLab && git checkout 445e314
git apply /path/to/patches/engine-android.patch

# 2) 资源：把 engine/resources/ 下的 profiles/、vulkan/、DI/、*.bin、*.json
#    拷到 app/src/main/assets/resources/

# 3) 五步构建，顺序不能省
bash app/build/build-engine.sh     # 引擎 TU（改过头文件会自动全量重编）
bash app/build/build-native.sh     # 两个 JNI → .so
bash app/build/build-libs.sh       # staging 依赖库 + SONAME 等长改名
bash app/build/build-dex.sh        # javac → d8
bash build-apk.sh                  # aapt2 + 签名 + 依赖闭包检查
```

`build-libs.sh` 会把 `libjpeg.so.8` / `libz.so.1` 改名成 `libjpeg_8.so` / `libz_1.so`
并就地改写 `.dynstr`：AOSP 的安装器只解压 `lib/` 下以 `.so` 结尾的条目，带版本号的名字
会被静默跳过，然后在 `dlopen` 时报缺库。`check-libs.py` 会遍历每个 `.so` 的 `DT_NEEDED`
阻止这种包被打出来。

## 目录

| 路径 | 内容 |
|---|---|
| `app/src/main/java/app/spektralab/` | Activity（UI 全部手写，无 AppCompat）、引擎 Java 门面、调试上报 |
| `app/src/main/cpp/` | JNI：会话、渲染、导出（libjpeg 直写 + ICC／EXIF）、RAW 打开 |
| `app/build/` | 引擎对象、JNI、依赖库、dex 的构建脚本 |
| `patches/engine-android.patch` | 对上游引擎的改动 |
| `docs/` | 分块渲染设计（`tiling.md`）等 |
| `build-apk.sh` | aapt2 + apksigner 打包 |

## 致谢与许可

这个引擎不是从零写的，它有明确的三段来历：

**1. 物理模型与数据 — [spektrafilm](https://github.com/andreavolpato/spektrafilm) by Andrea Volpato**
胶片过程模型、28 款实测彩色 profile、放大 LUT，原实现是 Python + Metal。
以 **CC BY-SA 4.0** 分发（见 `LICENSE-SPEKTRAFILM.txt`）。

**2. 原生移植 — [SpektraLab](https://github.com/JamesQiu2005/SpektraLab)**
把上面那个引擎重写成 C++ / Metal，并加上 macOS 应用；另有 4 款黑白 profile 由 Hanze Qiu 实测。
以 **GPL-3.0-or-later** 分发。本仓库的上游即其 `assist/vulkan-engine-1.3.4`（commit `445e314`）。

**3. Android / Vulkan 移植 — 本仓库**
Vulkan 后端、JNI、UI、分带渲染与显存管理、色相校正标定。同样以 **GPL-3.0-or-later** 分发。

引擎里的 `src/film`、`src/print`、`src/scan` 与 `resources/`（光谱表、profile、LUT）
保持上游原样；本仓库的改动集中在执行层——`src/gpu`、`src/pipeline` 的调度部分、
`src/shaders` 的索引与分带、`src/io` 的编码——共 64 个文件 / +8379 −731 行
（对比 `445e314` 可复验）。

代码以 **GPL-3.0-or-later** 分发（见 `LICENSE`）；胶片与相纸配置以 **CC BY-SA 4.0** 分发
（见 `LICENSE-SPEKTRAFILM.txt`）。

---

### English

An unofficial Android port of the SpektraLab film and paper simulation engine (Vulkan compute).
RAW in, physically modelled film + paper out, entirely on the phone: 81 wavelengths through
three dye layers, grain seeded per pixel at micrometre scale, halation, coupler coupling and
dye-layer inhibition each modelled — not a LUT.

**Provenance, in three steps.** The model, its 28 measured colour profiles and the print LUTs
are [spektrafilm](https://github.com/andreavolpato/spektrafilm) by Andrea Volpato (CC BY-SA 4.0).
SpektraLab is a native C++/Metal port of that engine (GPL-3.0-or-later), and this repository
forks that port. The film, print and scan models and the resource tables are untouched here;
the work in this repository is the execution layer — `src/gpu`, the scheduling half of
`src/pipeline`, shader indexing and banding, and `src/io` — 64 files, +8379/−731 against
`445e314`.

25 films, 9 papers, real film gates from 135 half-frame to 6×17, rebate with sprockets, fog,
leaks and DX codes, draggable date back, 70 ms live preview, 400-step undo, batch import and
export, presets you can share as text.

What the Android port had to solve: a 77 MP rebate canvas on drivers that refuse to bind more
than 128 MiB (banded dispatches with windowed bindings, byte-identical to the whole-plane
path); the 65,535-workgroup wall, past which this driver silently drops blocks; and an explicit
pool trim after every export so the launcher is not evicted.

Known limits: arbitrary-angle rotation needs a whole-source binding and is refused on 128 MiB
devices; contrast masks are not implemented by the Vulkan backend; DNGs are for desktop
Lightroom — use TIFF + share from the phone.
