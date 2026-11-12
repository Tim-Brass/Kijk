"""Maakt het 'kijk'-woordbeeld zonder puntjes op de ij (de oogjes tekent de app zelf) + de tv-banner.
Gebruik: python3 scripts/wordmark_eyes.py <res-map> <kotlin-geometry-bestand> <font.ttf>"""
import sys
from PIL import Image, ImageDraw, ImageFont
res, geo, FONT = sys.argv[1:4]
EW, EH = 44 / 330, 56 / 330          # oogje (halve breedte/hoogte) t.o.v. lettergrootte

def wordmark(fs, color="white"):
    """Geeft (beeld zonder puntjes, [(cx, cy)] oogmiddens) in pixels, strak bijgesneden met ruimte voor de oogjes."""
    W, H = int(fs * 3.2), int(fs * 1.8)
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    x0, y = fs * 0.2, fs * 1.3
    d.text((x0, y), "kijk", font=(f := ImageFont.truetype(FONT, fs)), fill=color, anchor="ls")
    xh = d.textbbox((0, y), "x", font=f, anchor="ls")[1]
    eyes = []
    for ch, pre in (("i", "k"), ("j", "ki")):
        xs = x0 + d.textlength(pre, font=f)
        bb = d.textbbox((xs, y), ch, font=f, anchor="ls")
        reg = im.crop((int(bb[0]), int(bb[1]), int(bb[2]) + 1, int(xh - fs * 0.02)))
        rb = reg.split()[-1].point(lambda v: 255 if v > 128 else 0).getbbox()
        eyes.append((bb[0] + (rb[0] + rb[2]) / 2, bb[1] + (rb[1] + rb[3]) / 2 - fs * 0.02))
        d.rectangle([bb[0] - 2, 0, bb[2] + 2, xh - fs * 0.02], fill=(0, 0, 0, 0))
    # bijsnijden: letters + oogjes
    lb = im.getbbox()
    top = min(cy - EH * fs for _, cy in eyes) - fs * 0.02
    box = (int(lb[0] - fs * 0.02), int(top), int(lb[2] + fs * 0.02), int(lb[3] + fs * 0.02))
    return im.crop(box), [(cx - box[0], cy - box[1]) for cx, cy in eyes]

def eye(d, cx, cy, ew, eh, look=0.3):
    d.ellipse([cx - ew, cy - eh, cx + ew, cy + eh], fill="white")
    pr = ew * 0.5; px, py = cx + ew * look, cy + eh * 0.1
    d.ellipse([px - pr, py - pr, px + pr, py + pr], fill=(11, 11, 15))
    hr = pr * 0.32; d.ellipse([px + pr * 0.15 - hr, py - pr * 0.45 - hr, px + pr * 0.15 + hr, py - pr * 0.45 + hr], fill=(255, 255, 255, 230))

# --- woordbeeld voor in de app (oogjes tekent Compose)
FS = 300
wm, eyes = wordmark(FS)
wm.save(f"{res}/drawable-nodpi/wordmark_kijk.png")
w, h = wm.size
with open(geo, "w") as g:
    g.write("package nl.kijk.tv.ui\n\n// Gegenereerd door scripts/wordmark_eyes.py: oogjes op de ij in drawable/wordmark_kijk.png (verhoudingen)\n")
    g.write(f"const val WM_ASPECT = {w / h:.4f}f\n")
    g.write(f"const val WM_EYE1_X = {eyes[0][0] / w:.4f}f\nconst val WM_EYE2_X = {eyes[1][0] / w:.4f}f\n")
    g.write(f"const val WM_EYE_Y = {eyes[0][1] / h:.4f}f\n")
    g.write(f"const val WM_EYE_RX = {EW * FS / w:.4f}f\nconst val WM_EYE_RY = {EH * FS / h:.4f}f\n")

# --- tv-banner 320×180 (getekend op 4×)
B = 4; W, H = 320 * B, 180 * B
ban = Image.new("RGBA", (W, H)); bd = ImageDraw.Draw(ban)
for yy in range(H):
    t = yy / H; bd.line([(0, yy), (W, yy)], fill=(int(10 + 20 * t), int(132 - 60 * t), int(255 - 60 * t), 255))
fs = 330; m, e = wordmark(fs)
ox, oy = (W - m.width) // 2, (H - m.height) // 2 - 10
ban.paste(m, (ox, oy), m); bd = ImageDraw.Draw(ban)
for cx, cy in e: eye(bd, ox + cx, oy + cy, EW * fs, EH * fs, look=0.35)
ban.convert("RGB").resize((320, 180), Image.LANCZOS).save(f"{res}/drawable-xhdpi/banner.png")
