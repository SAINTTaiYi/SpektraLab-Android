# 构建说明（已出包）

## 产物

| 项 | 值 |
|---|---|
| 文件 | [`dist/SpektraLab-1.3-minimal-arm64-v8a.apk`](../dist/SpektraLab-1.3-minimal-arm64-v8a.apk) |
| 大小 | 17.56 MB |
| 包名 | `app.spektralab`（与官方版相同 → 覆盖安装需先卸载官方版） |
| 版本 | versionCode **26** / versionName **1.3** |
| 最低系统 | Android 7.0（API 24），targetSdk 36 |
| ABI | arm64-v8a（官方包只有这一种） |
| 签名 | 本地 debug keystore（`apkbuild/spektralab-debug.keystore`，口令 `android`），v1+v2+v3 |
| SHA-256 | `9592E448B05D9E73BE893D549A7C21145087626B1C5E38CE965D00643192E5C9` |

## 这次是怎么打出来的

本机没有 Gradle、没有 Android Studio，用户目录还带中文（`C:\Users\梁\…`）。三个坑：

1. **原生工具读不了中文路径**：`aapt.exe` / `aapt2.exe` / `zipalign.exe` 遇到非 ASCII 路径直接 `Illegal byte sequence`。
2. **原生工具也不能在工作区外写文件**（沙箱）：`C:\spk` 只能读、不能写。
3. **仓库不含 `assets/` 与 `res/mipmap/`**：字体、引擎资源（约 30 MB）与图标不在 git 里，构建必须借官方包。

解法：

- `subst S: <workspace>` 把工作区映射成 ASCII 盘符 → 原生工具能读也能写，写进去的还是工作区里的文件；
- 官方 `SpektraLab-1.2.6.apk` 提供 7 个 `.so`、99 个 `assets/`（含 `stock_catalog.json`：25 胶片 + 9 相纸）与图标；
- 用户目录里的 `app/src/main/res`（暗色 `themes.xml`）由 `aapt` 正常编译，所以**暗色主题是本包自带的**，不是运行时改的。

## 复现

前置：

```
apkbuild/tools/jdk-17.0.20.1+1/     Temurin JDK 17
apkbuild/sdk/build-tools/36.0.0/    aapt / d8 / zipalign / apksigner
apkbuild/sdk/platforms/android-36/  android.jar
apkbuild/SpektraLab-1.2.6.apk       官方包（取 .so / assets / 图标）
```

命令（脚本会自己 `subst S:`、并把 `android.jar` 复制到 `C:\spk\aj.jar`）：

```powershell
python apkbuild\build3.py
```

脚本做的事：`javac`（`--release 11`，`-cp android.jar`）→ `d8`（min-api 24）→ `aapt package`（我们的 `res` + 官方 `assets`）→ 注入 `classes.dex` 与 7 个 `.so` → `zipalign -p 4` → `apksigner`（v1/v2/v3）→ `apksigner verify` + `aapt dump badging`。

注意：`javac` 在这台机器上无法自行创建目录，脚本会先建好 `classes/app/spektralab/{,ui,engine}`。

## 已做的校验

- `javac` / `d8` / `aapt` / `zipalign` / `apksigner` 全部 exit 0；
- `apksigner verify` 通过（v1+v2+v3）；
- `aapt dump badging`：包名、版本、minSdk 24、targetSdk 36、`native-code: arm64-v8a`；
- APK 结构：113 个条目 = `classes.dex`(120 KB) + 7 个 `.so` + 99 个 assets + `resources.arsc` + 图标 + 签名；
- `resources.arsc` 内含 `#0A0A0B` ×4 与 `#D9A55B` ×1，**不含**旧的 `#E3E3E0` / `#1C2AA5`；
- `classes.dex` 内含 `ui/` 七个类与中文片名（抽样：`依尔福 HP5 Plus 400`、`柯达 Tri-X 400`、`富士 阿库罗斯 100 II`、`CineStill 800T`）。

## 没验证的部分

- **没有真机/模拟器**，运行时行为（布局、手势、渲染、导出）未跑过；这是纯打包层面的绿灯。
- 装的时候会覆盖掉官方版的签名：同包名不同签名 → 必须先卸载官方版（设置里没有数据可留，只有默认参数）。
- 关于 / 检查更新 指向官方站点；本包版本号 26 > 官方 25，不会被提示更新。

## 装好后应该看到

1. 启动即近黑，无亮色闪屏（`themes.xml` 的 `windowBackground` 已是 `#0A0A0B`）；
2. 左上角琥珀色 `SpektraLab`，中间一行「选择一张照片开始」，下面一个描边按钮「选择照片」；
3. 选图后主按钮变「渲染胶片」；渲染完变「导出」；
4. 点「调色」从底部拉出四组参数（胶片 / 光学 / 画幅 / 更多），胶片列表是中文名 + 右侧「负片 / 反转 / 电影」。

## 桌面图标

![图标](../icon.png)

**暗房放大机**（App 里那条滑杆就叫「放大机曝光」）。做成实心剪影，比线稿在 48dp 更挺。

高级感来自四层叠加，全部烘进 PNG（不靠系统渲染）：

| 层 | 做法 |
|---|---|
| 底 | 竖向渐变 `#22170B → #0908 07` + 中心偏上的暖光 + 四周暗角 |
| 光 | 灯头位置一团 30/255 的暖光，高斯 2.8% 半径——暗示"灯是开的" |
| 形 | 实心剪影：底座 / 立柱 / 灯头（中间一条深色片夹缝）/ 镜头（暗一档做前后层次） |
| 边 | 圆角内侧 3px 的 24/255 高光，像玻璃边 |

- 图形缩到 86%（圆版 78%），四周留白更像"品牌图标"而不是"插画"；
- 自适应图标用 **背景图 + 前景图** 两套（渐变只能这样带进去），前景缩在 66/108 安全区；
- Android 13 主题图标用纯白剪影（去掉片夹缝），系统着色；

交付资源：`mipmap-{m,h,x,xx,xxx}dpi/ic_launcher(_round|_background|_foreground|_monochrome).png`、`mipmap-anydpi-v26/ic_launcher(_round).xml`。
生成脚本：`apkbuild/icons15.py`。

## 参数输入（v1.3.1）

调节弹层里的每条滑杆现在有两种落值方式：

- **拖动**：松手才提交，一次手势一次 JNI；
- **点右侧数值**：弹出数字输入（`ui/NumberSheet.java`），可直接键入、用 `−`/`+` 按 0.01 微调，超出范围自动夹紧，确定后只提交一次。

顺手修掉一个显示 bug：滑杆原来 36dp 高、insets 按 36 算，被 56dp 行高挤压后轨道被裁掉，界面上只剩一个圆点。现在轨道区 30dp、insets 13、**未选段纯白 90%、线宽 3dp**，并给数值加了 1px 琥珀下划线作为"可输入"的视觉暗示；光学组底部也加了一行说明。

### 导出方式的可发现性

「导出」默认一键出图（长边 2048），更多档位藏在长按里——为了不让人猜，动作栏下方加了一行 10sp 标识：

> 长按「导出」可选尺寸

只在「已渲染、非渲染中、非导出中」时显示（用 INVISIBLE 占位，避免状态切换时底部跳动），空状态与未渲染时整块隐藏。
