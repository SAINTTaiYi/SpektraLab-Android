# 04 · 实施方案（代码级）

> 目标：把 `buildUi()` 的视觉语言换成"暗房"，**不动**引擎/JNI/渲染/导入导出逻辑。

> **v2 补充（重要）**：本文的 P1/P2 落点写于「面板常驻版」（v1）。当前基线是 [05-minimal-spec.md](05-minimal-spec.md) 的极简版，差异如下，其余（P0 token/系统栏/字体、P3 状态与文案、P4 构建验收）照本文执行：
>
> | 本文写法 | 极简版实际做法 |
> |---|---|
> | 顶部 48dp TopBar（词标 + 版本） | **删除**；词标只在空状态左上角 13sp accent |
> | 四格工作流条 → ActionBar | **直接删除**；底部只有 `换图` `调色` + 主按钮 |
> | 44dp 配方行 / 268dp 展开面板 | **不常驻**，改 `ParamSheet` 底部弹层 |
> | 20dp 常驻状态行 | **取消**；仅错误/进度时出现一行 10sp |
> | `UiPicker` 挂在面板里 | 挂在弹层里，打开 `Sheet.list` |
> | 舞台 79% | 舞台约 **87%**（无面板占位） |

> 约束（来自仓库现状）：无 Gradle、无 AndroidX、无 Kotlin；构建是 `aapt + javac + d8 + apksigner`（`build-apk.sh`）；`minSdk 24 / target 36`；单 Activity；`assets/` 与 `res/mipmap/` 不在仓库内（需 Termux 侧用上游资源补齐）。

---

## 1. 包结构（新增 `app.spektralab.ui`）

```
app/src/main/java/app/spektralab/
├── MainActivity.java          ← 只保留状态与业务，界面构建降为 4 个方法
├── ui/
│   ├── Tokens.java            颜色/字号/间距/圆角/字体/等宽数字
│   ├── Chrome.java            系统栏反色、insets、手绘图形（Arrow/Chevron/Dots/Guide）
│   ├── Sheet.java             底部弹层（列表 / 说明 / 确认三种形态）
│   ├── UiPicker.java          值选择行（替换 Spinner）
│   ├── SegmentedTabs.java     四段参数切换（替换 setTab 的 childAt 遍历）
│   ├── SliderRow.java         滑杆行（替换 rangeRow + styleRange）
│   ├── ToggleRow.java         开关行（替换 toggleRow + Square）
│   └── UiState.java           7 个界面状态的枚举与文案
└── ...（engine / DeviceProbe / UpdateCheck / SpkError / DebugSink 不动）
```

`CompareView` / `GuideView` 从 `MainActivity` 的静态内部类改为 `ui/` 下的类，颜色由构造参数传入（治掉硬编码）。

---

## 2. 阶段任务

### P0 · token 与系统层（0.5 天）　✅ 已完成（2026-10-31）

| 文件 | 动作 |
|---|---|
| `ui/Tokens.java` | 新增：替换 `MainActivity` L105–L112 的 8 个常量；提供 `dp()/sp()/tf()/tnum()` |
| `res/values/themes.xml` | 重写为暗色（见 §4） |
| `MainActivity.lightSystemBars()` | 改为 `Chrome.darkSystemBars(window)`，清除 `LIGHT_STATUS_BARS/LIGHT_NAVIGATION_BARS` 并设栏色 `#0A0A0B` |
| `MainActivity.dp()/label()/hairline()` | 迁到 `Tokens`/`Chrome`，`label()` 增加字距与字重参数 |
| `loadUiFonts()` | 字体对象收进 `Tokens.fonts(context)` 单例，失败回退逻辑保留 |

**验收**：全工程 `grep` 不再出现 `0xFF1C2AA5|0xFF3B4BC0|0xFF172174|0xFFF1F1EE|0xFFE3E3E0`；系统栏为黑、状态栏图标为浅色。

### P1 · 外壳重排（1 天）　✅ 已完成（按 05 的极简版口径，见 README「P1 实际改了哪些代码」）

把 `buildUi()`（L240–L653）拆成：

```java
private void buildUi() {
    Tokens t = Tokens.get(this);
    root = new FrameLayout(this);  …           // bg.root
    app  = new LinearLayout(this); …           // 纵向
    app.addView(buildTopBar(),  lp(-1, 48));
    app.addView(buildStage(),   lp(-1, 0, 1f));// 舞台 flex
    app.addView(buildStatusLine(), lp(-1, 20));// 决策 A1
    app.addView(lookPanel.build(this), lp(-1, 44));// 收起态 44，可动画到 268
    app.addView(buildActionBar(), lp(-1, 56));
}
```

