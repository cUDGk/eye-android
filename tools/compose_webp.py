"""Compose chair_render/frame_*.png into an animated WebP."""
import glob
import os
import sys

from PIL import Image

frame_dir = r"C:\Users\user\Desktop\eye-android\chair_render"
out_path = r"C:\Users\user\Desktop\eye-android\chair_loop.webp"

frames = sorted(glob.glob(os.path.join(frame_dir, "frame_*.png")))
if not frames:
    print("No frames found")
    sys.exit(1)
print(f"Loading {len(frames)} frames...")

images = [Image.open(f).convert("RGBA") for f in frames]
print(f"First frame size: {images[0].size}")

# 30fps = 33.33ms/frame; pass per-frame duration list so it sticks
PER_FRAME_MS = 33
durations = [PER_FRAME_MS] * len(images)

images[0].save(
    out_path,
    save_all=True,
    append_images=images[1:],
    format="WEBP",
    duration=durations,
    loop=0,
    quality=88,
    method=6,
    minimize_size=False,
    lossless=False,
    allow_mixed=False,
    background=(0, 0, 0, 0),
    disposal=2,
)

size_mb = os.path.getsize(out_path) / 1024 / 1024
print(f"Wrote {out_path}: {size_mb:.2f} MB ({len(images)} frames @ {DURATION}ms = {DURATION*len(images)}ms loop)")
