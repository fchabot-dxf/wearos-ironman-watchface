#!/usr/bin/env python3
"""
Shapes watch face generator.

Single source of truth for the "Shapes" analog face: edit CONFIG below on your
computer, then run

    python shapes/tools/generate_face.py

It writes:
  shapes/src/main/res/raw/watchface.xml      (Watch Face Format, v2)
  shapes/src/main/res/drawable/preview.png   (picker thumbnail)
  shapes/preview/active.png, ambient.png     (for eyeballing, git-ignored)

The XML and the PNG previews are drawn from the same geometry, so what you see
in the preview is what the watch draws.
"""
import math
import os
import sys

# --------------------------------------------------------------------------
# CONFIG - everything you'd want to tweak lives here
# --------------------------------------------------------------------------
CONFIG = {
    "background": "#000000",
    "marker_radius": 188,          # distance of marker centers from dial center
    "marker_box": 40,              # each marker is drawn in a 40x40 box
    "dim_alpha": 175,              # non-current markers (0-255)
    "current_scale": 1.4,          # current-hour marker pops by this much
    "current_spins": True,         # current-hour marker turns once a minute
    "ambient_marker_alpha": 70,    # markers in always-on display
    # Marker palette, clockwise from 12. Mono style uses mono_marker for all.
    "palette": [
        "#4DD0E1", "#FF8A65", "#FFD54F", "#AED581", "#7986CB", "#F06292",
        "#4DB6AC", "#FFB74D", "#BA68C8", "#90CAF9", "#DCE775", "#E57373",
    ],
    "mono_marker": "#E6E6EA",
    # Hour hand colour choices (shown in the watch's Customize > Hour hand)
    "hour_hand_colors": [
        ("coral", "Coral", "#FF5F57"),
        ("lime", "Lime", "#B8E986"),
        ("sky", "Sky", "#6EC6FF"),
        ("amber", "Amber", "#FFC24D"),
        ("white", "White", "#F2F2F2"),
    ],
    "minute_hand": "#FFFFFF",
    "second_hand": "#FFC24D",
    "complication_bg": "#22212A",
    "complication_ring": "#6EC6FF",
    "complication_text": "#FFFFFF",
    "preview_time": (10, 9, 32),   # h, m, s used for previews + PREVIEW_TIME
}

# Marker shapes, one per hour, in a local 40x40 box whose "up" points outward
# from the dial center. Each entry: (extra_rotation_deg, [primitives]).
#   ("ell",  x, y, w, h)               filled ellipse
#   ("ellS", x, y, w, h, thickness)    stroked ellipse (ring)
#   ("rr",   x, y, w, h, r)            filled rounded rect
#   ("rrS",  x, y, w, h, r, thickness) stroked rounded rect
#   ("arc",  cx, cy, d, start, end, thickness)
MARKERS = [
    (0,  [("rr", 14, 3, 12, 34, 6)]),                          # 12 capsule
    (0,  [("ell", 8, 8, 24, 24)]),                             # 1 dot
    (45, [("rr", 9, 9, 22, 22, 4)]),                           # 2 diamond
    (0,  [("ellS", 9, 9, 22, 22, 6)]),                         # 3 ring
    (0,  [("rr", 8, 8, 24, 24, 8)]),                           # 4 squircle
    (0,  [("rr", 16, 5, 8, 30, 4), ("rr", 5, 16, 30, 8, 4)]),  # 5 plus
    (0,  [("arc", 20, 24, 12, -90, 90, 12)]),                  # 6 half disc
    (0,  [("ell", 5, 13, 14, 14), ("ell", 21, 13, 14, 14)]),   # 7 twin dots
    (45, [("rrS", 10, 10, 20, 20, 3, 5)]),                     # 8 diamond outline
    (0,  [("rr", 3, 14, 34, 12, 6)]),                          # 9 bar
    (0,  [("rrS", 9, 9, 22, 22, 7, 5)]),                       # 10 hollow squircle
    (0,  [("ell", 5, 11, 30, 18)]),                            # 11 bean
]

W = H = 450
CX = CY = 225
HERE = os.path.dirname(os.path.abspath(__file__))
MODULE = os.path.dirname(HERE)


