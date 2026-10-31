# SpektraLab-Android · 界面重构（极简 · 暗色）

> 阶段：**界面已重构并出包** · v1.3（versionCode 26）
> 基线：`SAINTTaiYi/SpektraLab-Android` @ `main`（v1.2.6 / versionCode 25）→ 产物在 `dist/`

![极简版五屏](mockups/minimal.png)

① 空状态 ② 导入后 ③ 渲染完成 ④ 参数弹层 ⑤ 胶片列表（中文名）

## 安装包（已生成）

**dist/SpektraLab-1.3-minimal-arm64-v8a.apk** · 17.56 MB · versionCode 26 / 1.3 · Android 7.0+ · arm64-v8a
SHA-256 2A35F50C1492FEF69C09DBDF7AE6CB87B2F2D634FA5C742AD870A85E8EAF1D16

- 包名与官方版相同、**签名不同** → 安装前先卸载官方 SpektraLab；
- 自带暗色主题（resources.arsc 里已是 #0A0A0B），不是运行时改色；
- 构建细节与复现方式见 [BUILD.md](BUILD.md)。

---

## 这次只做一件事：把不用的东西拿掉

| | 现在 | 重构后 |
|---|---|---|
| 首屏可点元素 | 18 个（四格工作流 + 四 tab + 四滑杆 + 三下拉 + 两开关…） | **2 个** |
| 同屏文字节点 | 40+ | **5 个** |
| 字号档位 | 11 档（最小 6sp） | **4 档**（24 / 13 / 12 / 10） |
| 同屏色块 | 6 块（满屏蓝场 + 蓝格 + 纸白） | **1 个描边按钮** |
| 参数面板 | 常驻 178dp | **不常驻**，拉开才出现 |
| 下一步 | 要自己判断点 03 还是 04 | **主按钮就是下一步** |

三条规则：**一屏一个动作**、**参数拉开才出现**、**默认值就能出片**。

主按钮状态机（唯一需要学的东西）：

```
[ 选择照片 ] → [ 渲染胶片 ] → [ 导出 ]
                    ↓ 渲染中
               [ 渲染中… ]
                    ↓ 失败
               [ 重试渲染 ]
```

---

## 文档

| 文件 | 内容 |
|---|---|
| **[mockups/minimal.png](mockups/minimal.png)** | 极简版四屏效果图 |
| **[mockups/minimal-style.png](mockups/minimal-style.png)** | 极简版样式参考：色带 / 4 档字阶 / 6 个组件 / 密度对照 |
| [05-minimal-spec.md](05-minimal-spec.md) | **当前基线规格**：元素去向表、四屏尺寸、弹层规格、主按钮状态机、文案、验收 |
| [02-design-system.md](02-design-system.md) | 设计系统底层：色板对比度、间距、动效、无障碍（与极简版共用） |
| [04-implementation-plan.md](04-implementation-plan.md) | 代码落点：P0–P4、`Tokens.java` / `UiPicker.java` 草稿、风险表 |
| [01-audit.md](01-audit.md) | 现状审计：17 条问题、必须保留的能力表 |
| [03-layout-spec.md](03-layout-spec.md) · [mockups/redesign.png](mockups/redesign.png) · [mockups/style-reference.png](mockups/style-reference.png) | **归档**：面板常驻版（v1）方案，留作备选 |

色板（暗房）：`#0A0A0B` 底 · `#000000` 舞台 · `#111112` / `#191A1C` 面板与弹层 · `#EDECE8` 主文本 · `#D9A55B` 安全灯琥珀（只给当前值与主按钮）· `#E08A76` 错误。

---

## 已定 / 待定

