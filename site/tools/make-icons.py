"""用 App 真实的自适应图标生成网站图标。

用法：python tools/make-icons.py [NeriPlayer 源码目录，默认 E:/AndroidProject/NeriPlayer]
来源：res/mipmap-anydpi/ic_launcher.xml = 纯色背景 #1E293A + drawable-nodpi/ic_launcher_foreground_material.png
自适应图标画布 108 dp，启动器只显示中间 72 dp，这里按同样的比例裁切。
"""
import os
import sys
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = sys.argv[1] if len(sys.argv) > 1 else "E:/AndroidProject/NeriPlayer"
RES = os.path.join(APP, "app", "src", "main", "res")
OUT = os.path.join(ROOT, "assets", "img")
BG = (0x1E, 0x29, 0x3A, 255)

fg = Image.open(os.path.join(RES, "drawable-nodpi", "ic_launcher_foreground_material.png")).convert("RGBA")
canvas = Image.new("RGBA", fg.size, BG)
canvas.alpha_composite(fg)
side = round(fg.width * 72 / 108)
off = (fg.width - side) // 2
full = canvas.crop((off, off, off + side, off + side))


def square(size):
    return full.resize((size, size), Image.LANCZOS)


def rounded(size, radius=0.235):
    # 网页里使用的圆角方形版本（四倍超采样后缩小，边缘更平滑）
    big = square(size * 4)
    mask = Image.new("L", big.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, big.width - 1, big.height - 1), radius=round(big.width * radius), fill=255)
    big.putalpha(mask)
    return big.resize((size, size), Image.LANCZOS)


os.makedirs(OUT, exist_ok=True)
rounded(192).save(os.path.join(OUT, "app-icon.png"), optimize=True)
rounded(512).save(os.path.join(OUT, "icon-512.png"), optimize=True)
rounded(32).save(os.path.join(OUT, "favicon-32.png"), optimize=True)
square(180).convert("RGB").save(os.path.join(OUT, "apple-touch-icon.png"), optimize=True)
square(512).convert("RGB").save(os.path.join(OUT, "icon-maskable-512.png"), optimize=True)
print("app-icon.png  icon-512.png  favicon-32.png  apple-touch-icon.png  icon-maskable-512.png")
