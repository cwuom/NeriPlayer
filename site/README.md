# NeriPlayer 官网

[NeriPlayer](https://github.com/cwuom/NeriPlayer) 的官方网站：暖纸、网格与蜡笔线条的「音理线手账」。纯静态，零构建，由 GitHub Pages 托管。

## 本地预览

```bash
cd site
node tools/serve.mjs          # http://localhost:6099 ，站点文件内容变化后自动刷新
```

只需要 Node 18+，没有依赖。

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
| `assets/js/pitch-worklet.js` | 不改速度的移调（AudioWorklet） |
| `assets/js/demos.js` | 信号链、一起听、下载、统计等小演示 |
| `assets/js/github.js` | 从 GitHub API 读取最新版本、各 ABI 安装包、星标与下载量（带缓存） |
| `assets/js/main.js` | 启动顺序、导航、路线图与锚点跳转 |

## 英文版

中文 `index.html` 是唯一的源，`en/index.html` 由脚本生成，不要手改：

```bash
node tools/i18n.mjs extract   # 抽取翻译单元到 i18n/en.txt：保留已有译文，新增条目留空，删除失效条目
# 在 i18n/en.txt 里补齐空着的译文（"# " 开头的是中文原文，下面一行起是译文，行内 HTML 原样书写，{{n}} 是图标占位）
node tools/i18n.mjs build     # 生成 en/index.html：路径上移一级、语言链接互换、平板截图换成 -en 版本
```

脚本里动态写入的文案用 `NP.L('中文', 'English')` 按 `<html lang>` 选择；`404.html` 在 `/en/` 路径下或浏览器语言不是中文时显示英文。改了中文文案后先跑 `extract`，再跑 `build` 和字体子集。

## 重新生成素材

这些步骤都只在改动设计时才需要，生成结果已经提交在仓库里。

```bash
# 品牌图形：emblem / favicon / 图标精灵（写回 index.html 与 404.html 的 @sprite 标记之间）
node tools/build-brand.mjs

# 字体子集：先收集页面上实际用到的字符，再按字符子集化为 woff2
#   需要 puppeteer-core 与本机 Chrome；原始字体放在 tools/fonts-src/（不入库）：
#   Fraunces、Fraunces-Italic、Caveat、Geist、GeistMono、NotoSerifSC（可变字重）、LXGWWenKai
node tools/serve.mjs &
node tools/collect-glyphs.mjs
pip install fonttools brotli
python tools/subset-fonts.py

# 分享图与 PNG 图标（同样需要 puppeteer-core）
node tools/make-images.mjs
```

改了页面文案、尤其是标题与手写批注后，记得重新跑一遍字体子集，否则新字会回退到系统字体。

## 素材来源

- 示例曲《星轨》、封面与歌词时间轴来自 NeriPlayer PV（Neri Line）。
- 手机截图来自 Android 16 模拟器中运行的 NeriPlayer，平板截图来自 Pixel Tablet 模拟器（2560 × 1600 横屏），中英文各一套（`*-zh.jpg` / `*-en.jpg`）；桌面小组件图为 App 自带的预览图。
- 字体：Fraunces、Caveat、Geist、Noto Serif SC、霞鹜文楷，均为 SIL Open Font License。

网站代码与 NeriPlayer 一样以 GPL-3.0 发布。
