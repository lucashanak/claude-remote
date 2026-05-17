#!/usr/bin/env python3
"""Generate Claude Remote app icon in the new CR design language.

- Dark slate navy gradient background
- Sky accent radial glow
- Claude four-petal asterisk in Sky accent on white
Produces 1024x1024 master, rasterizes to Android mipmaps + desktop sizes.
"""
import math
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

REPO = Path("/home/lucas/claude-remote")
M = 1024  # master size

# CRTheme color tokens
BG_TOP = (15, 23, 42, 255)        # slate-900 #0F172A
BG_BOT = (30, 41, 59, 255)        # slate-800 #1E293B
ACCENT = (56, 189, 248, 255)      # Sky #38BDF8
ACCENT_DIM = (56, 189, 248, 120)  # glow
WHITE = (226, 232, 240, 255)      # slate-200 text


def vertical_gradient(size, top, bot):
    img = Image.new("RGBA", (size, size))
    for y in range(size):
        t = y / (size - 1)
        c = tuple(int(top[i] * (1 - t) + bot[i] * t) for i in range(4))
        for x in range(size):
            img.putpixel((x, y), c)
    return img


def rounded_mask(size, radius):
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle([(0, 0), (size - 1, size - 1)], radius=radius, fill=255)
    return mask


def make_glow(size, color):
    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(glow)
    cx = cy = size // 2
    # Layered radial — many translucent ellipses
    for r in range(size // 2, 0, -8):
        alpha = int(color[3] * (1 - r / (size / 2)) * 0.55)
        d.ellipse([cx - r, cy - r, cx + r, cy + r],
                  fill=(color[0], color[1], color[2], alpha))
    return glow.filter(ImageFilter.GaussianBlur(radius=size * 0.08))


def petal(d, cx, cy, length, width, angle_deg, color):
    """Draw one Claude petal as a rotated ellipse anchored at center."""
    # Build petal as horizontal ellipse, then rotate by drawing into a temp layer
    tmp = Image.new("RGBA", (length, length), (0, 0, 0, 0))
    td = ImageDraw.Draw(tmp)
    half = length // 2
    td.ellipse([half - length // 2 + 30, half - width // 2,
                half + length // 2 - 30, half + width // 2],
               fill=color)
    rotated = tmp.rotate(angle_deg, resample=Image.BICUBIC)
    # Paste centered at (cx, cy)
    paste_x = cx - rotated.width // 2
    paste_y = cy - rotated.height // 2
    d.bitmap((0, 0), Image.new("L", (1, 1)))  # noop to satisfy linters
    return rotated, (paste_x, paste_y)


def build_master(size=M):
    # Background gradient
    bg = vertical_gradient(size, BG_TOP, BG_BOT)

    # Sky radial glow
    glow_layer = make_glow(int(size * 0.85), ACCENT_DIM)
    gx = (size - glow_layer.width) // 2
    bg.alpha_composite(glow_layer, (gx, gx))

    d = ImageDraw.Draw(bg, "RGBA")
    cx = cy = size // 2

    # Claude four-petal asterisk (rotated ellipses at 0/45/90/135 deg)
    petal_len = int(size * 0.72)
    petal_w = int(size * 0.18)
    for ang in (45, 135):
        layer, pos = petal(d, cx, cy, petal_len, petal_w, ang, WHITE)
        bg.alpha_composite(layer, pos)

    # Center accent dot
    r = int(size * 0.07)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=ACCENT)
    r2 = int(size * 0.045)
    d.ellipse([cx - r2, cy - r2, cx + r2, cy + r2], fill=BG_TOP)

    # Apply rounded-square mask (22% radius — iOS-style continuous corner)
    radius = int(size * 0.22)
    mask = rounded_mask(size, radius)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(bg, (0, 0), mask)
    return out


def build_adaptive_bg(size=M):
    """Android adaptive icon background layer — full bleed gradient (no mask)."""
    bg = vertical_gradient(size, BG_TOP, BG_BOT)
    glow_layer = make_glow(int(size * 0.85), ACCENT_DIM)
    gx = (size - glow_layer.width) // 2
    bg.alpha_composite(glow_layer, (gx, gx))
    return bg


def build_adaptive_fg(size=M):
    """Android adaptive icon foreground layer — petals on transparent bg, padded for 66% safe zone."""
    fg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(fg, "RGBA")
    cx = cy = size // 2
    # Petals slightly smaller because Android adaptive crops corners more aggressively
    petal_len = int(size * 0.58)
    petal_w = int(size * 0.15)
    for ang in (45, 135):
        layer, pos = petal(d, cx, cy, petal_len, petal_w, ang, WHITE)
        fg.alpha_composite(layer, pos)
    r = int(size * 0.06)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=ACCENT)
    r2 = int(size * 0.038)
    d.ellipse([cx - r2, cy - r2, cx + r2, cy + r2], fill=BG_TOP)
    return fg


def main():
    print("Building master 1024x1024...")
    master = build_master(M)
    out = REPO / "design_handoff" / "icons"
    out.mkdir(parents=True, exist_ok=True)
    master.save(out / "icon_master.png")

    # Android legacy mipmaps (full icon, rounded-square)
    mipmaps = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for name, sz in mipmaps.items():
        scaled = master.resize((sz, sz), Image.LANCZOS)
        target = REPO / "androidApp" / "src" / "main" / "res" / f"mipmap-{name}" / "ic_launcher.png"
        scaled.save(target)
        # also round variant
        target_round = REPO / "androidApp" / "src" / "main" / "res" / f"mipmap-{name}" / "ic_launcher_round.png"
        scaled.save(target_round)
        print(f"  android mipmap-{name} ({sz}px) → {target.name} + ic_launcher_round.png")

    # Android adaptive (foreground/background separate)
    bg = build_adaptive_bg(M)
    fg = build_adaptive_fg(M)
    for name, sz in mipmaps.items():
        # Adaptive uses 108dp canvas; we approximate using the same px sizes
        bg.resize((sz, sz), Image.LANCZOS).save(
            REPO / "androidApp" / "src" / "main" / "res" / f"mipmap-{name}" / "ic_launcher_background.png"
        )
        fg.resize((sz, sz), Image.LANCZOS).save(
            REPO / "androidApp" / "src" / "main" / "res" / f"mipmap-{name}" / "ic_launcher_foreground.png"
        )
    print("  android adaptive bg+fg written across all densities")

    # Desktop PNG (512)
    master.resize((512, 512), Image.LANCZOS).save(
        REPO / "desktopApp" / "src" / "main" / "resources" / "icon.png"
    )
    print("  desktop icon.png 512x512 written")

    # Desktop ICO (multi-size for Windows)
    ico_sizes = [(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
    master.save(
        REPO / "desktopApp" / "src" / "main" / "resources" / "icon.ico",
        format="ICO",
        sizes=ico_sizes,
    )
    print(f"  desktop icon.ico written ({len(ico_sizes)} sizes)")

    print("Done.")


if __name__ == "__main__":
    main()
