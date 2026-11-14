"""App-icoon variant A: zwart, twee oogjes, schuine blauwe baan met twee bolletjes.
Gebruik: python3 scripts/icon_orbit.py <res-map>"""
import sys
from PIL import Image, ImageDraw, ImageFont
S=4
def eye(d,cx,cy,ew,eh,look=0.3):
    d.ellipse([cx-ew,cy-eh,cx+ew,cy+eh],fill="white")
    pr=ew*0.5; px,py=cx+ew*look,cy+eh*0.1
    d.ellipse([px-pr,py-pr,px+pr,py+pr],fill=(11,11,15))
    hr=pr*0.32; d.ellipse([px+pr*0.15-hr,py-pr*0.45-hr,px+pr*0.15+hr,py-pr*0.45+hr],fill=(255,255,255,230))
def tile(d,x,y,s,kind):
    d.rounded_rectangle([x-s/2,y-s/2,x+s/2,y+s/2],radius=s*0.26,fill=(15,22,38,255),outline=(40,60,100,255),width=max(2,int(s*0.05)))
    c=(91,140,255,255)
    if kind=="play": d.polygon([(x-s*.14,y-s*.2),(x-s*.14,y+s*.2),(x+s*.22,y)],fill=c)
    else:
        w=max(2,int(s*0.07)); d.rounded_rectangle([x-s*.26,y-s*.2,x+s*.26,y+s*.12],radius=s*.05,outline=c,width=w); d.line([x-s*.12,y+s*.24,x+s*.12,y+s*.24],fill=c,width=w)
def icon(v,size):
    W=size*S; im=Image.new("RGBA",(W,W),(0,0,0,0))
    m=Image.new("L",(W,W),0); ImageDraw.Draw(m).rounded_rectangle([0,0,W-1,W-1],int(W*0.22),fill=255)
    bg=Image.new("RGBA",(W,W)); bd=ImageDraw.Draw(bg)
    for y in range(W):
        t=y/W
        col=(0,0,0,255) if v in "AC" else (int(10+20*t),int(132-60*t),int(255-60*t),255)
        bd.line([(0,y),(W,y)],fill=col)
    im.paste(bg,(0,0),m)
    orb=Image.new("RGBA",(W,W),(0,0,0,0)); od=ImageDraw.Draw(orb)
    cx,cy,rx,ry=W/2,W*0.52,W*0.40,W*0.17
    dim=(31,58,138,255) if v in "AC" else (255,255,255,90)
    br=(47,107,255,255) if v in "AC" else (255,255,255,255)
    od.ellipse([cx-rx,cy-ry,cx+rx,cy+ry],outline=dim,width=int(W*0.014))
    od.arc([cx-rx,cy-ry,cx+rx,cy+ry],300,35,fill=br,width=int(W*0.03))
    orb=orb.rotate(14,center=(cx,cy),resample=Image.BICUBIC)
    im.alpha_composite(orb); d=ImageDraw.Draw(im)
    ew,eh,gap=W*0.13,W*0.165,W*0.025
    for ex in (W/2-gap-ew, W/2+gap+ew): eye(d,ex,W*0.47,ew,eh)
    if v=="C":
        tile(d,W*0.17,W*0.36,W*0.2,"tv"); tile(d,W*0.83,W*0.66,W*0.2,"play")
    else:
        d.ellipse([W*0.12-W*0.035,W*0.36-W*0.035,W*0.12+W*0.035,W*0.36+W*0.035],fill=br)
        d.ellipse([W*0.86-W*0.025,W*0.66-W*0.025,W*0.86+W*0.025,W*0.66+W*0.025],fill=br)
    return im.resize((size,size),Image.LANCZOS)

res = sys.argv[1]
for name, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    icon("A", px).save(f"{res}/mipmap-{name}/ic_launcher.png")
