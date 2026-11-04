import math, random
from PIL import Image, ImageDraw, ImageFilter

random.seed(7)

# ---------- 基础画布 ----------
def vertical_gradient(w, h, top, mid, bot):
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        r = y / max(1, h - 1)
        if r < 0.5:
            t = r / 0.5
            c = tuple(int(top[i] + (mid[i] - top[i]) * t) for i in range(3))
        else:
            t = (r - 0.5) / 0.5
            c = tuple(int(mid[i] + (bot[i] - mid[i]) * t) for i in range(3))
        for x in range(w):
            px[x, y] = c
    return img

def add_rain(img, count, tilt=-18):
    w, h = img.size
    d = ImageDraw.Draw(img, "RGBA")
    import math as m
    ang = math.radians(tilt)
    for _ in range(count):
        x = random.randint(-w // 3, w)
        y = random.randint(0, h)
        ln = random.randint(18, 46)
        alpha = random.randint(30, 90)
        col = (200, 215, 245, alpha)
        dx = math.sin(ang) * ln
        dy = math.cos(ang) * ln
        d.line([x, y, x + dx, y + dy], fill=col, width=random.choice([1, 2]))

def radial_glow(base, cx, cy, radius, color, strength):
    w, h = base.size
    glow = Image.new("RGB", (w, h), (0, 0, 0))
    gd = ImageDraw.Draw(glow)
    for i in range(radius, 0, -4):
        a = strength * (1 - i / radius)
        gd.ellipse([cx - i, cy - i, cx + i, cy + i], fill=(int(color[0]*a), int(color[1]*a), int(color[2]*a)))
    glow = glow.filter(ImageFilter.GaussianBlur(radius // 3))
    return Image.blend(base, ImageChops_screen(base, glow), 1.0) if False else ImageChops_screen(base, glow)

from PIL import ImageChops

# ---------- 小女孩背影（打伞） ----------
def draw_girl(d, cx, ground_y, s):
    """s 为比例系数，决定女孩整体大小"""
    # 雨靴
    boot_w = 26 * s
    boot_h = 30 * s
    leg_gap = 10 * s
    d.ellipse([cx - boot_w - leg_gap, ground_y - boot_h, cx - leg_gap, ground_y], fill=(50, 45, 60))
    d.ellipse([cx + leg_gap, ground_y - boot_h, cx + boot_w + leg_gap, ground_y], fill=(50, 45, 60))
    # 腿
    d.rectangle([cx - 16 * s, ground_y - boot_h - 60 * s, cx - 6 * s, ground_y - boot_h], fill=(40, 38, 52))
    d.rectangle([cx + 6 * s, ground_y - boot_h - 60 * s, cx + 16 * s, ground_y - boot_h], fill=(40, 38, 52))
    # 黄色雨衣身体（梯形下摆）
    coat_top = ground_y - boot_h - 60 * s
    coat_bot = ground_y - boot_h
    d.polygon([(cx - 46 * s, coat_top), (cx + 46 * s, coat_top),
               (cx + 62 * s, coat_bot), (cx - 62 * s, coat_bot)], fill=(238, 180, 60))
    # 雨衣高光
    d.polygon([(cx - 20 * s, coat_top), (cx + 20 * s, coat_top),
               (cx + 34 * s, coat_bot), (cx - 34 * s, coat_bot)], fill=(250, 205, 95))
    # 兜帽头
    head_r = 34 * s
    head_cy = coat_top - head_r + 4 * s
    d.ellipse([cx - head_r, head_cy - head_r, cx + head_r, head_cy + head_r], fill=(238, 180, 60))
    d.ellipse([cx - head_r * 0.78, head_cy - head_r * 0.9, cx + head_r * 0.78, head_cy + head_r * 0.7], fill=(250, 205, 95))
    # 小脸（从兜帽下露出一角，微侧）
    face_r = head_r * 0.55
    d.ellipse([cx - face_r, head_cy - face_r * 0.6, cx + face_r, head_cy + face_r * 1.3], fill=(255, 226, 199))
    # 伞柄（弯柄，从伞顶下来）
    # 伞面（红色，略向观众倾斜，画成侧视的伞）
    umbrella_top = head_cy - head_r - 70 * s
    d.line([cx, umbrella_top, cx + 6 * s, head_cy - head_r], fill=(70, 50, 50), width=int(6 * s))
    # 伞柄末端弯钩
    d.arc([cx - 20 * s, head_cy - head_r - 24 * s, cx + 20 * s, head_cy - head_r + 20 * s], 180, 360, fill=(70, 50, 50), width=int(6 * s))
    # 伞面：大圆弧 + 伞骨
    uw = 130 * s
    uy = umbrella_top
    d.pieslice([cx - uw, uy - uw * 0.95, cx + uw, uy + uw * 0.15], 180, 360, fill=(214, 66, 66))
    d.pieslice([cx - uw * 0.66, uy - uw * 0.72, cx + uw * 0.66, uy + uw * 0.05], 180, 360, fill=(235, 96, 88))
    # 伞骨
    for k in range(-3, 4):
        ex = cx + k * uw * 0.28
        d.line([cx, uy - 4 * s, ex, uy - uw * 0.88 + abs(k) * 4 * s], fill=(180, 52, 52), width=max(1, int(2 * s)))
    # 伞顶小尖
    d.polygon([(cx - 4 * s, uy - uw * 0.95 - 8 * s), (cx + 4 * s, uy - uw * 0.95 - 8 * s), (cx, uy - uw * 0.95 + 2 * s)], fill=(160, 45, 45))

def draw_scene(w, h, girl_scale, lamp=True):
    img = vertical_gradient(w, h, (10, 14, 34), (22, 30, 55), (30, 38, 64))
    # 远处地面
    ground_y = int(h * 0.86)
    d = ImageDraw.Draw(img, "RGBA")
    d.rectangle([0, ground_y, w, h], fill=(20, 22, 40, 255))
    # 地平线处一条亮边（积水反光）
    d.rectangle([0, ground_y, w, ground_y + 6], fill=(46, 56, 92, 255))
    # 路灯（左上，暖黄光）
    if lamp:
        lx, ly = int(w * 0.16), int(h * 0.20)
        glow = Image.new("RGB", (w, h), (0, 0, 0))
        gd = ImageDraw.Draw(glow)
        for i in range(int(h * 0.42), 0, -5):
            a = (1 - i / (h * 0.42)) ** 2
            gd.ellipse([lx - i, ly - i, lx + i, ly + i], fill=(int(255 * a * 0.5), int(210 * a * 0.5), int(140 * a * 0.5)))
        glow = glow.filter(ImageFilter.GaussianBlur(40))
        img = ImageChops.screen(img, glow)
        d = ImageDraw.Draw(img, "RGBA")
        # 灯杆
        d.rectangle([lx - 5, ly, lx + 5, ground_y], fill=(24, 26, 40, 255))
        d.ellipse([lx - 26, ly - 26, lx + 26, ly + 26], fill=(255, 226, 150, 255))
        d.ellipse([lx - 26, ly - 26, lx + 26, ly + 26], outline=(255, 244, 200, 255), width=3)
    # 雨
    add_rain(img, int(w * h / 2600))
    d = ImageDraw.Draw(img, "RGBA")
    # 女孩位置（偏右，留出左侧路灯）
    cx = int(w * 0.60)
    draw_girl(d, cx, ground_y, girl_scale)
    # 脚下倒影（积水）
    d.ellipse([cx - 120 * girl_scale, ground_y + 8, cx + 120 * girl_scale, ground_y + 40], fill=(30, 32, 52, 200))
    d.ellipse([cx - 70 * girl_scale, ground_y + 10, cx + 70 * girl_scale, ground_y + 30], fill=(238, 180, 60, 90))
    d.ellipse([cx - 40 * girl_scale, ground_y + 10, cx + 40 * girl_scale, ground_y + 24], fill=(214, 66, 66, 110))
    return img

# ---------- 壁纸 ----------
wp = draw_scene(2048, 3644, 1.0)   # 2x 超采样
wp = wp.resize((1024, 1822), Image.LANCZOS)
wp.save("/root/.codebuddy/artifact/sw_fix/art_gen/wallpaper.jpg", quality=92)

# ---------- 图标方形（女孩放大居中） ----------
icon = draw_scene(2048, 2048, 1.9)
icon = icon.resize((1024, 1024), Image.LANCZOS)
icon.save("/root/.codebuddy/artifact/sw_fix/art_gen/icon.png")

# ---------- adaptive icon 前景（透明底，主体居中安全区） ----------
fg = Image.new("RGBA", (2048, 2048), (0, 0, 0, 0))
fd = ImageDraw.Draw(fg, "RGBA")
# 安全区中心 66%，主体约占 55%
fg_ground = int(2048 * 0.83)
draw_girl(fd, 1024, fg_ground, 1.9)
fg.save("/root/.codebuddy/artifact/sw_fix/art_gen/foreground.png")

print("done")
