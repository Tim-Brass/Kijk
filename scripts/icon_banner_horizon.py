"""App-icoon (A1 'Opkomst') en banner (A 'Horizon') in de stijl van het laadscherm.
Gebruik: python3 scripts/icon_banner_horizon.py app/src/main/res"""
import sys
from PIL import Image, ImageDraw, ImageFilter, ImageChops

S = 4
BLUE = (10, 132, 255); CYAN = (100, 210, 255)

def quad(p0, c, p1, n=200):
    return [((1-t)**2*p0[0] + 2*(1-t)*t*c[0] + t*t*p1[0], (1-t)**2*p0[1] + 2*(1-t)*t*c[1] + t*t*p1[1]) for t in [i/n for i in range(n+1)]]

def edge_fade(W, H, x0, x1):
    """horizontaal masker: uiteinden van de boog lopen weg"""
    m = Image.new("L", (W, H), 0); d = ImageDraw.Draw(m)
    for x in range(W):
        t = (x - x0) / max(1, (x1 - x0))
        a = 0 if t <= 0 or t >= 1 else min(1, t / 0.2, (1 - t) / 0.2)
        d.line([(x, 0), (x, H)], fill=int(255 * a))
    return m

def glow_arc(W, H, pts, core_w, x0, x1):
    lay = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    for width, col, alpha, blur in [(core_w * 14, BLUE, 70, core_w * 6), (core_w * 5, BLUE, 140, core_w * 2.2), (core_w, CYAN, 255, 0)]:
        l = Image.new("RGBA", (W, H), (0, 0, 0, 0)); ImageDraw.Draw(l).line(pts, fill=col + (alpha,), width=int(width), joint="curve")
        if blur: l = l.filter(ImageFilter.GaussianBlur(blur))
        lay = Image.alpha_composite(lay, l)
    a = ImageChops.multiply(lay.getchannel("A"), edge_fade(W, H, x0, x1)); lay.putalpha(a)
    return lay

def atmosphere(W, H, cx, cy, rx, ry, strength):
    l = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(l)
    d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=BLUE + (int(255 * strength),))
    return l.filter(ImageFilter.GaussianBlur(rx * 0.35))

def eye(d, cx, cy, ew, eh, look_up=True):
    d.ellipse([cx - ew, cy - eh, cx + ew, cy + eh], fill=(255, 255, 255, 255))
    pr = ew * 0.5; px, py = cx + ew * 0.12, cy - (eh * 0.28 if look_up else -eh * 0.1)
    d.ellipse([px - pr, py - pr, px + pr, py + pr], fill=(11, 11, 15, 255))
    hr = pr * 0.32; d.ellipse([px + pr * 0.15 - hr, py - pr * 0.45 - hr, px + pr * 0.15 + hr, py - pr * 0.45 + hr], fill=(255, 255, 255, 235))

def icon(size):
    W = size * S
    im = Image.new("RGBA", (W, W), (0, 0, 0, 255))
    hy = W * 0.80; top = W * 0.64          # horizon: randen op hy, midden op 'top'
    pts = quad((-W * 0.1, hy), (W / 2, 2 * top - hy), (W * 1.1, hy))
    im = Image.alpha_composite(im, atmosphere(W, W, W / 2, top, W * 0.55, W * 0.22, 0.55))
    # oogjes (gluren boven de horizon uit; deel eronder verdwijnt achter de planeet)
    eyes = Image.new("RGBA", (W, W), (0, 0, 0, 0)); d = ImageDraw.Draw(eyes)
    ew, eh, gap, cy = W * 0.15, W * 0.19, W * 0.025, top - W * 0.02
    for cx in (W / 2 - gap - ew, W / 2 + gap + ew): eye(d, cx, cy, ew, eh)
    im = Image.alpha_composite(im, eyes)
    planet = Image.new("RGBA", (W, W), (0, 0, 0, 0)); ImageDraw.Draw(planet).polygon(pts + [(W * 1.1, W * 1.2), (-W * 0.1, W * 1.2)], fill=(3, 10, 20, 255))
    im = Image.alpha_composite(im, planet)
    im = Image.alpha_composite(im, glow_arc(W, W, pts, W * 0.012, 0, W))
    m = Image.new("L", (W, W), 0); ImageDraw.Draw(m).rounded_rectangle([0, 0, W - 1, W - 1], int(W * 0.22), fill=255)
    out = Image.new("RGBA", (W, W), (0, 0, 0, 0)); out.paste(im, (0, 0), m)
    return out.resize((size, size), Image.LANCZOS)

def banner(res):
    w, h = 320, 180; W, H = w * S, h * S
    im = Image.new("RGBA", (W, H), (0, 0, 0, 255))
    hy = H * 0.98; top = H * 0.70
    pts = quad((W * 0.04, hy), (W / 2, 2 * top - hy), (W * 0.96, hy))
    im = Image.alpha_composite(im, atmosphere(W, H, W / 2, top + H * 0.08, W * 0.38, H * 0.12, 0.45))
    im = Image.alpha_composite(im, glow_arc(W, H, pts, H * 0.011, W * 0.04, W * 0.96))
    # woordmerk met oogjes (zelfde maten als in de app, LogoGeometry.kt)
    wm = Image.open(f"{res}/drawable-nodpi/wordmark_kijk.png").convert("RGBA")
    lh = H * 0.50; lw = lh * 1.4917
    wm = wm.resize((int(lw), int(lh)), Image.LANCZOS)
    lx, ly = int(W / 2 - lw / 2), int(top - lh * 0.93)
    im.alpha_composite(wm, (lx, ly))
    d = ImageDraw.Draw(im)
    for fx in (0.4017, 0.5674):
        cx, cy = lx + lw * fx, ly + lh * 0.1575
        rx, ry = lw * 0.0741, lh * 0.1406
        d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=(255, 255, 255, 255))
        pr = rx * 0.5; px, py = cx + 0.35 * rx * 0.42, cy + ry * 0.1
        d.ellipse([px - pr, py - pr, px + pr, py + pr], fill=(11, 11, 15, 255))
        hr = pr * 0.32; d.ellipse([px + pr * 0.15 - hr, py - pr * 0.45 - hr, px + pr * 0.15 + hr, py - pr * 0.45 + hr], fill=(255, 255, 255, 230))
    im.convert("RGB").resize((w, h), Image.LANCZOS).save(f"{res}/drawable-xhdpi/banner.png")

res = sys.argv[1]
for name, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    icon(px).save(f"{res}/mipmap-{name}/ic_launcher.png")
banner(res)
icon(512).save("/tmp/icon512.png")