# --------------------------------------------------------------------------
# XML generation
# --------------------------------------------------------------------------
def marker_pos(i):
    a = math.radians(i * 30)
    r = CONFIG["marker_radius"]
    return CX + r * math.sin(a), CY - r * math.cos(a)


def prim_xml(p, color, indent):
    k = p[0]
    pad = " " * indent
    if k == "ell":
        _, x, y, w, h = p
        return f'{pad}<Ellipse x="{x}" y="{y}" width="{w}" height="{h}"><Fill color="{color}"/></Ellipse>'
    if k == "ellS":
        _, x, y, w, h, t = p
        return f'{pad}<Ellipse x="{x}" y="{y}" width="{w}" height="{h}"><Stroke color="{color}" thickness="{t}"/></Ellipse>'
    if k == "rr":
        _, x, y, w, h, r = p
        return f'{pad}<RoundRectangle x="{x}" y="{y}" width="{w}" height="{h}" cornerRadiusX="{r}" cornerRadiusY="{r}"><Fill color="{color}"/></RoundRectangle>'
    if k == "rrS":
        _, x, y, w, h, r, t = p
        return f'{pad}<RoundRectangle x="{x}" y="{y}" width="{w}" height="{h}" cornerRadiusX="{r}" cornerRadiusY="{r}"><Stroke color="{color}" thickness="{t}"/></RoundRectangle>'
    if k == "arc":
        _, cx, cy, d, s, e, t = p
        # An arc of diameter d with thickness d fills a solid sector.
        return (f'{pad}<Arc centerX="{cx}" centerY="{cy}" width="{d}" height="{d}" '
                f'startAngle="{s}" endAngle="{e}"><Stroke color="{color}" thickness="{t}"/></Arc>')
    raise ValueError(k)


def markers_group(style_name, colors):
    b = CONFIG["marker_box"]
    out = [f'                <Group name="markers_{style_name}" x="0" y="0" width="{W}" height="{H}">',
           f'                    <Variant mode="AMBIENT" target="alpha" value="{CONFIG["ambient_marker_alpha"]}"/>']
    for i, (rot, prims) in enumerate(MARKERS):
        mx, my = marker_pos(i)
        x, y = round(mx - b / 2), round(my - b / 2)
        base = i * 30 + rot
        hour = 12 if i == 0 else i
        is_now = f"[HOUR_0_11] == {i}"
        spin = f" + ({is_now} ? [SECOND] * 6 : 0)" if CONFIG["current_spins"] else ""
        out.append(f'                    <!-- {hour} o\'clock -->')
        out.append(f'                    <PartDraw x="{x}" y="{y}" width="{b}" height="{b}" '
                   f'pivotX="0.5" pivotY="0.5" angle="{base}" alpha="{CONFIG["dim_alpha"]}">')
        out.append(f'                        <Transform target="angle" value="{base}{spin}"/>')
        s = CONFIG["current_scale"]
        out.append(f'                        <Transform target="scaleX" value="{is_now} ? {s} : 1"/>')
        out.append(f'                        <Transform target="scaleY" value="{is_now} ? {s} : 1"/>')
        out.append(f'                        <Transform target="alpha" value="{is_now} ? 255 : {CONFIG["dim_alpha"]}"/>')
        for p in prims:
            out.append(prim_xml(p, colors[i], 24))
        out.append('                    </PartDraw>')
    out.append('                </Group>')
    return "\n".join(out)


HOUR_ANGLE = "([HOUR_0_11] + [MINUTE] / 60) * 30"
MINUTE_ANGLE = "([MINUTE] + [SECOND] / 60) * 6"
SECOND_ANGLE = "[SECOND] * 6"


