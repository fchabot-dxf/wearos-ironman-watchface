import os
import xml.etree.ElementTree as ET
from PIL import Image, ImageDraw, ImageFont

PROJECT_DIR = os.path.dirname(__file__)
PARAMS_FILE = os.path.join(PROJECT_DIR, "app", "src", "main", "res", "values", "params.xml")
OUT_DIR = os.path.join(PROJECT_DIR, "preview")

def load_params():
    params = {}
    tree = ET.parse(PARAMS_FILE)
    root = tree.getroot()
    for color in root.findall("color"):
        name = color.get("name")
        params[f"@color/{name}"] = color.text.strip()
    for string in root.findall("string"):
        name = string.get("name")
        params[f"@string/{name}"] = string.text.strip()
    return params

def hex_to_rgb(hex_str):
    hex_str = hex_str.lstrip('#')
    if len(hex_str) == 8:
        hex_str = hex_str[2:]
    return tuple(int(hex_str[i:i+2], 16) for i in (0, 2, 4))

def get_font(size):
    candidates = [
        "C:\\Windows\\Fonts\\consola.ttf",
        "C:\\Windows\\Fonts\\cour.ttf",
        "C:\\Windows\\Fonts\\lucon.ttf",
        "C:\\Windows\\Fonts\\arial.ttf"
    ]
    for c in candidates:
        if os.path.exists(c):
            try:
                return ImageFont.truetype(c, size)
            except Exception:
                pass
    return ImageFont.load_default()

def render_preview(ambient=False):
    params = load_params()
    size = (450, 450)
    
    font_large_time = get_font(74)
    font_sec = get_font(36)
    font_date = get_font(20)
    font_weather = get_font(18)
    font_small = get_font(13)
    font_banner = get_font(14)

    bg_color = hex_to_rgb(params["@color/param_ambient_bg"]) if ambient else (10, 12, 16)
    img = Image.new("RGBA", size, bg_color)
    draw = ImageDraw.Draw(img)

    glow_color = hex_to_rgb(params["@color/param_ambient_glow"])
    active_digits = hex_to_rgb(params["@color/param_active_digits"])
    accent_color = hex_to_rgb(params["@color/param_active_accent_ring"])
    lcd_bg = hex_to_rgb(params["@color/param_active_lcd_bg"])

    digits_color = glow_color if ambient else active_digits

    if not ambient:
        # Outer matte bezel
        draw.ellipse([0, 0, 450, 450], outline=hex_to_rgb(params["@color/param_bezel_casing"]), width=18)
        
        # Circular Accent ring
        draw.ellipse([18, 18, 432, 432], outline=accent_color, width=4)

        # Full Circular LCD Dial
        draw.ellipse([22, 22, 428, 428], fill=lcd_bg, outline=hex_to_rgb(params["@color/param_bezel_casing"]), width=2)

        # Top Banner inside circular dial
        draw.text((225, 78), params["@string/param_banner_label"], fill=hex_to_rgb(params["@color/param_active_banner_text"]), font=font_banner, anchor="mm")

        # Circular dial separator line
        draw.line([(85, 160), (365, 160)], fill=(132, 147, 119), width=2)

    # Time & Complications (both active and ambient)
    # Day & Date (Upper Left)
    draw.text((95, 126), "WE 10.14", fill=digits_color, font=font_date, anchor="la")

    # Weather Complication (Upper Right)
    draw.text((355, 126), "22°C ⛅", fill=digits_color, font=font_weather, anchor="ra")

    # Middle Progress Complication
    prog_color = glow_color if ambient else accent_color
    draw.rounded_rectangle([95, 182, 255, 200], radius=4, outline=digits_color, width=2)
    draw.rounded_rectangle([97, 184, 230, 198], radius=2, fill=prog_color) # 84% filled
    draw.text((268, 182), "84% PASS", fill=digits_color, font=font_small, anchor="la")

    # Main Digital Time (Properly centered horizontally inside circular dial)
    if not ambient:
        draw.text((195, 275), "10:09", fill=digits_color, font=font_large_time, anchor="mm")
        draw.text((305, 280), ".36", fill=digits_color, font=font_sec, anchor="mm")
    else:
        # Centered in ambient AOD mode
        draw.text((225, 275), "10:09", fill=digits_color, font=font_large_time, anchor="mm")

    return img

def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    active_img = render_preview(ambient=False)
    active_img.save(os.path.join(OUT_DIR, "preview_active.png"))

    ambient_img = render_preview(ambient=True)
    ambient_img.save(os.path.join(OUT_DIR, "preview_ambient.png"))

    comparison = Image.new("RGBA", (940, 490), (15, 17, 23))
    comparison.paste(active_img, (20, 20))
    comparison.paste(ambient_img, (470, 20))
    comp_draw = ImageDraw.Draw(comparison)
    comp_font = get_font(13)
    comp_draw.text((245, 470), "ACTIVE MODE (Wrist Up - Full Circle Vintage LCD)", fill=(203, 213, 225), font=comp_font, anchor="mm")
    comp_draw.text((695, 470), "IDLE / AMBIENT AOD (Wrist Down - Indiglo OLED)", fill=(56, 189, 248), font=comp_font, anchor="mm")
    
    comp_path = os.path.join(OUT_DIR, "preview_comparison.png")
    comparison.save(comp_path)
    print(f"Generated centered circular previews in {OUT_DIR}")

if __name__ == "__main__":
    main()