| 现有代码 | 处理 |
|---|---|
| L255–L279 intro（词标 + 版本） | → `buildTopBar()`：字号 20sp，去掉蓝，右端留空 |
| L282–L325 四格工作流 | **删除**，能力并入 `buildActionBar()`（导入/渲染/导出）+ 长按菜单 |
| L331–L477 preview（head/stage/foot/busy） | → `buildStage()`：去掉 head 25dp 带与 foot 23dp 带；文件名/尺寸改为舞台覆盖文字；保留 `GuideView`（仅空态）与 `CompareView`；两条 ProgressBar 合并为一条 1dp 线 |
| L480–L645 controls（head/tabs/panelHolder） | → `LookPanel`：头部 44（`重置`）、`SegmentedTabs` 40、内容区滚动；四个面板构造逻辑（L538–L641）逐条搬进对应 builder，**字段名不变** |
| L644 `lab.addView(controls, lp(-1, dp(178)))` | 写死的 178dp 取消，改 `wrap_content` + 最大 300dp |
| `setStep()` L900 | 删除（无序号） |
| `setTab()` L906 的 `getChildAt(0)` 遍历 | 换 `SegmentedTabs.setActive(int)` |

**验收**：390×844 下收起态舞台 ≥ 60%（实测用 `getHeight()` 打印）；展开态面板 ≤ 300dp；360dp 宽不裁字。

### P2 · 控件替换（1.5 天）

1. **`UiPicker`**（替换 4 个 `Spinner`）——保留现有监听器语义，改动最小：
   ```java
   // 旧：private Spinner filmSpinner;  filmSpinner = new Spinner(this);
   // 新：private UiPicker filmSpinner; filmSpinner = UiPicker.field(this, "胶片");
   // 旧：filmSpinner.setAdapter(inkAdapter(filmLabels));
   // 新：filmSpinner.setItems(filmLabels);
   // 旧：setOnItemSelectedListener(...) —— 签名保持不变，内部转调
   ```
   `UiPicker.setOnItemSelectedListener(AdapterView.OnItemSelectedListener)` 内部在选中后回调 `onItemSelected(null, row, pos, pos)`，因此 `loadStockCatalog`（L1275–L1305）里"反转片不能印到相纸"的拦截逻辑**一行不用改**。
   点击行 → `Sheet.list(...)` 选择，选中项琥珀 + 勾。

2. **`SliderRow`**（替换 `rangeRow` L803–L843 + `styleRange` L846–L875）：保持 `max=1000` 定点映射与"松手提交"语义；轨道/拇指改为 2dp 轨 + 12dp 圆环拇指；数值 `tnum` + 固定宽 48dp（消除抖动）。

3. **`ToggleRow`**（替换 `toggleRow` L760–L775 + `Square`）：18dp 方形，on = 琥珀填充 + 勾；行高 48dp。

4. **`Sheet`**（替换 4 处 `AlertDialog`）：

   | 现位置 | 新调用 |
   |---|---|
   | `showImportMenu()` L979 | `Sheet.list("导入", 三项, idx -> …)` |
   | `showExportDialog()` L999 | `Sheet.list("导出 · "+probe.describe(), 三项, …)` |
   | `confirmNativeImport()` L1221 | `Sheet.confirm("按原始尺寸导入", 说明段, "继续", …)` |
   | `showAbout()` L1238 | `Sheet.about(...)`（设备能力段 + 两行操作） |

5. **错误详情**：`SpkError.format()` 的全文从 `Toast.LENGTH_LONG` 改为 `Sheet.message()`；Toast 只保留 3 类非关键确认。

**验收**：暗色下无任何白底平台控件出现（弹层、下拉、对话框全链路点一遍）。

### P3 · 状态、文案与细节（1 天）

- 新增 `UiState`，收敛 63 处 `setText`：建立映射表（旧文案 → 新文案 → 状态），`setStage(0/1/2)` 保留兼容。
- 文案统一：去掉 `…` 与英文全大写混排；错误前缀 `[E-xxxx]` 保留；`已导出 … · … MB` 保留 6 秒后回落。
- 首次渲染才显示手势提示（`SharedPreferences` 键 `hint.compare`）。
- `CompareView` 分割线改 `accent` 40% + 12dp 琥珀环手柄（L1127–L1139 的 3 个硬编码色）。
- `onConfigurationChanged`：重算 `display` 字号与左右边距（16/20dp），面板高度重算（治 L214–L223 只算一次的问题）。
- 无障碍：给 4 个分段、3 个动作、4 个滑杆、4 个选择行加 `contentDescription` 与 `setSelected`。