1. ✅ **词标颜色**：`SpektraLab` = **和「选择照片 / 导出」同色同字号**（`#D9A55B`，13sp）。
2. ✅ **导航字颜色**：`换图` `调色` = `#A0A099`；弹层 `完成` = `#EDECE8`；`恢复默认` 与提示行 = `#82827A`；错误 = `#E08A76`。
3. ✅ **胶片 / 相纸中文名**：29 个已知 id 全部有译名，型号保留原文；表外片基按品牌自动推导（见 `05-minimal-spec.md` §6）。
4. ✅ **极简程度**：已确认（首屏只有照片 + 一个按钮）。
5. ⏳ **品牌色**：默认琥珀；想保留原来的蓝就整体换 `#6E7BD8`，只改一个 token。

---

## 进度

- ✅ 设计基线：极简版 v2（`05-minimal-spec.md`），词标琥珀、胶片列表中文化。
- ✅ **P0**：`ui/Tokens.java`（全部 token）、`ui/Chrome.java`（暗色系统栏 / 词标 / 主按钮 / 手绘图标）、`ui/FilmNames.java`（胶片与相纸中文名 + 兜底推导）。
- ✅ **P1（2026-10-31 已落地）**：`MainActivity.buildUi()` 整体重写；新增 `ui/Sheet.java`（列表 / 说明 / 确认三种弹层）、`ui/ParamSheet.java`（「调节」四组弹层）、`ui/SliderRow.java`、`ui/UiPicker.java`；`themes.xml` 转暗色；`app/build/sources.txt` 已登记全部新类。
- ⏭ **下一步：编译与真机走查**。本机没有 JDK / Android SDK，只做了静态核对（括号配平、API 交叉检查、悬空引用扫描），**代码尚未编译过**。按 `04-implementation-plan.md` §P4 在 Termux 侧出包。

### P1 实际改了哪些代码

| 文件 | 变化 |
|---|---|
| `MainActivity.java` | 2667 → 2331 行；`buildUi` 从 413 行降到 180 行；删 `loadUiFonts` / `lightSystemBars` / `applySystemInsets` / `selectRow` / `rangeRow` / `styleRange` / `inkAdapter` / `setStep` / `setTab` / `onWorkflow` / `getStatusBarHeight`；新增 `buildStage` / `buildFoot` / `buildPanel` / `onPrimary` / `refreshActions` / `say` / `toggleRow` / `rerenderIfPossible` |
| `ui/Tokens.java` | 新增：颜色、4 档字号、间距、字体、等宽数字 |
| `ui/Chrome.java` | 新增：暗色系统栏、insets、词标、主按钮（含 `setPrimary`）、文字动作、提示行、手绘 chevron / 箭头 / 手柄 / 发丝线 |
| `ui/Sheet.java` | 新增：底部弹层（`list` / `listWithBody` / `message` / `confirm`），替换 9 处平台 `AlertDialog` |
| `ui/ParamSheet.java` | 新增：调节弹层（胶片 / 光学 / 画幅 / 更多 四组 + 恢复默认 + 关于） |
| `ui/SliderRow.java` | 新增：56dp 滑杆行（2dp 轨 + 12dp 圆环拇指，松手才提交） |
| `ui/UiPicker.java` | 新增：值选择行，保留 `Spinner` 的监听器签名，4 处选值逻辑零改动 |
| `ui/FilmNames.java` | 新增：29 个 id 的中文名 + 品牌兜底推导 |
| `res/values/themes.xml` | 亮色 → 暗房（窗口底 `#0A0A0B`，系统栏同色，图标转亮） |
| `app/build/sources.txt` | 追加 7 个 ui 类 |

### 交互变化（功能一个没丢）

| 旧 | 新 |
|---|---|
| 01 格点按导入 / 长按三入口 | 「选择照片」点按导入；长按出三入口弹层 |
| 02 格换图 | `换图` 文本动作（长按同样出三入口） |
| 03 格渲染 | 主按钮「渲染胶片」 |
| 04 格导出 / 长按全尺寸 | 主按钮「导出」（快速档）；**长按出三档选择** |
| 面板四 tab 常驻 | 「调色」→ 弹层四组，一次一组 |
| 关于藏在词标长按 | 词标长按 + 弹层脚注「关于」两个入口 |
| 平台对话框 / 下拉 | `Sheet` 全暗色 |
