from PIL import Image, ImageDraw, ImageFont
import os, sys
res = sys.argv[1]
WHITE=(255,255,255); BLUE=(10,132,255); DARK=(11,11,15)
FONT="/usr/share/fonts/opentype/inter/Inter-Bold.otf"
def gradient(S, top=(64,160,255), bot=(0,92,214)):
    g=Image.new("RGB",(1,S))
    for y in range(S):
        t=y/(S-1); g.putpixel((0,y), tuple(int(top[i]+(bot[i]-top[i])*t) for i in range(3)))
    return g.resize((S,S))
def icon(sz, path):
    ss=4; S=sz*ss
    mask=Image.new("L",(S,S),0); ImageDraw.Draw(mask).rounded_rectangle([0,0,S-1,S-1], radius=int(S*0.23), fill=255)
    im=Image.new("RGBA",(S,S),(0,0,0,0)); im.paste(gradient(S),(0,0),mask)
    d=ImageDraw.Draw(im)
    # K uit het lettertype; in de opening tussen de poten een kleine afspeelknop
    f=ImageFont.truetype(FONT, int(S*0.62))
    bb=d.textbbox((0,0),"K",font=f); kw=bb[2]-bb[0]; kh=bb[3]-bb[1]
    x=(S-kw)/2 - bb[0] - S*0.06; y=(S-kh)/2 - bb[1]
    d.text((x,y),"K",font=f,fill=WHITE)
    # opening van de K: rechts van het midden
    cx=x+bb[0]+kw*0.86; cy=y+bb[1]+kh*0.5; t=S*0.075
    d.polygon([(cx-t*0.7,cy-t),(cx-t*0.7,cy+t),(cx+t*1.05,cy)],fill=(255,255,255))
    im.resize((sz,sz), Image.LANCZOS).save(path)
def banner(w,h,path):
    ss=4; W,H=w*ss,h*ss
    im=Image.new("RGB",(W,H),DARK); d=ImageDraw.Draw(im)
    f=ImageFont.truetype(FONT, int(H*0.50))
    text="kıȷk"
    tw=d.textlength(text,font=f); bb=d.textbbox((0,0),text,font=f)
    x0=(W-tw)/2; base_y=(H-(bb[3]-bb[1]))/2 - bb[1] + H*0.06
    d.text((x0,base_y),text,font=f,fill=WHITE)
    # posities van ı en ȷ
    xi=x0+d.textlength("k",font=f); wi=d.textlength("ı",font=f)
    xj=x0+d.textlength("kı",font=f); wj=d.textlength("ȷ",font=f)
    xh_top=base_y+d.textbbox((0,0),"ı",font=f)[1]   # bovenkant x-hoogte
    r=H*0.048; cy=xh_top - r*2.4
    cxi=xi+wi/2; d.ellipse([cxi-r,cy-r,cxi+r,cy+r],fill=(30,144,224))
    # j-stam zit iets rechts in de glyph; schat het midden van de stam via bbox
    jb=d.textbbox((xj,base_y),"ȷ",font=f); cxj=jb[2]-(cxi-xi- (d.textbbox((xi,base_y),"ı",font=f)[0]-xi)) if False else (jb[2]-r*1.15)
    t=r*1.25
    # afspeelknop met hetzelfde verloop als het icoon (lichtblauw naar indigo)
    m=Image.new("L",(W,H),0); ImageDraw.Draw(m).polygon([(cxj-t*0.8,cy-t),(cxj-t*0.8,cy+t),(cxj+t*1.0,cy)],fill=255)
    g=Image.new("RGB",(W,H)); gd=ImageDraw.Draw(g)
    y0=int(cy-t); y1=int(cy+t)
    for y in range(max(0,y0),min(H,y1+1)):
        k=(y-y0)/max(1,(y1-y0)); gd.line([(0,y),(W,y)],fill=(int(40+(60-40)*k),int(160+(70-160)*k),int(220+(170-220)*k)))
    im.paste(g,(0,0),m)
    im.resize((w,h),Image.LANCZOS).save(path)
banner(320,180,f"{res}/drawable-xhdpi/banner.png")
banner(640,360,"/home/claude/out/banner_preview.png")
