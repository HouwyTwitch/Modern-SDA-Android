#!/usr/bin/env python3
"""
Generate Android launcher icon PNGs from icon.png at repo root.

Usage:
    pip install Pillow
    python generate_icons.py

Place your icon.png (any square PNG) in the same directory as this script.
"""

import os
import shutil
from pathlib import Path
from PIL import Image

REPO_ROOT = Path(__file__).parent
RES_DIR = REPO_ROOT / "app" / "src" / "main" / "res"

# (density, legacy_size, adaptive_foreground_size)
DENSITIES = [
    ("mipmap-mdpi",    48,  108),
    ("mipmap-hdpi",    72,  162),
    ("mipmap-xhdpi",   96,  216),
    ("mipmap-xxhdpi",  144, 324),
    ("mipmap-xxxhdpi", 192, 432),
]

ADAPTIVE_ICON_XML = """\
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""

def make_dirs():
    for density, *_ in DENSITIES:
        (RES_DIR / density).mkdir(parents=True, exist_ok=True)


def save_png(img: Image.Image, path: Path, size: int):
    resized = img.resize((size, size), Image.LANCZOS)
    resized.save(path, "PNG")
    print(f"  wrote {path.relative_to(REPO_ROOT)}")


def add_safe_zone_padding(img: Image.Image, foreground_px: int) -> Image.Image:
    """
    Adaptive icon foreground canvas is 108dp; safe zone is inner 72dp (2/3).
    Scale the icon down to fit the safe zone, then center on a transparent canvas.
    """
    safe = round(foreground_px * 72 / 108)
    canvas = Image.new("RGBA", (foreground_px, foreground_px), (0, 0, 0, 0))
    icon = img.resize((safe, safe), Image.LANCZOS)
    offset = (foreground_px - safe) // 2
    canvas.paste(icon, (offset, offset), icon if icon.mode == "RGBA" else None)
    return canvas


def main():
    src = REPO_ROOT / "icon.png"
    if not src.exists():
        print(f"ERROR: {src} not found. Place your square icon.png at the repo root.")
        raise SystemExit(1)

    img = Image.open(src).convert("RGBA")
    if img.width != img.height:
        print("WARNING: icon.png is not square — it will be stretched.")

    make_dirs()
    print("Generating legacy launcher PNGs…")
    for density, legacy_size, fg_size in DENSITIES:
        out_dir = RES_DIR / density
        save_png(img, out_dir / "ic_launcher.png", legacy_size)
        save_png(img, out_dir / "ic_launcher_round.png", legacy_size)
        # Adaptive foreground (with safe-zone padding)
        fg = add_safe_zone_padding(img, fg_size)
        save_png(fg, out_dir / "ic_launcher_foreground.png", fg_size)

    print("\nUpdating adaptive icon XMLs…")
    anydpi_dir = RES_DIR / "mipmap-anydpi-v26"
    anydpi_dir.mkdir(parents=True, exist_ok=True)
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        path = anydpi_dir / name
        path.write_text(ADAPTIVE_ICON_XML)
        print(f"  wrote {path.relative_to(REPO_ROOT)}")

    # Remove the old vector foreground so there's no resource name conflict
    old_vector = RES_DIR / "drawable" / "ic_launcher_foreground.xml"
    if old_vector.exists():
        old_vector.unlink()
        print(f"\n  removed {old_vector.relative_to(REPO_ROOT)} (replaced by PNG mipmaps)")

    print("\nDone! Open Android Studio and rebuild the project.")


if __name__ == "__main__":
    main()
