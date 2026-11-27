# SpektraLab for Android · Lightroom Mobile 风格 UI 重构版（v1.7.4t）

本分支在完整保留 **SpektraLab-Android `v1.7.4t`** 原版双光谱胶片引擎与全部后期能力的基础上，参考 **Adobe Lightroom Mobile（Android）** 的视觉语言与交互层级，对应用首页图库、编辑器底栏架构、多级参数抽屉、局部画布操作及深色工作室主题进行了全面重构，并补充了 Windows 纯 UI 打包脚本。

> **上游基线**：基于 [`SAINTTaiYi/SpektraLab-Android`](https://github.com/SAINTTaiYi/SpektraLab-Android)（commit `b17e73b` / tag `v1.7.4t`）  
> **修改日期**：`2026-11-27`  
> **修改范围**：仅 Android UI / 交互层与 Windows 构建脚本；C++ / Vulkan / Halide 引擎及 `CC BY-SA 4.0` 光谱数据未改动  
> **公开源码仓库**：<https://github.com/waisiwei17/SpektraLab-Android>  
> **开源协议**：`GPL-3.0-or-later`（详见 [LICENSE](LICENSE) 与 [AI-NOTICE.md](AI-NOTICE.md)）

---

## 直接下载可安装 APK

- **GitHub Release 页面**：<https://github.com/waisiwei17/SpektraLab-Android/releases/tag/v1.7.4t-lr-ui>
- **APK 直链下载**：[`SpektraLab-1.7.4t-lr-ui.apk`](https://github.com/waisiwei17/SpektraLab-Android/releases/download/v1.7.4t-lr-ui/SpektraLab-1.7.4t-lr-ui.apk)

### 安装说明
- **架构与系统要求**：`arm64-v8a`，Android 7.0+（`minSdkVersion 24` / `targetSdkVersion 36`）
- **签名提示**：该包使用本地调试签名证书（`CN=SpektraLab UI Dev`）进行 v2/v3 签名。如果手机上此前已安装官方签名版本（同包名 `app.spektralab`），**请先卸载原版后再安装本 APK**。

---

## 核心 UI / 交互改动详情

### 1. Lightroom 风格图库首屏（按导入日期分组 & 自动持久化）
- **日期分组浏览**（[LrLibraryView.java](app/src/main/java/app/spektralab/ui/LrLibraryView.java)、[LrLibraryItem.java](app/src/main/java/app/spektralab/ui/LrLibraryItem.java)）：应用启动默认进入「所有照片」图库页，按「今天 / 昨天（支持跨年判断）/ M月d日」时间分组，右上角支持 **3 列 / 4 列网格切换** 并持久化保存（`lib_cols`）。
- **轻量冷启动**：图库态冷启动时不再阻塞解码高像素 RAW 原图，待点击具体照片进入编辑器时再按需解码；右下角提供 LR 蓝圆形 `+` 悬浮导入按钮（FAB）。
- **多选批处理**：支持全选 / 全不选、单张参数一键应用到多张照片、选中子集批量导出以及批量移除照片。
- **往返自动保存**：从编辑器返回图库或切换至后台（`onPause`）时，自动保存每张照片的 Look JSON、编辑标记、胶片徽章与 `360px` 缩略图缓存（`filesDir/lib_thumbs/`），下次从图库点开可无损续接上次调色进度。

### 2. 方案 B 六段主底栏 + 分级子导航 + 可折叠抽屉
- **六段主底栏**（[LrBottomBar.java](app/src/main/java/app/spektralab/ui/LrBottomBar.java)）：采用方案 B 重新组织编辑器底部主导航为：
  `胶片 | 冲印 | 光学 | 局部 | 裁剪 | 边框`
  配套平滑滑动指示条与按压反馈。
- **二级胶囊栏与三级界面**（[LrSubBar.java](app/src/main/java/app/spektralab/ui/LrSubBar.java)）：支持可滚动胶囊式二级子菜单切换参数分类（如光学子项、裁剪与 17 种片门整合切换、边框 4 项直达），曲线 / HSL / 颜色分级等保持清晰的三级进入/返回层级。
- **可折叠抽屉与逐级返回**：参数面板上方新增居中拖拽手柄，支持展开/收起抽屉查看全屏画布；物理返回键与 Android 15 预测返回手势（`BackApi33`）支持从三级工具、二级子页、抽屉面板逐级退回图库，并在图库多选模式下先退出多选。

### 3. 独立「局部」面板与画布实时拖点蒙版
- 新增独立「局部」标签页及 [LocalSpotOverlay.java](app/src/main/java/app/spektralab/ui/LocalSpotOverlay.java)，可在照片画布上直接拖拽椭圆遮挡/加深中心点并直观调节范围半径与 EV 反馈，配合面板滑杆精细微调。

### 4. Lightroom 深色工作室视觉主题、动效与交互打磨
- **统一深空工作室配色**（[LrTheme.java](app/src/main/java/app/spektralab/ui/LrTheme.java)、[themes.xml](app/src/main/res/values/themes.xml)）：画布 `#0B0B0D`、工具栏 `#16161A`、底部抽屉与弹窗 `#1C1C21`、卡片 `#25252B`、强调色 `#3897F0`（LR Blue），统一状态栏/导航栏深色沉浸外观。
- **图标、动画与轨道滑杆**（[LrIcons.java](app/src/main/java/app/spektralab/ui/LrIcons.java)、[LrAnim.java](app/src/main/java/app/spektralab/ui/LrAnim.java)）：矢量绘制工具图标；居中原点双极轨道滑杆支持双击快速复位与触感反馈；顶部浮动半透明工具栏、磨砂 Toast 与竖屏纯净画布体验。
- **圆角底部弹窗**：统一重构动作表单、多选弹窗与导出面板为 `16dp` 顶部圆角面板、居中拖拽条、8dp 圆角选项卡片与 24dp 胶囊主操作按钮。
- **去除启动自动弹窗**：移除 `onCreate` 阶段自动弹出的微信/QQ 反馈群二维码弹窗，保留设置页内的手动「反馈群」入口。

---

## 原版功能 100% 完整保留

本次改动完全位于 Java UI 与交互编排层，原版 SpektraLab Android `1.7.4t` 的全部影像能力均原封不动保留：
- **双光谱引擎**：第一引擎 SpektraLab（Vulkan GPU）+ 第二引擎 Fotufilm（Halide CPU）及同片合并货架
- **完整胶片与介质库**：72 支胶片、23 种输出介质（相纸 / 拷贝片 / Cineon Log / 负片 / 数字参考）、相纸宽容度与 8 种测光方式
- **片门、片边与印制**：17 种真实片门、齿孔/厂标/DX 边码/漏光/张数、15 种印刷边框与画布可拖拽日期水印
- **光学、调色与特技**：辉光（5 种模式）、曲线、HSL 八段、颜色分级、混色、局部椭圆遮挡/加深、±45° 旋转与翻转裁切
- **导出与工作流**：JPEG / 16 位 TIFF / 线性 TIFF / DNG / Ultra HDR / 实况照片（MotionPhoto）、400 步撤销、预设保存与文本分享、批量导出与完整设置页（含「开源许可」）

---

## 文件结构清单

| 文件 | 用途说明 |
|---|---|
| `app/src/main/java/app/spektralab/ui/LrTheme.java` | LR 深色工作室色彩 Token 与圆角 / 涟漪 Drawable 工厂 |
| `app/src/main/java/app/spektralab/ui/LrIcons.java` | Vector/Canvas 矢量图标与顶栏按钮构建 |
| `app/src/main/java/app/spektralab/ui/LrAnim.java` | 面板切换、按压缩放、页面转场动画曲线工具 |
| `app/src/main/java/app/spektralab/ui/LrBottomBar.java` | 方案 B 六段底部主导航栏与滑动指示条 |
| `app/src/main/java/app/spektralab/ui/LrSubBar.java` | 可滚动胶囊二级子菜单组件 |
| `app/src/main/java/app/spektralab/ui/LocalSpotOverlay.java` | 「局部」工具在画布上的交互式位置 / 半径覆盖层 |
| `app/src/main/java/app/spektralab/ui/LrLibraryItem.java` | 图库条目数据模型（导入时间、原图/实况/调色状态、胶片徽章） |
| `app/src/main/java/app/spektralab/ui/LrLibraryView.java` | 按日期分组的 LR 风格图库网格、多选操作栏与 LRU 缩略图加载器 |
| `app/src/main/java/app/spektralab/MainActivity.java` | 图库首屏编排、方案 B 面板接入、Look 自动持久化、圆角弹窗与许可声明 |
| `app/src/main/res/values/themes.xml` | 统一暗色窗口与系统栏默认样式 |
| `build-ui-apk.ps1` | Windows 纯 UI 编译（`javac` + `d8` + `aapt`）与基于官方底包的重打包脚本 |
| `LIGHTROOM-UI.md` | 本 UI 重构版本说明与构建文档 |

---

## Windows 构建与打包方法

本仓库提供纯 Windows PowerShell 构建脚本 [`build-ui-apk.ps1`](build-ui-apk.ps1)（不依赖完整 Gradle / NDK 重编原生 `.so`）：

### 1. 仅校验 Java 编译、Dex 与资源打包
```powershell
.\build-ui-apk.ps1 -DexOnly
```

### 2. 结合官方 `1.7.4t` APK 重打包为可安装 APK
1. 将官方原版 `SpektraLab 1.7.4t` APK 放到仓库根目录并命名为 `base-official.apk`（或运行参数指定 `-BaseApk`）。
2. 执行脚本：
   ```powershell
   .\build-ui-apk.ps1
   ```
3. 脚本会使用 JDK 编出 `classes.dex`、用 `aapt` 编译清单与资源，并通过内嵌的 `ApkPacker.java` 保留 `resources.arsc` 等条目为 ZIP `STORED`（`method=0`）无压缩，再执行 `zipalign -p -f 4` 与 `apksigner` 签名校验。产物输出在：
   `app\build\SpektraLab-ui-signed.apk`

---

## 许可与致谢声明（GPL-3.0-or-later）

- 本衍生修改基于 [`SAINTTaiYi/SpektraLab-Android`](https://github.com/SAINTTaiYi/SpektraLab-Android)（commit `b17e73b` / `v1.7.4t`），仅针对 UI/交互层与 Windows 构建脚本进行修改与增补，未改动原始许可证、声明文件及应用内「开源许可」入口（且已在「开源许可」页新增本分支修改说明与源码仓库链接）。
- 依据 `GPL-3.0-or-later` 与 [AI-NOTICE.md](AI-NOTICE.md)，本分支及对应二进制分发物的完整源代码公开于：<https://github.com/waisiwei17/SpektraLab-Android>。
- 光谱模型、胶片/相纸配置文件等资产保留原作者（Andrea Volpato、Hanze Qiu、MUAStudio Inc. / Fotufilm）各自原始开源/知识共享协议（`CC BY-SA 4.0`、`Apache-2.0`、`GPL-3.0-or-later`）。
