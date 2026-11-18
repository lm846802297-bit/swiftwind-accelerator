"""生成花意加速器的启动图标（各密度 PNG）。"""
import os

from PIL import Image, ImageDraw

SIZE = 1024
BOLT = [(54, 22), (33, 58), (49, 58), (43, 86), (75, 46), (57, 46)]
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
BASE = r"C:\Users\84680\Desktop\加速器开发\app\src\main\res"


def gradient(size):
    img = Image.new("RGBA", (size, size))
    draw = ImageDraw.Draw(img)
    for y in range(size):
        t = y / max(1, size - 1)
        r = int(14 + (7 - 14) * t)
        g = int(34 + (16 - 34) * t)
        b = int(48 + (25 - 48) * t)
        draw.line([(0, y), (size, y)], fill=(r, g, b, 255))
    return img


def make_icon(round_icon=False):
    img = gradient(SIZE)
    mask = Image.new("L", (SIZE, SIZE), 0)
    md = ImageDraw.Draw(mask)
    if round_icon:
        md.ellipse([0, 0, SIZE - 1, SIZE - 1], fill=255)
    else:
        md.rounded_rectangle([0, 0, SIZE - 1, SIZE - 1], radius=210, fill=255)
    img.putalpha(mask)

    draw = ImageDraw.Draw(img)
    scale = SIZE / 108
    pts = [(x * scale, y * scale) for x, y in BOLT]
    draw.polygon(pts, fill=(82, 230, 192, 255))

    # 细微高光，让闪电更有层次
    inner = [
        ((33 * scale + 24 * scale) / 2, (58 * scale + 22 * scale) / 2),
        (49 * scale, 58 * scale),
        (43 * scale, 86 * scale),
    ]
    draw.polygon(inner, fill=(184, 255, 240, 255))
    return img


def main():
    square = make_icon(False)
    rounded = make_icon(True)
    for name, px in DENSITIES.items():
        folder = f"{BASE}\\mipmap-{name}"
        os.makedirs(folder, exist_ok=True)
        square.resize((px, px), Image.LANCZOS).save(f"{folder}\\ic_launcher.png")
        rounded.resize((px, px), Image.LANCZOS).save(f"{folder}\\ic_launcher_round.png")
    square.resize((512, 512), Image.LANCZOS).save(f"{BASE}\\mipmap-xxxhdpi\\ic_launcher_playstore.png")


if __name__ == "__main__":
    main()
