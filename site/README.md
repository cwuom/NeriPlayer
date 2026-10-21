# NeriPlayer 官网

[NeriPlayer](https://github.com/cwuom/NeriPlayer) 的官方网站：暖纸、网格与蜡笔线条的「音理线手账」，包含 Android 与桌面端展示、可交互播放器及音效演示。站点为纯静态文件，发布时无需构建，由 GitHub Pages 托管；翻译、字体与仿真素材使用本地工具预先生成。

## 本地预览

```bash
cd site
node tools/serve.mjs          # http://localhost:6099 ，站点文件内容变化后自动刷新
```

预览服务只使用 Node.js 内置模块。自动刷新依赖当前平台对递归 `fs.watch` 的支持；翻译和素材生成另需下文列出的浏览器与 Python 依赖。停止预览时在该终端按 `Ctrl+C`。

## 部署到 GitHub Pages

站点放在仓库的 `site/` 目录，由根目录的 `.github/workflows/site_pages.yml` 发布：

1. 仓库 **Settings → Pages → Build and deployment → Source** 选择 **GitHub Actions**（只需设置一次）。
2. 之后每次 `master` 上有 `site/` 的改动都会自动部署；也可以在 Actions 里手动运行 **NeriPlayer Website**。
3. 工作流只发布站点文件（`index.html`、`404.html`、`en/`、`assets/` 等），`site/tools/` 与 `site/i18n/` 不会上线。只改 `site/` 时不会触发 Android CI。

所有资源都使用相对路径，部署在项目页（`<user>.github.io/<repo>/`）或自定义域名下都能用；`404.html` 的样式与图形全部内联，并会自动探测项目页的首页地址。需要自定义域名时，在 `site/` 下放一个 `CNAME` 文件即可。

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

主页面的中文源是 `index.html`，`en/index.html` 由脚本生成，不要手改。桌面端仿真片段则由下文的桌面快照工具独立生成。`i18n.mjs` 的 `extract` 和 `build` 均依赖 `puppeteer-core` 与本机 Chrome；可用 `PUPPETEER_FROM` 指向已安装该包的目录，用 `CHROME` 指定浏览器可执行文件：

```bash
node tools/i18n.mjs extract   # 抽取翻译单元到 i18n/en.txt：保留已有译文，新增条目留空，删除失效条目
# 在 i18n/en.txt 里补齐空着的译文（"# " 开头的是中文原文，下面一行起是译文，行内 HTML 原样书写，{{n}} 是图标占位）
node tools/i18n.mjs build     # 生成 en/index.html：路径上移一级、语言链接互换、平板截图换成 -en 版本
```

脚本里动态写入的文案用 `NP.L('中文', 'English')` 按 `<html lang>` 选择；`404.html` 在 `/en/` 路径下或浏览器语言不是中文时显示英文。改了中文文案后先跑 `extract`，再跑 `build` 和字体子集。

## 重新生成素材

这些步骤都只在改动设计时才需要，生成结果已经提交在仓库里。
以下命令均从 `site/` 运行，浏览器工具运行前先在另一终端启动预览服务。Python 依赖可装在本地虚拟环境中；字体子集需要 `fonttools`、`brotli`，PNG 图标需要 `pillow`。

```bash
# 品牌图形：emblem / favicon / 图标精灵（写回 index.html 与 404.html 的 @sprite 标记之间）
node tools/build-brand.mjs

# 字体子集：先收集页面上实际用到的字符，再按字符子集化为 woff2
#   需要 puppeteer-core 与本机 Chrome；原始字体放在 tools/fonts-src/（不入库）：
#   Fraunces、Fraunces-Italic、Caveat、Geist、GeistMono、NotoSerifSC（可变字重）、LXGWWenKai，
#   以及手机仿真用的 Android 系统字体 Roboto-Regular.ttf、NotoSansCJK-Regular.ttc、DroidSansMono.ttf
#   （从模拟器 adb pull /system/fonts/<文件名> 取出）
node tools/collect-glyphs.mjs
python3 tools/subset-fonts.py

# 直接双击 index.html（file://）预览时的兜底：把桌面版仿真片段和示例曲包成 .js
#   改了 assets/desktop/sim-*.html 或 assets/audio/startrail.mp3 之后重新跑
node tools/file-fallback.mjs

# 分享图：需要 puppeteer-core 和已运行的预览服务
# 非默认 Windows Chrome 路径时，设置 CHROME 或在站点地址后传入 Chrome 路径
node tools/make-images.mjs

# PNG 图标：需要 pillow，显式传入 Android 仓库根目录以覆盖脚本的本机默认路径
python3 tools/make-icons.py ..
```

改了页面文案、尤其是标题与手写批注后，记得重新跑一遍字体子集，否则新字会回退到系统字体。

## 桌面端仿真素材

`tools/desktop-sim/site-mock.ts` 是桌面端快照的数据桩；`snapshot.mjs` 读取运行在 `http://localhost:1477/` 的桌面前端，分别导出中文和英文的 DOM、主题变量及样式。它需要另一个 `NeriPlayer-Desktop` checkout、`puppeteer-core` 和桌面前端所需依赖，目前浏览器路径固定为 Windows Chrome，其他环境执行前需要适配该路径。

已有双语快照时，可用以下命令重建网站片段和字体。`<snapshot-dir>` 需包含 `views-zh.json`、`views-en.json`、对应的 `meta-*.json` 和 `css-zh.json`；`<desktop-repo>` 指向桌面仓库根目录：

```bash
node tools/desktop-sim/build.mjs <snapshot-dir> <desktop-repo>
python3 tools/subset-icons.py <desktop-repo>
node tools/file-fallback.mjs
```

`build.mjs` 需要 `puppeteer-core`、Chrome 及可执行的 `python`（含 `fonttools`、`brotli`）。桌面端的 MiSans 与 Material Symbols 由这套工具处理，不在通用 `subset-fonts.py` 的生成范围内。网页仿真使用共享的 Web Audio 播放链路，不代表浏览器执行了 Android Native DSP 或真实桌面后端。

## 素材来源

- 示例曲《星轨》、封面与歌词时间轴来自 NeriPlayer PV（Neri Line）。
- 手机截图来自 Android 16 模拟器中运行的 NeriPlayer，平板截图来自 Pixel Tablet 模拟器（2560 × 1600 横屏），中英文各一套（`*-zh.jpg` / `*-en.jpg`）；桌面小组件图为 App 自带的预览图。
- 字体：Fraunces、Caveat、Geist、Noto Serif SC、霞鹜文楷，均为 SIL Open Font License。
- 手机仿真还使用 Android 字体子集；桌面端仿真使用桌面仓库提供的 MiSans 与 Material Symbols 子集。这些字体和导入素材保留各自的版权与许可，网站代码的 GPL-3.0 不替代其原有条款。

网站代码与 NeriPlayer 一样以 GPL-3.0 发布。
