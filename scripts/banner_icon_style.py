"""TV-banner in de stijl van het app-icoon: blauwe verlooptegel, witte 'kijk' met de oogjes als puntjes op de ij."""
import sys
from PIL import Image, ImageDraw, ImageFont
S = 4
W, H = 320 * S, 180 * S
FONT = "/usr/share/fonts/truetype/google-fonts/Poppins-Bold.ttf"

bg = Image.new("RGBA", (W, H)); bd = ImageDraw.Draw(bg)
for y in range(H):
    t = y / H; bd.line([(0, y), (W, y)], fill=(int(10 + 20 * t), int(132 - 60 * t), int(255 - 60 * t), 255))

f = ImageFont.truetype(FONT, int(118 * S))
xh_top = f.getbbox("x")[1]                 # bovenkant van de x-hoogte
letters = "kijk"
widths = [f.getlength(c) for c in letters]
track = -2 * S
total = sum(widths) + track * 3
asc_top = f.getbbox("k")[1]; base_bottom = f.getbbox("j")[3]
x0 = (W - total) / 2
y0 = (H - (base_bottom - asc_top)) / 2 - asc_top + 6 * S

mask = Image.new("L", (W, H), 0); md = ImageDraw.Draw(mask)
dots = []
x = x0
for c, w in zip(letters, widths):
    lm = Image.new("L", (W, H), 0); ld = ImageDraw.Draw(lm)
    ld.text((x, y0), c, font=f, fill=255)
    if c in "ij":
        cut = int(y0 + xh_top - 2 * S)        # puntje weghalen: alles boven de x-hoogte
        box = lm.getbbox()
        dot = lm.crop((0, 0, W, cut)).getbbox()
        if dot: dots.append(((dot[0] + dot[2]) / 2, (dot[1] + dot[3]) / 2))
        ImageDraw.Draw(lm).rectangle([0, 0, W, cut], fill=0)
    mask.paste(255, (0, 0), lm)
    x += w + track

im = bg.copy()
im.paste((255, 255, 255, 255), (0, 0), mask)
d = ImageDraw.Draw(im)
# oogjes zoals in het icoon
ew, eh = 19 * S, 24 * S
for cx, cy in dots:
    cy -= 4 * S
    d.ellipse([cx - ew, cy - eh, cx + ew, cy + eh], fill=(255, 255, 255, 255))
    pr = ew * 0.5; px, py = cx + ew * 0.3, cy + eh * 0.1
    d.ellipse([px - pr, py - pr, px + pr, py + pr], fill=(11, 11, 15, 255))
    hr = pr * 0.32; d.ellipse([px + pr * 0.15 - hr, py - pr * 0.45 - hr, px + pr * 0.15 + hr, py - pr * 0.45 + hr], fill=(255, 255, 255, 230))
im.resize((320, 180), Image.LANCZOS).save(sys.argv[1])
