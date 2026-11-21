# 复现这个 APK

仓库里只有**代码**。跑起来还需要三块二进制资产，共约 2,400 个文件 / 79 MB，它们不进
git（见 `.gitignore`），但每一块都有确定的来源。

```
app/src/main/assets/
├── resources/   103 文件 / 30 MB   引擎的光谱表与配置
├── fotufilm/   2290 文件 / 48 MB   第二引擎的胶片档案与骨架
└── fonts/         3 文件 / 1.5 MB  OPPOSans + Inter
```

## 1. `resources/` —— 上游引擎的

从 `SpektraLab/engine/resources/` 原样拷过来（`profiles/`、`vulkan/`、`DI/`、
`print_luts.json`、`neutral_print_filters.json`、`paper_grades.json`、
`spektrafilm_constants.bin`），另外两份是本项目生成的：

| 文件 | 生成方式 |
|---|---|
| `stock_catalog.json` | `python3 app/build/gen-stock-catalog.py`（从 `profiles/` 扫出胶片目录） |
| `io/linear-sRGB.icc` | lcms 生成的 sRGB 曲线矩阵（604 B），来源见 `io/README` |
| `io/linear-P3.icc` | 同上，Display P3（在 fotufilm 那一侧） |

## 2. `fotufilm/` —— 上游 + 本项目生成

上游 fotufilm 带的（原样）：`catalog.txt`、`records-<stock>.txt` × 50、
`push.bin`、`camera_profiles.bin`、`camera_identities.txt`、`rec2020-reflectance-prior.coeff`、
`coupler_gamma.txt`、`donor.txt`、`film_flare.txt`、`io/linear-P3.icc`、三份 LICENSE。

本项目从上游 JSON 重新打包的：

| 文件 | 生成器 |
|---|---|
| `stocks.bin`（STK7，47 支，含 `sensitivityFloor`） | `tools/assets/gen_stk7.py` |
| `paper_*.bin` × 11（PAP1，8 字节 magic） | `tools/assets/gen_papers2.py` + `gen_paper_ektacolor.py` |
| `cfg/<stock>/<medium>/` **462 组骨架**（config.f32 / meta.txt / meter.bin / physio-*.txt） | 见下 |

**462 组骨架是烘出来的，不是算的。** `fotufilm-android-probe` 的 CI
（`.github/workflows`，job `tables`）在 x86_64 runner 上跑上游 Swift 引擎，
把每个 (stock, medium) 的 `config.f32` + 三张表 + input planes 落盘，作为 artifact
`spectral-tables` 发布（约 81 MB）；下载后用 `assemble.py art <assets-dir>`
拼成 94 份骨架再复制到本仓库的两个 `cfg/` 目录。

> **缺口**：`assemble.py` 目前不在仓库里（它当时只存在于构建机上）。补齐它之前，
> 462 组骨架只能从 artifact 反推。这是下一件要补的事。

## 3. `fonts/`

`OPPOSans.ttf`（5,226 字形子集）+ `Inter-Regular/Bold.otf`（230 字形）。
OFL/Apache 授权见 `fonts/` 旁的许可文件。

## 4. 依赖库

`app/build/build-libs.sh` 从 Termux 的 `$PREFIX/lib` 里取 `libraw` / `libjpeg` /
`lcms2` / `libz`，按 SONAME 等长改名后 stage 进 `lib/arm64-v8a/`（原因见
`build-libs.sh` 顶部注释）。这三个是第三方库，不随源码分发。

## 5. 第二引擎的 `.so`

`libfotufilmhost.so` 的**源码在本仓库**（`app/src/main/cpp/fotufilm/`），但它要链上游
fotufilm 的 Halide AOT kernel 归档和 `libfotufilmcodecs.so`。见 README 的「第二引擎」一节。
