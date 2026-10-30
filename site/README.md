# NeriPlayer 官网

[NeriPlayer](https://github.com/cwuom/NeriPlayer) 官方网站采用暖纸、网格与蜡笔线条的「音理线手账」设计，包含 Android 与桌面端展示、可交互播放器及音效演示。站点为纯静态文件，翻译、字体与仿真素材均在本地预生成，发布时无需构建并由 GitHub Pages 托管。

## 本地预览

```bash
cd site
node tools/serve.mjs          # http://localhost:6099 ，站点文件内容变化后自动刷新
```

预览服务仅使用 Node.js 内置模块，按 `Ctrl+C` 停止。文件变动自动刷新依赖当前操作系统的递归 `fs.watch` 支持；翻译与素材生成需额外配置下文列出的浏览器与 Python 依赖。

## 部署到 GitHub Pages

站点位于 `site/` 目录，由根目录的 `.github/workflows/site_pages.yml` 发布：

1. 在仓库 **Settings → Pages → Build and deployment → Source** 中选择 **GitHub Actions**（首次配置即可）。
2. `master` 分支包含 `site/` 改动时自动触发部署，也可在 Actions 页面手动运行 **NeriPlayer Website**。
3. 工作流仅打包上线站点资源（`index.html`、`404.html`、`en/`、`assets/` 及可选的 `CNAME`），排除 `site/tools/` 与 `site/i18n/`；仅修改 `site/` 不会触发 Android CI。

站点资源均采用相对路径，兼容项目页（`<user>.github.io/<repo>/`）与自定义域名部署。`404.html` 内联了全部样式与图形，可自动探测项目页首页地址；若在 `site/` 下放置 `CNAME` 文件，发布工作流会将其一并复制到产物根目录。

## 目录

| 路径 | 内容 |
| --- | --- |
| `index.html` | 全部页面内容与 SVG 图标精灵 |
| `en/index.html` | 英文版（由 `tools/i18n.mjs` 生成） |
| `i18n/en.txt` | 英文译文 |
| `assets/css/motion.css` | 动效、深色模式与主题切换 |
| `assets/js/motion.js` | 进入视口编排、标题逐字升起、滚动视差、主题拉绳 |
| `assets/css/base.css` | 设计令牌、纸张底纹、字体、通用组件、导航与路线图 |
| `assets/css/sections.css` | 各站台（章节）的版式与响应式 |
| `assets/css/phone.css` | 手机界面 1:1 重绘（坐标单位即 dp，按 411 × 914 dp 排版后整体缩放） |
| `assets/js/core.js` | 命名空间、平滑滚动、揭示动画、主循环 |
| `assets/js/gl.js` | 播放页流体背景：App 着色器 `hyper_background_effect.glsl` 的 WebGL 移植 |
| `assets/js/lyrics.js` | 《星轨》逐字歌词与两种同步歌词视图 |
| `assets/js/phone.js` | 共享播放器与信号链（移调、均衡、响度、音量）、波形进度条、首页调色 |
| `assets/js/phone-ui.js` | 播放页交互仿真：更多选项、Dock 面板、睡眠定时器、音效与倍速、主控位置、封面 / 歌词页切换、迷你播放器 |
| `assets/css/dsp.css`、`assets/js/dsp.js`、`assets/js/dsp-scope.js` | 音效设置交互、专业音频模式和信号可视化 |
| `assets/css/desktop.css`、`assets/js/desktop.js` | 桌面端站台、桌面歌词、托盘与快捷键演示 |
| `assets/js/desktop-sim.js`、`assets/desktop/sim-*.html`、`assets/desktop/sim.css` | 桌面端 DOM 仿真及页面、设置分区切换；手机上使用截图兜底 |
| `assets/css/desktop-fonts.css`、`assets/fonts/misans-*`、`assets/fonts/material-symbols.woff2` | 桌面端仿真的独立字体子集 |
| `assets/js/pitch-worklet.js` | 不改速度的移调（AudioWorklet） |
| `assets/js/demos.js` | 信号链、一起听、下载、统计等小演示 |
| `assets/js/github.js` | 从 GitHub API 读取最新版本、各 ABI 安装包、星标与下载量（带缓存） |
| `assets/js/main.js` | 启动顺序、导航、路线图与锚点跳转 |

## 英文版

`index.html` 是中文源页面，`en/index.html` 由 `tools/i18n.mjs` 生成（请勿手改），桌面端仿真片段则由桌面快照工具独立生成。

`i18n.mjs` 直接读取本地文件，无需启动预览服务，但 `extract` 和 `build` 均需 `puppeteer-core` 与本机 Chrome。可通过环境变量 `PUPPETEER_FROM` 指定已安装 `puppeteer-core` 的目录，通过 `CHROME` 指定浏览器可执行文件路径。

```bash
node tools/i18n.mjs extract   # 抽取翻译单元到 i18n/en.txt：保留已有译文，新增条目留空，删除失效条目
# 在 i18n/en.txt 里补齐空着的译文（"# " 开头的是中文原文，下面一行起是译文，行内 HTML 原样书写，{{n}} 是图标占位）
node tools/i18n.mjs build     # 生成 en/index.html：路径上移一级、语言链接互换、平板截图换成 -en 版本
```

| 步骤 | 作用 |
| --- | --- |
| `extract` | 抽取中文词条至 `i18n/en.txt`，保留已有译文、置空新增词条并清理失效条目 |
| 编辑 `i18n/en.txt` | 在 `# ` 中文原文下一行填写英文，原样保留行内 HTML 与 `{{n}}` 图标占位符 |
| `build` | 生成 `en/index.html`，自动上移一层相对路径、互换语言切换链接并将平板截图替换为 `-en` 版本 |

脚本动态文案通过 `NP.L('中文', 'English')` 按 `<html lang>` 切换；`404.html` 在 `/en/` 路径下或非中文浏览器语言环境下显示英文。修改中文文案后，按顺序执行 `extract`、补齐译文、`build`，最后重新生成字体子集。

## 重新生成素材

生成产物已提交至仓库，仅在修改对应设计或文案时需重新运行。以下命令均在 `site/` 目录下执行：

| 素材任务 | 命令 | 依赖与前置条件 |
| --- | --- | --- |
| 品牌图形（emblem / favicon / 图标精灵，写回 `index.html` 与 `404.html` 的 `@sprite` 标记之间） | `node tools/build-brand.mjs` | Node.js 内置模块 |
| 字体子集（收集页面实际字符并生成 `.woff2`） | `node tools/collect-glyphs.mjs`<br>`python3 tools/subset-fonts.py` | `collect-glyphs.mjs` 需先在另一终端启动 `node tools/serve.mjs` 预览服务，并依赖 `puppeteer-core` 与本机 Chrome；`subset-fonts.py` 需 Python 包 `fonttools`、`brotli` 及 `tools/fonts-src/` 下的源字体 |
| 本地 `file://` 预览兜底（将桌面仿真片段与示例音频打包为 `.js`） | `node tools/file-fallback.mjs` | Node.js 内置模块；手动修改 `assets/desktop/sim-*.html` 或 `assets/audio/startrail.mp3` 后运行 |
| 社交分享图 | `node tools/make-images.mjs` | 需已运行的预览服务、`puppeteer-core` 与 Chrome（非默认 Windows Chrome 路径时，设置 `CHROME` 或在站点地址参数后传入浏览器路径） |
| PNG 图标 | `python3 tools/make-icons.py ..` | Python 包 `pillow`；显式传入 Android 仓库根目录 `..` 以覆盖脚本默认路径 |

**字体子集源字体准备**：将不入库的原始字体放入 `tools/fonts-src/`，包含 `Fraunces`、`Fraunces-Italic`、`Caveat`、`Geist`、`GeistMono`、`NotoSerifSC`（可变字重）、`LXGWWenKai`，以及从 Android 模拟器通过 `adb pull /system/fonts/<文件名>` 提取的手机仿真系统字体 `Roboto-Regular.ttf`、`NotoSansCJK-Regular.ttc`、`DroidSansMono.ttf`。修改页面文案（尤其是标题与手写批注）后需重新运行字体子集，避免新增字符回退到系统默认字体。

## 桌面端仿真素材

桌面端网页仿真使用共享的 Web Audio 播放链路，不在浏览器中运行 Android Native DSP 或真实桌面后端。

导出快照前，先将 `site-mock.ts` 接入桌面工程的 `src/__site_mock.ts` 入口并启动前端，确认 `window.__SITE_QUEUE` 已就绪；快照脚本不会自动注入数据桩。

| 阶段 | 工具 / 输入 | 环境与依赖 |
| --- | --- | --- |
| 导出双语快照 | `tools/desktop-sim/site-mock.ts`（数据桩）<br>`tools/desktop-sim/snapshot.mjs` | 需独立的 `NeriPlayer-Desktop` 仓库、运行在 `http://localhost:1477/` 的桌面前端、`puppeteer-core` 与 Chrome（脚本内固定了 Windows Chrome 路径，其他环境运行前需修改路径） |
| 构建仿真片段与图标字体 | `tools/desktop-sim/build.mjs`<br>`<snapshot-dir>`（含 `views-zh.json`、`views-en.json`、`meta-zh.json`、`meta-en.json`、`css-zh.json`）与 `<desktop-repo>` | `puppeteer-core`、Chrome，以及安装了 `fonttools` 与 `brotli` 的可执行 `python` |

备妥双语快照后，运行主构建命令即可生成桌面端 HTML 片段、提取桌面端专用字体（MiSans 与 Material Symbols，不在通用 `subset-fonts.py` 处理范围内），并自动调用 `subset-icons.py` 与 `file-fallback.mjs`：

```bash
node tools/desktop-sim/build.mjs <snapshot-dir> <desktop-repo>
```

若仅需单独重跑图标字体子集或更新 `file://` 兜底脚本，可分别执行：

```bash
python3 tools/subset-icons.py <desktop-repo>
node tools/file-fallback.mjs
```

## 素材来源

- 示例曲《星轨》、封面与歌词时间轴来自 NeriPlayer PV（Neri Line）。
- 手机截图来自 Android 16 模拟器中运行的 NeriPlayer，平板截图来自 Pixel Tablet 模拟器（2560 × 1600 横屏），中英文各一套（`*-zh.jpg` / `*-en.jpg`）；桌面小组件图为 App 自带的预览图。
- 字体：Fraunces、Caveat、Geist、Noto Serif SC、霞鹜文楷，均为 SIL Open Font License。
- 手机仿真还使用 Android 字体子集；桌面端仿真使用桌面仓库提供的 MiSans 与 Material Symbols 子集。这些字体和导入素材保留各自的版权与许可，网站代码的 GPL-3.0 不替代其原有条款。

网站代码与 NeriPlayer 一样以 GPL-3.0 发布。
