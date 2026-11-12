from PIL import Image, ImageDraw
import sys
res=sys.argv[1]; prev=sys.argv[2]
wm=Image.open(f"{res}/drawable-xhdpi/wordmark.png").convert("RGBA")   # 640x360, zonder puntjes
def make(w,h,path):
    ss=4; W,H=w*ss,h*ss
    bg=Image.new("RGBA",(W,H),(11,11,15,255)); bg.alpha_composite(wm.resize((W,H),Image.LANCZOS))
    d=ImageDraw.Draw(bg); r=H*0.048; er=r*1.25; cy=H*0.3017
    for cx in (W*0.4619, W*0.5455):
        d.ellipse([cx-er,cy-er,cx+er,cy+er],fill=(255,255,255))
        pr=er*0.55; px=cx+er*0.30; py=cy+er*0.10   # kijkt een beetje naar rechts-onder: "naar het scherm"
        d.ellipse([px-pr,py-pr,px+pr,py+pr],fill=(10,132,255))
    bg.convert("RGB").resize((w,h),Image.LANCZOS).save(path)
make(320,180,f"{res}/drawable-xhdpi/banner.png")
make(640,360,prev)
