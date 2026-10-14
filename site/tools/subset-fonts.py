"""把 tools/fonts-src 里的原始字体按 tools/glyphs.json 子集化为 assets/fonts/*.woff2。

用法：python tools/subset-fonts.py
依赖：pip install fonttools brotli
原始字体（不入库）：Fraunces / Fraunces-Italic / Caveat / Geist / GeistMono（Google Fonts），
NotoSerifSC（思源宋体可变字重）与 LXGWWenKai（霞鹜文楷），放进 tools/fonts-src/。
手机仿真用的是 Android 自带字体：Roboto-Regular.ttf、NotoSansCJK-Regular.ttc、DroidSansMono.ttf，
可以从模拟器 `adb pull /system/fonts/<文件名>` 取出。
"""
import io
import json
import os
from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "tools", "fonts-src")
OUT = os.path.join(ROOT, "assets", "fonts")

with open(os.path.join(ROOT, "tools", "glyphs.json"), encoding="utf-8") as f:
    GLYPHS = json.load(f)

LATIN = set(range(0x20, 0x7F)) | set(range(0xA0, 0x100)) | set(range(0x2010, 0x2028)) | set(range(0x2030, 0x203B))
LATIN |= set(range(0x2190, 0x219A)) | {0x2122, 0x2212, 0x21B7, 0x2713, 0x2715, 0x2116}


def is_cjk(cp):
    return cp >= 0x2E80 and not (0xFE00 <= cp <= 0xFE0F)


def codepoints(key, cjk):
    cps = {ord(c) for c in GLYPHS.get(key, "")}
    return {c for c in cps if is_cjk(c)} if cjk else {c for c in cps if not is_cjk(c)} | LATIN


JOBS = [
    ("Fraunces.ttf", "fraunces.woff2", codepoints("serif", False)),
    ("Fraunces-Italic.ttf", "fraunces-italic.woff2", {ord(c) for c in GLYPHS.get("serifItalic", "")} | set(range(0x20, 0x7F))),
    ("NotoSerifSC.ttf", "np-serif.woff2", codepoints("serif", True) | {0x3001, 0x3002, 0xFF0C, 0xFF01, 0xFF1F, 0xFF1A, 0x300A, 0x300B, 0x300C, 0x300D}),
    ("Caveat.ttf", "caveat.woff2", codepoints("hand", False)),
    ("LXGWWenKai.ttf", "np-hand.woff2", codepoints("hand", True) | {0x3001, 0x3002, 0xFF0C, 0xFF01, 0xFF1F, 0xFF1A}),
    ("Geist.ttf", "geist.woff2", codepoints("sans", False)),
    ("GeistMono.ttf", "geist-mono.woff2", codepoints("mono", False) | codepoints("sans", False)),
    # (文件, 输出, 字符, TTC 序号, 固定的可变轴)
    ("Roboto-Regular.ttf", "roboto.woff2", codepoints("phone", False), None, {"wdth": 100, "ital": 0}),
    ("NotoSansCJK-Regular.ttc", "np-phone-cjk.woff2", codepoints("phone", True) | {0x3001, 0x3002, 0xFF0C, 0xFF01, 0xFF1F, 0xFF1A, 0x300C, 0x300D, 0xFF08, 0xFF09}, 2, None),
    ("DroidSansMono.ttf", "droid-mono.woff2", LATIN, None, None),
]


def run():
    os.makedirs(OUT, exist_ok=True)
    for job in JOBS:
        src, dst, cps = job[:3]
        number = job[3] if len(job) > 3 else None
        pin = job[4] if len(job) > 4 else None
        font = TTFont(os.path.join(SRC, src), fontNumber=number if number is not None else -1)
        if pin:
            buf = io.BytesIO()
            instancer.instantiateVariableFont(font, pin).save(buf)
            buf.seek(0)
            font = TTFont(buf)
        cmap = font.getBestCmap()
        keep = sorted(c for c in cps if c in cmap)
        opts = subset.Options()
        opts.flavor = "woff2"
        opts.layout_features = ["*"]
        opts.hinting = False
        opts.name_IDs = [0, 1, 2, 3, 4, 5, 6]
        opts.notdef_outline = True
        sub = subset.Subsetter(opts)
        sub.populate(unicodes=keep)
        sub.subset(font)
        path = os.path.join(OUT, dst)
        font.flavor = "woff2"
        font.save(path)
        print(f"{dst:24s} {len(keep):5d} glyphs  {os.path.getsize(path) / 1024:7.1f} KB")


if __name__ == "__main__":
    run()
