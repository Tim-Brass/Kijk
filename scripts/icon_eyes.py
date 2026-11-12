"""Genereert het app-icoon: twee oogjes op een blauwe verlooptegel (variant C)."""
import sys
from PIL import Image, ImageDraw
S = 4
def icon(size):
    W = size * S
    im = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    bg = Image.new("RGBA", (W, W)); bd = ImageDraw.Draw(bg)
    for y in range(W):
        t = y / W; bd.line([(0, y), (W, y)], fill=(int(10 + 20 * t), int(132 - 60 * t), int(255 - 60 * t), 255))
    m = Image.new("L", (W, W), 0); ImageDraw.Draw(m).rounded_rectangle([0, 0, W - 1, W - 1], int(W * 0.22), fill=255)
    im.paste(bg, (0, 0), m); d = ImageDraw.Draw(im)
    ew, eh, gap, cy = W * 0.155, W * 0.2, W * 0.03, W * 0.5
    for cx in (W / 2 - gap - ew, W / 2 + gap + ew):
        d.ellipse([cx - ew, cy - eh, cx + ew, cy + eh], fill=(255, 255, 255, 255))
        pr = ew * 0.5; px, py = cx + ew * 0.3, cy + eh * 0.1
        d.ellipse([px - pr, py - pr, px + pr, py + pr], fill=(11, 11, 15, 255))
        hr = pr * 0.32; d.ellipse([px + pr * 0.15 - hr, py - pr * 0.45 - hr, px + pr * 0.15 + hr, py - pr * 0.45 + hr], fill=(255, 255, 255, 230))
    return im.resize((size, size), Image.LANCZOS)
res = sys.argv[1]
for name, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    icon(px).save(f"{res}/mipmap-{name}/ic_launcher.png")
