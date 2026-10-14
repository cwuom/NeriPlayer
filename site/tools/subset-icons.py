"""桌面端仿真用的 Material Symbols 子集：按字体自己的连字表找图标，而不是猜字形名。

用法：python tools/subset-icons.py <NeriPlayer-Desktop 仓库目录>
从 assets/desktop/sim-*.html 里的 material-symbols 文本和 assets/js/desktop-sim.js 里的图标名收集要用的图标，
固定 wght 400 / GRAD 0 / opsz 24，保留 FILL 轴，输出 assets/fonts/material-symbols.woff2。
"""
import io
import os
import re
import sys

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DESK = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "..", "_np_ref", "desktop")
SRC = os.path.join(DESK, "public", "fonts", "MaterialSymbolsRounded.woff2")
OUT = os.path.join(ROOT, "assets", "fonts", "material-symbols.woff2")


def wanted():
    names = set()
    for lang in ("zh", "en"):
        with open(os.path.join(ROOT, "assets", "desktop", f"sim-{lang}.html"), encoding="utf-8") as f:
            html = f.read()
        names.update(re.findall(r'class="[^"]*material-symbols[^"]*"[^>]*>\s*([a-z0-9_]+)\s*<', html))
    with open(os.path.join(ROOT, "assets", "js", "desktop-sim.js"), encoding="utf-8") as f:
        names.update(re.findall(r"'([a-z][a-z0-9_]{1,40})'", f.read()))
    return names


def ligatures(font):
    """连字表：文本 -> 字形名"""
    cmap = {v: chr(k) for k, v in font.getBestCmap().items()}
    out = {}
    for lookup in font["GSUB"].table.LookupList.Lookup:
        for st in lookup.SubTable:
            if st.LookupType == 7:
                st = st.ExtSubTable
            if getattr(st, "LookupType", None) != 4 and not hasattr(st, "ligatures"):
                continue
            for first, ligs in st.ligatures.items():
                for lig in ligs:
                    seq = [first] + list(lig.Component)
                    text = "".join(cmap.get(g, "?") for g in seq)
                    out.setdefault(text, lig.LigGlyph)
    return out


def run():
    font = TTFont(SRC)
    font = instancer.instantiateVariableFont(font, {"wght": 400, "GRAD": 0, "opsz": 24})
    buf = io.BytesIO()
    font.save(buf)
    buf.seek(0)
    font = TTFont(buf)
    table = ligatures(font)
    names = wanted()
    order = set(font.getGlyphOrder())
    keep = set()
    hit = []
    for n in sorted(names):
        g = table.get(n)
        if not g:
            continue
        hit.append(n)
        keep.add(g)
        if g + ".fill" in order:
            keep.add(g + ".fill")
    opts = subset.Options()
    opts.flavor = "woff2"
    opts.layout_features = ["liga", "rlig", "rclt", "calt", "ccmp"]
    opts.layout_closure = False
    opts.notdef_outline = True
    opts.name_IDs = [0, 1, 2, 3, 4, 5, 6]
    sub = subset.Subsetter(opts)
    sub.populate(glyphs=sorted(keep), text="abcdefghijklmnopqrstuvwxyz_0123456789")
    sub.subset(font)
    font.flavor = "woff2"
    font.save(OUT)
    print(f"material-symbols.woff2  {len(hit)} icons  {os.path.getsize(OUT) / 1024:.1f} KB")


if __name__ == "__main__":
    run()
