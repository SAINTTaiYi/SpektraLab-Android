# 01 · 现状审计（Interface Audit）

> 对象：`app.spektralab` / SpektraLab-Android v1.2.6（versionCode 25）
> 代码基线：`app/src/main/java/app/spektralab/MainActivity.java`（2667 行，界面全部由 Java 构建）
> 主题基线：`app/src/main/res/values/themes.xml`（6 行，`Theme.Material.Light.NoActionBar`）

---

## 1. 界面是怎么搭出来的

没有 XML 布局。整棵树在 `buildUi()`（L240–L653）里手搓：

```
FrameLayout root            #E3E3E0   ground（含 window insets padding）
└ LinearLayout app (V)      #F1F1EE   paper
  ├ intro        11/16/10                "SpektraLab" 30–39sp Light 蓝 + v1.2.6 7sp
  ├ workflow     46dp  4 格             01 导入 / 02 换图 / 03 渲染 / 04 导出（蓝底纸白字）
  └ lab (weight 1)
    ├ preview (weight 1)  #1C2AA5 蓝场
    │ ├ head      25dp    "IMAGE PREVIEW" 7sp + "(Empty / 000)" 7sp + 白 8% 细线
    │ ├ stage     (flex)  GuideView 参考线 / empty / CompareView / raw / busyLabel 20sp
    │ ├ busy       2dp    两条 indeterminate 进度条共用一个槽
    │ └ foot      23dp    文件名 7sp + 状态 7sp（右，maxWidth 62% 屏宽）
    └ controls    178dp    #F1F1EE
      ├ head      26dp    "LOOK SETTINGS" 7sp + Reset 7sp 下划线
      ├ tabs      29dp    01 胶片 / 02 光学 / 03 画幅 / 04 边框
      └ panelHolder (flex) 四个面板共用一个槽，setTab 切换可见性
```

界面规格的上游是一份 390px 视口的 HTML 参考稿（L90–L104 注释里写明了三处有意偏离）。也就是说：**当前视觉 = 印刷海报语言**——大字号负字距、蓝/纸两色、直角、细线、全大写小字。

## 2. 现有 token（唯一事实来源：L105–L112）

| Token | 值 | 用途 |
|---|---|---|
| `UI_BLUE` | `#1C2AA5` | 预览场底色、工作流 1/4 格、主强调、滑杆填充 |
| `UI_BLUE_LIGHT` | `#3B4BC0` | 工作流 2/3 格 |
| `UI_PAPER` | `#F1F1EE` | 面板底、蓝场上的文字 |
| `UI_GROUND` | `#E3E3E0` | 根底、系统栏 |
| `UI_INK` | `#171717` | 面板正文 |
| `UI_MUTED` | `#777772` | 次级说明、未激活 tab |
| `UI_LINE` | `#CECEC8` | 分区细线、滑杆轨道 |
| `UI_STAGE` | `#172174` | 声明了但未被使用 |

另有 **11 处颜色绕过 token 硬编码**：`0x2EFFFFFF`（蓝场细线 ×2）、`0x66171717`（开关描边）、`0x2EFFFFFF/0x14FFFFFF/0xA6FFFFFF`（GuideView）、`0xB3F1F1EE/0xFFF1F1EE/0xFF1C2AA5`（CompareView 分割线与手柄）。换肤时这些会漏。

## 3. 字号现状（实际出现的 sp 值）

`6, 7, 8, 9, 10, 13, 20, 25, 34, 35…48`

- 6–8sp 共 **19 处**：预览头、页脚状态、文件名、版本号、tab 名称、分区标题、滑杆名称/数值、下拉标签。
- 关键信息倒挂：文件名与渲染状态是用户最需要读的两行，却排在 7sp；装饰性的 `01/02/03` 序号拿到 25sp。

## 4. 触控尺寸现状

| 元素 | 高度 | 结论 |
|---|---|---|
| 工作流格 | 46dp | 达标 |
| tab 格 | 29dp | 偏小 |
| 开关行 | 26dp | 偏小 |
| 开关本体 | 12×12dp | 远小于 44dp |
| Reset 文本 | 7sp + 3dp padding | 实际命中区 ≈ 20dp |
| 下拉行 | 26dp | 偏小 |

## 5. 问题清单（按影响排序）

**A. 与"极简 + 偏暗"目标直接冲突**

1. **大面积高饱和蓝抢焦点**：预览场与工作流条都是 `#1C2AA5`，用户的眼睛先落在色块上，而不是照片。极简的前提是"照片是唯一的亮部"。
2. **整体亮色**：观片/调色类工具在暗环境使用，`#F1F1EE` 的整屏亮度等于把暗房灯打开；同时浅底会压低照片的对比判断。
3. **靠色块分层，不靠间距分层**：4 个蓝格 + 4 个 tab + 4 条滑杆 + 3 个下拉 + 2 条细线同屏出现，视觉噪音来自"每个区域都要有自己的底"。
4. **同屏双 CTA 竞争**：`03 渲染` 是隐藏在工作流里的动词格子，`04 导出` 在它旁边，两者权重相同，主次不明。

