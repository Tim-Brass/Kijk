from PIL import Image, ImageDraw, ImageFont
import sys
res=sys.argv[1]; out=sys.argv[2]
FONT="/usr/share/fonts/opentype/inter/Inter-Bold.otf"
w,h=640,360; ss=2; W,H=w*ss,h*ss
im=Image.new("RGBA",(W,H),(0,0,0,0)); d=ImageDraw.Draw(im)
f=ImageFont.truetype(FONT,int(H*0.50)); text="kıȷk"
tw=d.textlength(text,font=f); bb=d.textbbox((0,0),text,font=f)
x0=(W-tw)/2; base_y=(H-(bb[3]-bb[1]))/2-bb[1]+H*0.06
d.text((x0,base_y),text,font=f,fill=(255,255,255,255))
xi=x0+d.textlength("k",font=f); wi=d.textlength("ı",font=f)
xj=x0+d.textlength("kı",font=f)
xh_top=base_y+d.textbbox((0,0),"ı",font=f)[1]
r=H*0.048; cy=xh_top-r*2.4; cxi=xi+wi/2
jb=d.textbbox((xj,base_y),"ȷ",font=f); cxj=jb[2]-r*1.15
im.resize((w,h),Image.LANCZOS).save(f"{res}/drawable-xhdpi/wordmark.png")
open(out,"w").write(f'''package nl.kijk.tv.ui

// Gegenereerd door scripts/wordmark.py: posities van de puntjes in drawable/wordmark.png (verhoudingen)
const val LOGO_DOT_X = {cxi/W:.4f}f
const val LOGO_DOT_Y = {cy/H:.4f}f
const val LOGO_DOT_R = {r/H:.4f}f
const val LOGO_PLAY_X = {cxj/W:.4f}f
''')
