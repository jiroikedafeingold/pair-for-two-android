#!/usr/bin/env python3
"""Generate the Android launcher icons and the Play Store listing art from the iOS assets.

The two apps must look like siblings on a home screen, so the art is not redrawn — it is the very
same `icon_1024.png` the iOS asset catalog ships, resampled here. Re-run this rather than editing
any of the generated files:

    python3 tools/generate-icon.py

What it writes:

- `mipmap-*/ic_launcher_foreground.png` — the adaptive icon's foreground: the art drawn at 72 of
  the 108dp canvas, centred on transparency, with felt behind it from
  `mipmap-anydpi-v26/ic_launcher.xml`. 72 is what a launcher mask actually reveals, so the whole
  two-card composition survives with only its corners clipped. At the full 108 the mask ate the
  tops and bottoms of both cards, and Android 12's splash — which insets further still — cropped
  them to a band.
- `mipmap-*/ic_launcher.webp` and `ic_launcher_round.webp` — the legacy bitmaps. `minSdk 26` means
  every device gets the adaptive icon, but some launchers and tools still read these, and leaving
  the Android Studio template droid in them would be a trap for exactly one embarrassing screenshot.
- `drawable-xxxhdpi/splash_icon_foreground.webp` — the same art again, for the launch screen. It
  needs its own copy because the splash draws the icon *much* larger than a launcher does: the
  canvas is 288dp rather than 108dp, so at xxhdpi it wants 864px and the 432px launcher foreground
  was being blown up nearly threefold and looked it. This one is a 288dp asset at xxxhdpi — 1152px —
  which every density can scale *down* from. WebP at 95 rather than PNG: the same pixels cost
  882KB lossless and 179KB here, and the difference is not visible on a card back.
- `fastlane/metadata/android/en-GB/images/icon.png` — the 512px Play Store listing icon.
- `fastlane/metadata/android/en-GB/images/featureGraphic.png` — the 1024×500 banner at the top of
  the Play listing, cropped from iOS's `splash.png` so the store shows the art the app opens on.
  Both are flattened to RGB: Play rejects transparency in either slot.
"""

from pathlib import Path

from PIL import Image, ImageDraw

REPO = Path(__file__).resolve().parent.parent
IOS_ASSETS = Path(
    "/Users/jirofeingold/Projects/Pair for two/Pair for two/Assets.xcassets"
)
SOURCE = IOS_ASSETS / "AppIcon.appiconset/icon_1024.png"
BANNER = IOS_ASSETS / "SplashArt.imageset/splash.png"
# Play's feature graphic, fixed by the store at 1024×500.
FEATURE_PX = (1024, 500)

# Adaptive foreground: a 108dp square canvas at each density...
FOREGROUND_PX = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
# ...with the art occupying the central 72 of those 108 units — the region a mask reveals.
ART_FRACTION = 72 / 108
# Splash foreground: the same 108-unit composition, but drawn for a 288dp canvas at xxxhdpi. Only
# the one bucket — it is the largest any device asks for, and smaller densities prescale it down.
SPLASH_PX = 288 * 4
# Legacy launcher bitmap: 48dp square at each density.
LEGACY_PX = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def rounded(image: Image.Image, radius_fraction: float) -> Image.Image:
    """A copy of `image` with its corners rounded — `radius_fraction` of the shorter side."""
    mask = Image.new("L", image.size, 0)
    radius = int(min(image.size) * radius_fraction)
    ImageDraw.Draw(mask).rounded_rectangle([(0, 0), (image.width - 1, image.height - 1)],
                                           radius=radius, fill=255)
    out = image.copy()
    out.putalpha(mask)
    return out


def main() -> None:
    source = Image.open(SOURCE).convert("RGBA")
    res = REPO / "app/src/main/res"

    for density, size in FOREGROUND_PX.items():
        canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        art = int(round(size * ART_FRACTION))
        offset = (size - art) // 2
        canvas.paste(source.resize((art, art), Image.LANCZOS), (offset, offset))
        path = res / f"mipmap-{density}/ic_launcher_foreground.png"
        canvas.save(path, optimize=True)
        print(path.relative_to(REPO))

    splash = Image.new("RGBA", (SPLASH_PX, SPLASH_PX), (0, 0, 0, 0))
    splash_art = int(round(SPLASH_PX * ART_FRACTION))
    splash_offset = (SPLASH_PX - splash_art) // 2
    splash.paste(source.resize((splash_art, splash_art), Image.LANCZOS), (splash_offset,) * 2)
    splash_path = res / "drawable-xxxhdpi/splash_icon_foreground.webp"
    splash_path.parent.mkdir(parents=True, exist_ok=True)
    splash.save(splash_path, "WEBP", quality=95, method=6)
    print(splash_path.relative_to(REPO))

    for density, size in LEGACY_PX.items():
        scaled = source.resize((size, size), Image.LANCZOS)
        square = res / f"mipmap-{density}/ic_launcher.webp"
        # 22% matches the corner radius Android's own legacy icons use at this size.
        rounded(scaled, 0.22).save(square, "WEBP", lossless=True)
        print(square.relative_to(REPO))

        circle = res / f"mipmap-{density}/ic_launcher_round.webp"
        rounded(scaled, 0.5).save(circle, "WEBP", lossless=True)
        print(circle.relative_to(REPO))

    images = REPO / "fastlane/metadata/android/en-GB/images"
    images.mkdir(parents=True, exist_ok=True)

    store = images / "icon.png"
    source.resize((512, 512), Image.LANCZOS).convert("RGB").save(store, optimize=True)
    print(store.relative_to(REPO))

    # The banner is 2.18:1 and the feature graphic is 2.05:1, so take the full height and trim the
    # sides evenly. The title sits dead centre, so an even crop leaves it centred — which matters,
    # because Play crops this further for some placements and always from the edges.
    banner = Image.open(BANNER).convert("RGB")
    keep = int(round(banner.height * FEATURE_PX[0] / FEATURE_PX[1]))
    left = (banner.width - keep) // 2
    feature = banner.crop((left, 0, left + keep, banner.height))
    feature = feature.resize(FEATURE_PX, Image.LANCZOS)
    feature_path = images / "featureGraphic.png"
    feature.save(feature_path, optimize=True)
    print(feature_path.relative_to(REPO))


if __name__ == "__main__":
    main()