**B. 可用性缺陷**

5. **6–8sp 文字不可读**（中文尤甚，OPPO Sans 在 7sp 时笔画糊成一团）。
6. **触控目标普遍 < 44dp**（见上表）。
7. **状态提示只靠 Toast**：渲染失败、RAW 解析进度、导出进度在 Toast 里一闪而过；页脚那行 7sp 是唯一留存，却读不清。
8. **没有空状态到有状态的视觉引导**：`NO IMAGE` 35–48sp，副文案 6sp，中间差 6 倍，读起来像两张不同的稿子拼在一起。
9. **无 `contentDescription`**，tab 没有选中语义，读屏会把 `01光学` 念成两个元素。

**C. 系统一致性**

10. **平台浅色 AlertDialog 会击穿设计**：`showImportMenu` / `showExportDialog` / `confirmNativeImport` / `showAbout` 四处一弹窗就回到系统浅色，暗色改完这四处必须一起换。
11. **Spinner 是平台控件**：`inkAdapter` 只改了文字颜色，下拉列表仍是系统样式；四个下拉在暗色下会直接露白底。
12. **`lightSystemBars()` 强制亮色系统栏**（L172–L191），暗色主题下必须反向并同步 `themes.xml`。

**D. 工程债（会阻碍换肤）**

13. token 是 `MainActivity` 的私有常量，`CompareView` / `GuideView` 是静态内部类拿不到实例状态 → 颜色只能硬编码。
14. 面板高度写死 `178dp`（L644），四组内容靠"每行 weight 1"均分：小屏（360dp 宽 / 高 640dp 级）下总高 = 46 + 178 + 24 行头，stage 只剩约 40%，照片被压。
15. `setTab()` 通过 `getChildAt(0)` 再遍历子 `TextView` 改色（L906–L916），层级一变就崩，需要独立组件。
16. 无 `onConfigurationChanged` 处理：`configChanges` 保留了 Activity，但 `wordmarkSp()/emptyTitleSp()` 基于 `screenWidthDp` 只在 `buildUi()` 时算一次，旋转/分屏后字号不重算。
17. 字体从 `assets/fonts/` 读，仓库里不含 assets；`res/` 只有 `values/`，`@mipmap/ic_launcher` 缺失。**当前仓库不能独立构建**，必须在 Termux 侧用上游资源补齐（见 `build-apk.sh`）。

## 6. 必须保留的东西（重构不能动）

这是重设计的功能边界，一条都不能丢：

| 能力 | 代码位置 | 保留方式 |
|---|---|---|
| 导入（相册 / 文件 / 原生尺寸）三入口 | `pickImage` `pickRawFile` `confirmNativeImport` | 主按钮 + 长按菜单 |
| 换图（不重开 Activity） | `onWorkflow(1)` | 主按钮长按 / 导入后主按钮语义变"换图" |
| 渲染（含渲染中守卫） | `renderImage` | 唯一主 CTA |
| 对比：长按看原片、拖动分割线、点击收起 | `CompareView`（L1063–L1183） | 原样保留，仅改描线颜色 |
| 参数四组：胶片 / 光学 / 画幅 / 边框 | `panels[0..3]` | 四段分段控件 |
| 颗粒物理尺度（画面比例） | `formatSpinner` | 第三段 |
| 片边 + 日期水印 | `overscanMark` `dateMark` `edgeSpinner` | 第四段（改名"边角"或保留"边框"）|
| 导出三档 + 长按全尺寸 | `showExportDialog` `exportImage(tier)` | 底部"导出" + 长按 |
| 重置 | `resetLook` | 面板头右侧 |
| 关于 / 检查更新 / GitHub | `showAbout` `UpdateCheck` | 面板脚注行 |
| 错误码 E-xxxx | `SpkError` | 状态行 + 详情弹层 |
| 设备能力探测说明 | `probe.describe()` | 导出弹层副标题 |
| 字体方案 Inter + OPPO Sans | `loadUiFonts` | 沿用 |
| insets 处理 | `applySystemInsets` | 沿用，暗色栏 |

## 7. 审计结论

现有界面的**结构是对的**（预览在上、参数在下、四段分组、双指比较），问题集中在**视觉语言**（亮色、高饱和、色块分层、6–8sp）与**系统一致性**（平台弹窗/下拉/系统栏）。因此这次不做信息架构重写，做**视觉与密度重构**：保留 `buildUi()` 的骨架顺序与全部字段引用，换掉 token、字号、控件外壳和弹层实现。

→ 设计方向见 [02-design-system.md](02-design-system.md)，布局见 [03-layout-spec.md](03-layout-spec.md)，代码落点见 [04-implementation-plan.md](04-implementation-plan.md)。
