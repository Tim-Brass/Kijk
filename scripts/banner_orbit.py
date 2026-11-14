"""Tv-banner (320x180) in de stijl van het laadscherm: zwart, schuine blauwe baan, twee icoontegels, kijk met open oogjes.
Gebruik: python3 scripts/banner_orbit.py <res-map> <font.ttf>"""
import sys, math
sys.path.insert(0, __import__("os").path.dirname(__file__))
from PIL import Image, ImageDraw, ImageFont
res, FONT = sys.argv[1:3]
S = 4; W, H = 320 * S, 180 * S
EW, EH = 44 / 330, 56 / 330

def wordmark(fs):
    Wd, Hd = int(fs * 3.2), int(fs * 1.8)
    im = Image.new("RGBA", (Wd, Hd), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    f = ImageFont.truetype(FONT, fs); x0, y = fs * 0.2, fs * 1.3
    d.text((x0, y), "kijk", font=f, fill="white", anchor="ls")
    xh = d.textbbox((0, y), "x", font=f, anchor="ls")[1]
    eyes = []
    for ch, pre in (("i", "k"), ("j", "ki")):
        xs = x0 + d.textlength(pre, font=f); bb = d.textbbox((xs, y), ch, font=f, anchor="ls")
        reg = im.crop((int(bb[0]), int(bb[1]), int(bb[2]) + 1, int(xh - fs * 0.02)))
        rb = reg.split()[-1].point(lambda v: 255 if v > 128 else 0).getbbox()
        eyes.append((bb[0] + (rb[0] + rb[2]) / 2, bb[1] + (rb[1] + rb[3]) / 2 - fs * 0.02))
        d.rectangle([bb[0] - 2, 0, bb[2] + 2, xh - fs * 0.02], fill=(0, 0, 0, 0))
    lb = im.getbbox(); top = min(cy - EH * fs for _, cy in eyes) - fs * 0.02
    box = (int(lb[0] - 4), int(top), int(lb[2] + 4), int(lb[3] + 4))
    return im.crop(box), [(cx - box[0], cy - box[1]) for cx, cy in eyes]

def eye(d, cx, cy, ew, eh, look=0.3):
    d.ellipse([cx - ew, cy - eh, cx + ew, cy + eh], fill="white")
    pr = ew * 0.5; px, py = cx + ew * look, cy + eh * 0.1
    d.ellipse([px - pr, py - pr, px + pr, py + pr], fill=(11, 11, 15))
    hr = pr * 0.32; d.ellipse([px + pr * 0.15 - hr, py - pr * 0.45 - hr, px + pr * 0.15 + hr, py - pr * 0.45 + hr], fill=(255, 255, 255, 230))

img = Image.new("RGBA", (W, H), (0, 0, 0, 255))
# baan: schuine ellips, gedimd, met een helder stuk
orb = Image.new("RGBA", (W, H), (0, 0, 0, 0)); od = ImageDraw.Draw(orb)
cx, cy, rx, ry = W / 2, H * 0.47, W * 0.40, H * 0.24
od.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], outline=(31, 58, 138, 255), width=5)
od.arc([cx - rx, cy - ry, cx + rx, cy + ry], 300, 30, fill=(47, 107, 255, 255), width=11)
od.arc([cx - rx, cy - ry, cx + rx, cy + ry], 140, 175, fill=(47, 107, 255, 255), width=9)
orb = orb.rotate(8, center=(cx, cy), resample=Image.BICUBIC)
img.alpha_composite(orb)
d = ImageDraw.Draw(img)

def tile(x, y, s, kind):
    d.rounded_rectangle([x, y, x + s, y + s], radius=s * 0.24, fill=(15, 22, 38, 255), outline=(29, 42, 68, 255), width=4)
    c = (91, 140, 255, 255); w = int(s * 0.06)
    if kind == "tv":
        d.rounded_rectangle([x + s * .22, y + s * .26, x + s * .78, y + s * .64], radius=s * .05, outline=c, width=w)
        d.line([x + s * .5, y + s * .64, x + s * .5, y + s * .73], fill=c, width=w)
        d.line([x + s * .38, y + s * .75, x + s * .62, y + s * .75], fill=c, width=w)
    else:
        d.polygon([(x + s * .38, y + s * .28), (x + s * .38, y + s * .72), (x + s * .74, y + s * .5)], fill=c)

tile(W * 0.07, H * 0.20, 150, "tv")
tile(W * 0.83, H * 0.56, 150, "play")
# woordmerk met open oogjes
fs = 300; wm, eyes = wordmark(fs)
ox, oy = int((W - wm.width) / 2), int((H - wm.height) / 2 - 6)
img.alpha_composite(wm, (ox, oy)); d = ImageDraw.Draw(img)
for ex, ey in eyes: eye(d, ox + ex, oy + ey, EW * fs, EH * fs, look=0.35)
img.convert("RGB").resize((320, 180), Image.LANCZOS).save(f"{res}/drawable-xhdpi/banner.png")
