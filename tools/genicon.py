"""生成 legacy 启动图标 PNG（48/72/96/144/192）"""
from PIL import Image, ImageDraw
import os

SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
RES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
BG = (18, 23, 31, 255)
GOLD = (232, 184, 75, 255)

def draw(px):
    S = px * 8
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.22), fill=BG)
    # 金色卡面
    x0, y0, x1, y1 = S * 0.30, S * 0.26, S * 0.70, S * 0.74
    d.rounded_rectangle([x0, y0, x1, y1], radius=int(S * 0.045), fill=GOLD)
    # 卡面里的暗色菱形
    cx, cy = S / 2, S / 2
    rw, rh = S * 0.085, S * 0.135
    d.polygon([(cx, cy - rh), (cx + rw, cy), (cx, cy + rh), (cx - rw, cy)], fill=(18, 23, 31, 255))
    return im.resize((px, px), Image.LANCZOS)

for dens, px in SIZES.items():
    out = os.path.join(RES, f"mipmap-{dens}", "ic_launcher.png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    draw(px).save(out, "PNG")
    print("wrote", out, px)
