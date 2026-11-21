# 代码审查 · 2026-11-21 · 1.6.16w

范围：45,927 行 Java、6,748 行 app C++、引擎 C++ 的接口面、构建脚本与仓库状态。
方法：`javac -Xlint:all` 全量、26 类模式扫描、高危路径人工阅读。**没有**跑 Android
Lint（本机没有 gradle），也**没有**逐行读引擎的 27,938 行。

---

## P0 · 仓库里没有这台机器的源码 —— 已修（`9cc083b`）

最后提交曾是 `dbdba18 feat(1.6.2y)`，工作树是 1.6.16w：+33,317 / −12,549 行未提交，
9 个 Java 文件、5 个 C++ 文件未跟踪，`libfotufilmhost.so`(4.3 MB) 的源码完全不在仓库，
`assets/` 0 个文件跟踪，四个构建脚本在两棵树之间漂移。

已做：

- `app/src/main/cpp/fotufilm/` 收入 39 个文件 / 6.8 MB —— `host.cpp`、`tone.hpp`、
  `rebate/`（含生成器 `extract.py` 与它读的 `overscan.cpp`）、`print_frame.cpp`、
  以及全部标定头。与编出包内 `.so` 的源文件**逐字节相同**。
- `app/build/build-fotu-from-source.sh`（`FOTU_FROM_SOURCE=1`）从仓库编译；
  不设它时 stage 同一份字节并打印走了哪条路。
- 四个漂移的构建脚本已同步（仓库那份缺 `ultrahdr.cpp` 的 TU，`build-apk.sh` 缺
  `res/assets` 同步 —— 后者会打出黑主题）。
- `docs/REPRODUCING.md`：2,396 个二进制资产各自的来源，以及唯一还缺的生成器
  （`assemble.py`）。
- `MainActivity.kt` / `SpektraLabEngine.kt` / `*.bak-*` 从源码目录删除。

**仍未做**：`assemble.py`（462 组骨架的拼装器）不在任何地方；`assets/` 仍按设计不进 git。

---

## P1 · 真 bug

| # | 位置 | 问题 | 状态 |
|---|---|---|---|
| 1 | `DebugSink.makeRef` | `Date.getYear/getMonth/getDate` 读**默认历法**：泰语区产出 `SL-6901xx`（佛历），记录号是报障主键 | **已修**（`SimpleDateFormat("yyMMdd", Locale.US)`） |
| 2 | 46 处 | 用墙钟算「过了多久」，时钟回拨让拖动帧一直以为手指按着，前跳则中途发「停手」帧 | **已修**（`nowMs()` = `elapsedRealtime`；墙钟只留给要落盘/要发出去的戳） |
| 3 | manifest | `usesCleartextTraffic="true"` 全 App 放开，实际只有 `127.0.0.1:8770` 需要 | **已修**（`network_security_config` 只放行回环） |
| 4 | manifest | `allowBackup` 未声明 = true：`content://` URI 与 `filesDir` 路径会随云备份恢复到另一台机器 | **已修**（`allowBackup="false"`） |
| 5 | `MainActivity` 股票目录 | 唯一一处 `android.util.Log.w`，不进环形缓冲，用户报障看不到 | **已修**（`DebugSink.text`） |
| 6 | 源码目录 | 两个 1.9 MB `.bak` + 两个同名 Kotlin 骨架 | **已修** |
| 7 | `MainActivity:versionCode` | `PackageInfo.versionCode` 已废弃且是 int | **已修**（API 28+ 走 `getLongVersionCode()`） |
| 8 | `String.format("%02x")` | SHA-256 十六进制做缓存键，格式化没带 Locale | **已修** |

---

## P2 · 结构与可维护性

**已做**

- `scripts/audit.py` —— 静态闸门，8 条规则，带基线：新引入一个 toast、一个裸
  logcat、一个墙钟区间、一个无 Locale 的数字格式化、一个 `usesCleartextTraffic`、
  一个 `.kt`/`.bak`，或者把吞掉的异常从 421 变多，都判失败。
- `tools/fx_test.cpp` + `tools/regress.sh` —— 第一引擎的回归从「仓库外的脚本」
  变成一条命令，并在仓库里跑出 `913ed9890ef57520`。
- `docs/REGRESSION.md` —— 三条基线与两条判据（`.so` 未变就不必重跑；优化的唯一
  证明是 `cmp`）。

**没做，以及为什么**

- **拆 `MainActivity`（37,631 行，82% 的 Java）。** 这是十天级的重构，不是一次提交。
  分阶段的做法：① 把 `exportImage()`(1,066 行) 的六个阶段抽成 `ExportPlan` /
  `ExportSession` / `ExportWriter` 三个类，行为不变，先只搬；② 把 12 个面板的
  构建函数搬到 `ui/` 包，`MainActivity` 只留 `buildUi` 的编排；③ 把「帧状态」
  收进一个 `Frame` 值对象，让 `look` / `original` / `stock` 不再散落成 200 个字段。
  每一步都要能单独出包并过 `tools/regress.sh`。
- **单测。** 没有 gradle，最便宜的形态是继续用 `tools/` 下的探针 + 一份期望值表；
  `fx_test` 与 `scripts/audit.py` 是这条路的第一步。

---

## 编译面（记录，不是问题）

46k 行 Java **36 条 warning，全是 `[deprecation]`**，加 1 条 `[rawtypes]`。
C++ 侧：像素尺寸没有一处用 `int` 算，`memcpy` 全按行且带 `size_t`，
`row_stride_px ? row_stride_px : width` 在 9 处完全一致。

## 安全面

唯一导出组件是 `MainActivity`（launcher + `image/*` VIEW）；无 provider / service /
receiver；无 `WRITE_EXTERNAL_STORAGE`；源码里没有硬编码密钥。`file` scheme 的
intent-filter 在 targetSdk 36 下是死的，可以删（未删，因为它表达意图）。