### P4 · 构建与验收（0.5 天）

```bash
# Termux 侧（需先按 README 备好上游资源与 .so）
bash app/build/build-engine.sh
bash app/build/build-native.sh
cp app/build/native/libspektrafilm_engine.so app/build/apk/lib/arm64-v8a/
javac -source 17 -target 17 -d app/build/classes \
  -cp $ANDROID_HOME/platforms/android-36/android.jar @app/build/sources.txt
d8 --release --min-api 24 --lib $ANDROID_HOME/platforms/android-36/android.jar \
  --output app/build/dex $(find app/build/classes -name '*.class')
bash build-apk.sh
```
`app/build/sources.txt` 需追加 `ui/*.java`（现在是显式清单）。

---

## 3. 视觉验收清单（逐条可测）

- [ ] 舞台底色 `#000000`，面板 `#111112`，根 `#0A0A0B`；无蓝色残留（含 `CompareView`/`GuideView`/`Square`）。
- [ ] 全工程最小字号 = 10sp；中文标签 ≥ 12sp；无负字距。
- [ ] 所有可点元素命中区 ≥ 44dp（用 `Layout Inspector` 或 `getHitRect` 抽查 12 个）。
- [ ] 对比度：text.1/text.2/text.3/accent 分别为 16.5 / 6.9 / 4.8 / 8.4 : 1。
- [ ] 收起态舞台占可用高 ≈ 79%（≥ 72%）；展开态 ≥ 48%。
- [ ] 状态行在收起/展开两种形态下都可见，且不因文案变长而挤走文件名。
- [ ] 四个弹层与四个选择器全为暗色（无白底闪现）。
- [ ] 渲染中：照片变暗 + 1dp 琥珀线；失败：状态行转 `danger` 且能打开详情。
- [ ] 对比手势（长按/拖动/点按收起）行为与旧版完全一致。
- [ ] 360dp 宽、字体 130%、横屏、分屏四种场景不裁字不重叠。

## 4. 主题文件（草稿）

```xml
<style name="AppTheme" parent="android:Theme.Material.NoActionBar">
    <item name="android:windowBackground">#FF0A0A0B</item>
    <item name="android:colorBackground">#FF0A0A0B</item>
    <item name="android:statusBarColor">#FF0A0A0B</item>
    <item name="android:navigationBarColor">#FF0A0A0B</item>
    <item name="android:windowLightStatusBar">false</item>
    <item name="android:windowLightNavigationBar">false</item>
    <item name="android:colorAccent">#FFD9A55B</item>
    <item name="android:forceDarkAllowed" tools:targetApi="29">false</item>
    <item name="android:windowAnimationStyle">@null</item>
</style>
<style name="AppTheme.Sheet" parent="android:Theme.Material.Dialog.NoActionBar">
    <item name="android:windowBackground">@android:color/transparent</item>
    <item name="android:backgroundDimAmount">0.6</item>
    <item name="android:windowIsFloating">true</item>
</style>
```

## 5. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| `assets/`（字体、`resources/`）与 `res/mipmap/` 不在仓库 | 本地无法直接出包验证 | P4 前先按 README 拉上游资源；字体缺失已有 `Typeface` 回退，不阻塞布局验证 |
| `sources.txt` 是显式文件清单 | 新增 `ui/*.java` 忘记登记 → 编译期缺类 | P0 起同步维护，或改成 `find ... -name '*.java'` |
| `UiPicker` 替换改变回调时机（旧 Spinner 首次 `setSelection` 会触发一次回调） | 初始化时可能误触发渲染 | `UiPicker` 增加 `silent` 标记，构造后首次 `setSelection` 不回调（与 `setSelection(i,false)` 现状对齐，并在 P2 回归"选中即渲染"路径） |
| 面板由固定 178dp 改 wrap_content | 小屏展开后按钮被顶出屏幕 | 内容区 `ScrollView` + 面板 `maxHeight=300dp` + 动作栏固定在外层 |
| 去掉工作流四格后"换图"入口变隐蔽 | 老用户找不到 | 已导入状态下 `导入` 按钮文案变 `换图`，长按仍是三入口 |
| 状态行新增 20dp | 舞台再降 2.6% | 收起态仍 60%+；若需更严格，可把状态行并入动作栏的 56dp（用 background 的 `insetTop` 实现） |
| `configChanges` 保留 Activity | 旋转后字号/边距不重算 | P3 加 `onConfigurationChanged` |
| 平台下拉/对话框替换遗漏 | 暗色里闪白 | `grep -n "AlertDialog\|Spinner\|setDropDownViewResource"` 归零后验收 |