def hand_xml():
    hc = "[CONFIGURATION.hand_color.0]"
    return f"""
        <!-- ================= HOUR HAND (colour from Customize) ================ -->
        <PartDraw x="0" y="0" width="{W}" height="{H}" pivotX="0.5" pivotY="0.5" angle="0">
            <Transform target="angle" value="{HOUR_ANGLE}"/>
            <Variant mode="AMBIENT" target="alpha" value="0"/>
            <RoundRectangle x="210" y="108" width="30" height="132" cornerRadiusX="15" cornerRadiusY="15"><Fill color="{hc}"/></RoundRectangle>
        </PartDraw>
        <!-- Ambient: outline-only hour hand, ~90% fewer lit pixels -->
        <PartDraw x="0" y="0" width="{W}" height="{H}" pivotX="0.5" pivotY="0.5" angle="0" alpha="0">
            <Transform target="angle" value="{HOUR_ANGLE}"/>
            <Variant mode="AMBIENT" target="alpha" value="255"/>
            <RoundRectangle x="211.5" y="109.5" width="27" height="129" cornerRadiusX="13.5" cornerRadiusY="13.5"><Stroke color="{hc}" thickness="3"/></RoundRectangle>
        </PartDraw>

        <!-- ================= MINUTE HAND ================= -->
        <PartDraw x="0" y="0" width="{W}" height="{H}" pivotX="0.5" pivotY="0.5" angle="0">
            <Transform target="angle" value="{MINUTE_ANGLE}"/>
            <Line startX="225" startY="244" endX="225" endY="42"><Stroke color="{CONFIG['minute_hand']}" thickness="8" cap="ROUND"/></Line>
        </PartDraw>

        <!-- ================= SECOND HAND (toggle in Customize) ================= -->
        <BooleanConfiguration id="show_seconds">
            <BooleanOption id="TRUE">
                <Group name="seconds" x="0" y="0" width="{W}" height="{H}">
                    <Variant mode="AMBIENT" target="alpha" value="0"/>
                    <PartDraw x="0" y="0" width="{W}" height="{H}" pivotX="0.5" pivotY="0.5" angle="0">
                        <Transform target="angle" value="{SECOND_ANGLE}"/>
                        <Line startX="225" startY="262" endX="225" endY="36"><Stroke color="{CONFIG['second_hand']}" thickness="3" cap="ROUND"/></Line>
                    </PartDraw>
                </Group>
            </BooleanOption>
        </BooleanConfiguration>

        <!-- ================= CENTER CAP ================= -->
        <PartDraw x="211" y="211" width="28" height="28">
            <Ellipse x="3" y="3" width="22" height="22"><Fill color="#000000"/><Stroke color="{CONFIG['minute_hand']}" thickness="5"/></Ellipse>
        </PartDraw>"""


def text_part(x, y, w, h, size, expr, color=None, align="CENTER"):
    color = color or CONFIG["complication_text"]
    return (f'<PartText x="{x}" y="{y}" width="{w}" height="{h}">'
            f'<Text align="{align}" ellipsis="TRUE"><Font family="SYNC_TO_DEVICE" size="{size}" weight="BOLD" color="{color}">'
            f'<Template>%s<Parameter expression="{expr}"/></Template></Font></Text></PartText>')


def image_part(x, y, w, h, src, tint=None):
    t = f' tintColor="{tint}"' if tint else ""
    return f'<PartImage x="{x}" y="{y}" width="{w}" height="{h}"{t}><Image resource="{src}"/></PartImage>'


def bg_part(d, ring=None):
    ring_xml = f'<Stroke color="{ring}" thickness="4"/>' if ring else ""
    inset = 2 if ring else 0
    return (f'<PartDraw x="0" y="0" width="{d}" height="{d}">'
            f'<Ellipse x="{inset}" y="{inset}" width="{d - 2 * inset}" height="{d - 2 * inset}">'
            f'<Fill color="{CONFIG["complication_bg"]}"/>{ring_xml}</Ellipse></PartDraw>')


