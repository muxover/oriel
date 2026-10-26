"""Draws Oriel's boot animation (bootanimation.zip) and boot logo (logo.jpg).

Usage: python3 make_boot.py <font.ttf> <out_dir>
"""
import io, math, os, sys, zipfile
from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H, FPS = 1920, 1080, 25
INK = (5, 5, 6)
PAPER = (244, 243, 239)
VIOLET = (139, 92, 246)
VIOLET_HI = (163, 131, 250)
SS = 3  # supersampling for the mark

font_path, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
word_font = ImageFont.truetype(font_path, 88)

MW, MH = 132, 176           # window size
MX, MY = (W - MW) // 2, 360  # window top-left


def ease(t):
    t = max(0.0, min(1.0, t))
    return 1 - (1 - t) ** 3


def window_mask(scale):
    """Arched window: a rectangle with a half circle on top."""
    m = Image.new("L", (MW * scale, MH * scale), 0)
    d = ImageDraw.Draw(m)
    r = MW * scale // 2
    d.ellipse([0, 0, MW * scale - 1, 2 * r], fill=255)
    d.rectangle([0, r, MW * scale - 1, MH * scale - 1], fill=255)
    return m


FULL = window_mask(SS)


def mark(light, outline, glow):
    """Returns the mark as an RGBA image at window size plus a margin for the glow."""
    pad = 120
    size = (MW + 2 * pad, MH + 2 * pad)
    img = Image.new("RGBA", size, INK + (0,))
    # soft glow behind the window
    if glow > 0:
        g = Image.new("L", size, 0)
        ImageDraw.Draw(g).bitmap((pad, pad), FULL.resize((MW, MH), Image.LANCZOS), fill=int(255 * glow))
        g = g.filter(ImageFilter.GaussianBlur(46))
        img.alpha_composite(Image.merge("RGBA", (*[Image.new("L", size, c) for c in VIOLET], g)))
    # light rising inside the window, brighter at the bottom
    s = Image.new("RGBA", (MW * SS, MH * SS), (0, 0, 0, 0))
    if light > 0:
        top = int(MH * SS * (1 - light))
        grad = Image.new("RGBA", (MW * SS, MH * SS), (0, 0, 0, 0))
        gd = ImageDraw.Draw(grad)
        for y in range(top, MH * SS):
            k = (y - top) / max(1, MH * SS - top)
            c = tuple(int(VIOLET[i] + (VIOLET_HI[i] - VIOLET[i]) * k) for i in range(3))
            gd.line([(0, y), (MW * SS, y)], fill=c + (int(90 + 140 * k),))
        s = Image.composite(grad, s, FULL)
    # outline
    if outline > 0:
        o = Image.new("L", (MW * SS, MH * SS), 0)
        inner = FULL.resize((MW * SS - 6 * SS, MH * SS - 6 * SS), Image.LANCZOS)
        o.paste(FULL, (0, 0))
        o.paste(0, (3 * SS, 3 * SS), inner)
        line = Image.new("RGBA", (MW * SS, MH * SS), PAPER + (0,))
        line.putalpha(o.point(lambda v: int(v * outline)))
        s.alpha_composite(line)
        # mullion: one vertical bar, like a window frame
        bar = Image.new("RGBA", (MW * SS, MH * SS), (0, 0, 0, 0))
        ImageDraw.Draw(bar).rectangle([MW * SS // 2 - SS, MW * SS // 2, MW * SS // 2 + SS, MH * SS], fill=PAPER + (int(150 * outline),))
        s.alpha_composite(bar)
    img.alpha_composite(s.resize((MW, MH), Image.LANCZOS), (pad, pad))
    return img, pad


def frame(light, outline, glow, word, rise):
    im = Image.new("RGBA", (W, H), INK + (255,))
    m, pad = mark(light, outline, glow)
    im.alpha_composite(m, (MX - pad, MY - pad))
    if word > 0:
        t = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        d = ImageDraw.Draw(t)
        tw = d.textlength("Oriel", font=word_font)
        d.text(((W - tw) / 2, MY + MH + 64 + rise), "Oriel", font=word_font, fill=PAPER + (int(255 * word),))
        im.alpha_composite(t)
    return im.convert("RGB")


def jpg(im):
    b = io.BytesIO()
    im.save(b, "JPEG", quality=90)
    return b.getvalue()


intro, loop = [], []
N = 45
for i in range(N):
    t = i / (N - 1)
    outline = ease(t / 0.35)
    light = ease((t - 0.2) / 0.5)
    glow = 0.55 * ease((t - 0.3) / 0.5)
    word = ease((t - 0.5) / 0.4)
    rise = 18 * (1 - word)
    intro.append(frame(light, outline, glow, word, rise))
L = 50
for i in range(L):
    k = 0.5 - 0.5 * math.cos(2 * math.pi * i / L)
    loop.append(frame(1.0, 1.0, 0.55 + 0.25 * k, 1.0, 0))

with zipfile.ZipFile(os.path.join(out, "bootanimation.zip"), "w", zipfile.ZIP_STORED) as z:
    z.writestr("desc.txt", f"{W} {H} {FPS}\np 1 0 part0\np 0 0 part1\n")
    for i, f in enumerate(intro):
        z.writestr(f"part0/{i:03d}.jpg", jpg(f))
    for i, f in enumerate(loop):
        z.writestr(f"part1/{i:03d}.jpg", jpg(f))

# boot logo: the finished frame at 1280x720, baseline 4:2:0 like the maker's, which the boot loader decodes
loop[0].resize((1280, 720), Image.LANCZOS).save(os.path.join(out, "logo.jpg"), "JPEG", quality=92, subsampling=2)
print("ok")

# boot video, for boards set to play a video instead of the animation: intro then two loops, 720p, silent
import subprocess, tempfile
with tempfile.TemporaryDirectory() as d:
    for i, f in enumerate(intro + loop + loop):
        f.resize((1280, 720), Image.LANCZOS).save(os.path.join(d, f"{i:04d}.png"))
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-framerate", str(FPS), "-i", os.path.join(d, "%04d.png"),
                    "-c:v", "libx264", "-preset", "veryfast", "-profile:v", "main", "-pix_fmt", "yuv420p", "-crf", "20",
                    "-movflags", "+faststart", os.path.join(out, "bootvideo.mp4")], check=True)
print("video ok")