def complications_xml():
    tint = CONFIG["complication_ring"]
    mono = "[COMPLICATION.MONOCHROMATIC_IMAGE]"
    # Big slot (weather in the stock layout) ---------------------------------
    d = 96
    rv = ("([COMPLICATION.RANGED_VALUE_VALUE] - [COMPLICATION.RANGED_VALUE_MIN]) / "
          "max(1, [COMPLICATION.RANGED_VALUE_MAX] - [COMPLICATION.RANGED_VALUE_MIN]) * 360")
    big = f"""
        <!-- ===== Slot 1: big circle (pick Weather here on the watch) ===== -->
        <ComplicationSlot slotId="1" displayName="Center" x="177" y="236" width="{d}" height="{d}"
            supportedTypes="SHORT_TEXT RANGED_VALUE MONOCHROMATIC_IMAGE SMALL_IMAGE EMPTY" isCustomizable="TRUE">
            <Variant mode="AMBIENT" target="alpha" value="150"/>
            <BoundingOval x="0" y="0" width="{d}" height="{d}"/>
            <DefaultProviderPolicy defaultSystemProvider="DAY_AND_DATE" defaultSystemProviderType="SHORT_TEXT"/>
            <Complication type="SHORT_TEXT">
                {bg_part(d)}
                {image_part(34, 14, 28, 28, mono, tint)}
                {text_part(4, 44, 88, 36, 28, "[COMPLICATION.TEXT]")}
            </Complication>
            <Complication type="RANGED_VALUE">
                {bg_part(d)}
                <PartDraw x="0" y="0" width="{d}" height="{d}">
                    <Arc centerX="48" centerY="48" width="88" height="88" startAngle="0" endAngle="360"><Stroke color="#3A3846" thickness="5"/></Arc>
                    <Arc centerX="48" centerY="48" width="88" height="88" startAngle="0" endAngle="0">
                        <Transform target="endAngle" value="{rv}"/>
                        <Stroke color="{tint}" thickness="5" cap="ROUND"/>
                    </Arc>
                </PartDraw>
                {image_part(36, 16, 24, 24, mono, tint)}
                {text_part(4, 42, 88, 34, 24, "[COMPLICATION.TEXT]")}
            </Complication>
            <Complication type="MONOCHROMATIC_IMAGE">
                {bg_part(d)}
                {image_part(26, 26, 44, 44, mono, tint)}
            </Complication>
            <Complication type="SMALL_IMAGE">
                {bg_part(d)}
                {image_part(18, 18, 60, 60, "[COMPLICATION.SMALL_IMAGE]")}
            </Complication>
            <Complication type="EMPTY"/>
        </ComplicationSlot>"""
    # Small shortcut slot (camera in the stock layout) -----------------------
    s = 60
    small = f"""
        <!-- ===== Slot 2: small shortcut bubble (pick an App shortcut) ===== -->
        <ComplicationSlot slotId="2" displayName="Bottom" x="195" y="338" width="{s}" height="{s}"
            supportedTypes="SMALL_IMAGE MONOCHROMATIC_IMAGE SHORT_TEXT EMPTY" isCustomizable="TRUE">
            <Variant mode="AMBIENT" target="alpha" value="0"/>
            <BoundingOval x="0" y="0" width="{s}" height="{s}"/>
            <DefaultProviderPolicy defaultSystemProvider="WATCH_BATTERY" defaultSystemProviderType="SHORT_TEXT"/>
            <Complication type="SMALL_IMAGE">
                {bg_part(s, tint)}
                {image_part(10, 10, 40, 40, "[COMPLICATION.SMALL_IMAGE]")}
            </Complication>
            <Complication type="MONOCHROMATIC_IMAGE">
                {bg_part(s, tint)}
                {image_part(16, 16, 28, 28, mono, tint)}
            </Complication>
            <Complication type="SHORT_TEXT">
                {bg_part(s, tint)}
                {text_part(4, 17, 52, 26, 18, "[COMPLICATION.TEXT]")}
            </Complication>
            <Complication type="EMPTY"/>
        </ComplicationSlot>"""
    return big + "\n" + small


def build_xml():
    h, m, sec = CONFIG["preview_time"]
    color_opts = "\n".join(
        f'            <ColorOption id="{cid}" displayName="{name}" colors="#FF{hexv[1:]}"/>'
        for cid, name, hexv in CONFIG["hour_hand_colors"])
    default_color = CONFIG["hour_hand_colors"][0][0]
    color_markers = markers_group("color", CONFIG["palette"])
    mono_markers = markers_group("mono", [CONFIG["mono_marker"]] * 12)
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- GENERATED by shapes/tools/generate_face.py - edit CONFIG there, not this file. -->
<WatchFace width="{W}" height="{H}" clipShape="CIRCLE">
    <Metadata key="CLOCK_TYPE" value="ANALOG"/>
    <Metadata key="PREVIEW_TIME" value="{h:02d}:{m:02d}:{sec:02d}"/>

    <UserConfigurations>
        <ColorConfiguration id="hand_color" displayName="Hour hand" defaultValue="{default_color}">
{color_opts}
        </ColorConfiguration>
        <ListConfiguration id="marker_style" displayName="Markers" defaultValue="color">
            <ListOption id="color" displayName="Colour"/>
            <ListOption id="mono" displayName="Mono"/>
        </ListConfiguration>
        <BooleanConfiguration id="show_seconds" displayName="Second hand" defaultValue="TRUE"/>
    </UserConfigurations>

    <Scene backgroundColor="{CONFIG['background']}">
        <!-- ================= HOUR MARKERS =================
             Every hour has its own shape. The current hour's marker
             pops (bigger, full brightness) and slowly spins. -->
        <ListConfiguration id="marker_style">
            <ListOption id="color">
{color_markers}
            </ListOption>
            <ListOption id="mono">
{mono_markers}
            </ListOption>
        </ListConfiguration>
{complications_xml()}
{hand_xml()}
    </Scene>
</WatchFace>
"""


# --------------------------------------------------------------------------
# PNG preview (same geometry as the XML)
# --------------------------------------------------------------------------
def render(ambient=False, size=450):
    from PIL import Image, ImageDraw, ImageFont

    SS = 4  # supersample
    S = W * SS
    h, m, sec = CONFIG["preview_time"]

    def rgba(hexv, a=255):
        hexv = hexv.lstrip("#")
        return tuple(int(hexv[i:i + 2], 16) for i in (0, 2, 4)) + (a,)

    def font(px):
        for p in ("/usr/share/fonts/opentype/inter/Inter-Bold.otf",
                  "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
                  "C:\\Windows\\Fonts\\segoeuib.ttf", "C:\\Windows\\Fonts\\arialbd.ttf"):
            if os.path.exists(p):
                return ImageFont.truetype(p, px)
        return ImageFont.load_default()

    img = Image.new("RGBA", (S, S), rgba(CONFIG["background"]))

    def draw_prims(d, prims, color, off=(0, 0)):
        ox, oy = off
        for p in prims:
            k = p[0]
            if k in ("ell", "ellS"):
                x, y, w_, h_ = [v * SS for v in p[1:5]]
                box = [ox + x, oy + y, ox + x + w_, oy + y + h_]
                if k == "ell":
                    d.ellipse(box, fill=color)
                else:
                    t = p[5] * SS
                    d.ellipse([box[0] - t / 2, box[1] - t / 2, box[2] + t / 2, box[3] + t / 2], outline=color, width=int(t))
            elif k in ("rr", "rrS"):
                x, y, w_, h_, r = [v * SS for v in p[1:6]]
                box = [ox + x, oy + y, ox + x + w_, oy + y + h_]
                if k == "rr":
                    d.rounded_rectangle(box, radius=r, fill=color)
                else:
                    t = p[6] * SS
                    d.rounded_rectangle([box[0] - t / 2, box[1] - t / 2, box[2] + t / 2, box[3] + t / 2],
                                        radius=r + t / 2, outline=color, width=int(t))
            elif k == "arc":
                cx, cy, dia, s, e, t = p[1:]
                R = (dia / 2 + t / 2) * SS
                # WFF: 0deg = 12 o'clock, clockwise. PIL: 0deg = 3 o'clock.
                d.pieslice([ox + cx * SS - R, oy + cy * SS - R, ox + cx * SS + R, oy + cy * SS + R],
                           s - 90, e - 90, fill=color)

    def paste_rotated(layer, angle, center):
        rot = layer.rotate(-angle, resample=Image.BICUBIC, center=center)
        img.alpha_composite(rot)

    # markers
    b = CONFIG["marker_box"]
    for i, (rot, prims) in enumerate(MARKERS):
        now = i == h % 12
        alpha = CONFIG["ambient_marker_alpha"] if ambient else (255 if now else CONFIG["dim_alpha"])
        scale = CONFIG["current_scale"] if now else 1
        angle = i * 30 + rot + (sec * 6 if (now and CONFIG["current_spins"]) else 0)
        tile = Image.new("RGBA", (b * SS, b * SS), (0, 0, 0, 0))
        draw_prims(ImageDraw.Draw(tile), prims, rgba(CONFIG["palette"][i]))
        tile = tile.resize((int(b * SS * scale),) * 2, Image.BICUBIC).rotate(-angle, resample=Image.BICUBIC)
        if alpha < 255:
            a = tile.getchannel("A").point(lambda v: v * alpha // 255)
            tile.putalpha(a)
        mx, my = marker_pos(i)
        img.alpha_composite(tile, (int(mx * SS - tile.width / 2), int(my * SS - tile.height / 2)))

    comp = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(comp)
    tint = rgba(CONFIG["complication_ring"])
    # slot 1 sample: weather "3°"
    if True:
        a = 150 if ambient else 255
        x0, y0, dd = 177 * SS, 236 * SS, 96 * SS
        d.ellipse([x0, y0, x0 + dd, y0 + dd], fill=rgba(CONFIG["complication_bg"], a))
        # little crescent moon icon
        mx0, my0 = x0 + 36 * SS, y0 + 16 * SS
        d.ellipse([mx0, my0, mx0 + 24 * SS, my0 + 24 * SS], fill=tint[:3] + (a,))
        d.ellipse([mx0 + 8 * SS, my0 - 3 * SS, mx0 + 30 * SS, my0 + 19 * SS], fill=rgba(CONFIG["complication_bg"], a))
        f = font(28 * SS)
        d.text((x0 + dd / 2, y0 + 62 * SS), "3°", font=f, fill=(255, 255, 255, a), anchor="mm")
    if not ambient:
        x0, y0, s = 195 * SS, 338 * SS, 60 * SS
        d.ellipse([x0 + 2 * SS, y0 + 2 * SS, x0 + s - 2 * SS, y0 + s - 2 * SS],
                  fill=rgba(CONFIG["complication_bg"]), outline=tint, width=4 * SS)
        # camera glyph
        d.rounded_rectangle([x0 + 18 * SS, y0 + 22 * SS, x0 + 42 * SS, y0 + 40 * SS], radius=3 * SS, outline=tint, width=3 * SS)
        d.ellipse([x0 + 25 * SS, y0 + 26 * SS, x0 + 35 * SS, y0 + 36 * SS], outline=tint, width=3 * SS)

    img.alpha_composite(comp)
    hour_c = rgba(CONFIG["hour_hand_colors"][0][2])
    c = (CX * SS, CY * SS)
    # hour hand
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    if ambient:
        ld.rounded_rectangle([211.5 * SS, 109.5 * SS, 238.5 * SS, 238.5 * SS], radius=13.5 * SS, outline=hour_c, width=3 * SS)
    else:
        ld.rounded_rectangle([210 * SS, 108 * SS, 240 * SS, 240 * SS], radius=15 * SS, fill=hour_c)
    paste_rotated(layer, (h % 12 + m / 60) * 30, c)
    # minute hand
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    ld.rounded_rectangle([221 * SS, 38 * SS, 229 * SS, 248 * SS], radius=4 * SS, fill=rgba(CONFIG["minute_hand"]))
    paste_rotated(layer, (m + sec / 60) * 6, c)
    # second hand
    if not ambient:
        layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        ld = ImageDraw.Draw(layer)
        ld.rounded_rectangle([223.5 * SS, 34.5 * SS, 226.5 * SS, 263.5 * SS], radius=1.5 * SS, fill=rgba(CONFIG["second_hand"]))
        paste_rotated(layer, sec * 6, c)
    # cap
    d = ImageDraw.Draw(img)
    d.ellipse([214 * SS, 214 * SS, 236 * SS, 236 * SS], fill=(0, 0, 0, 255), outline=rgba(CONFIG["minute_hand"]), width=5 * SS)

    # circular clip
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, S, S], fill=255)
    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out.resize((size, size), Image.LANCZOS)


def main():
    xml_path = os.path.join(MODULE, "src", "main", "res", "raw", "watchface.xml")
    with open(xml_path, "w", encoding="utf-8") as f:
        f.write(build_xml())
    print("wrote", xml_path)
    try:
        prev_dir = os.path.join(MODULE, "preview")
        os.makedirs(prev_dir, exist_ok=True)
        render(False).save(os.path.join(prev_dir, "active.png"))
        render(True).save(os.path.join(prev_dir, "ambient.png"))
        render(False, 300).save(os.path.join(MODULE, "src", "main", "res", "drawable", "preview.png"))
        print("wrote previews in", prev_dir)
    except ImportError:
        print("Pillow not installed - skipped PNG previews (pip install pillow)", file=sys.stderr)


if __name__ == "__main__":
    main()
