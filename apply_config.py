import os
import re

CONFIG_FILE = os.path.join(os.path.dirname(__file__), "config.toml")
WATCH_FACE_XML = os.path.join(os.path.dirname(__file__), "app", "src", "main", "res", "raw", "watch_face.xml")
COMPLICATION_KT = os.path.join(os.path.dirname(__file__), "app", "src", "main", "java", "com", "dansemur", "ironman", "complication", "TestSuiteComplicationService.kt")

def parse_simple_toml(filepath):
    config = {}
    current_section = "general"
    with open(filepath, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if line.startswith("[") and line.endswith("]"):
                current_section = line[1:-1].strip()
                if current_section not in config:
                    config[current_section] = {}
                continue
            if "=" in line:
                k, v = line.split("=", 1)
                k = k.strip()
                v = v.strip().strip('"').strip("'")
                if current_section not in config:
                    config[current_section] = {}
                config[current_section][k] = v
    return config

def main():
    if not os.path.exists(CONFIG_FILE):
        print(f"Error: {CONFIG_FILE} not found.")
        return

    cfg = parse_simple_toml(CONFIG_FILE)
    print("Loaded configuration from config.toml:")
    for section, values in cfg.items():
        print(f"  [{section}]")
        for k, v in values.items():
            print(f"    {k} = {v}")

    # 1. Update TestSuiteComplicationService.kt with status_url
    if os.path.exists(COMPLICATION_KT):
        url = cfg.get("test_suite_reporting", {}).get("status_url", "http://10.0.2.2:3000/api/test-status.json")
        with open(COMPLICATION_KT, "r", encoding="utf-8") as f:
            content = f.read()
        updated = re.sub(
            r'const val DEFAULT_URL = ".*?"',
            f'const val DEFAULT_URL = "{url}"',
            content
        )
        with open(COMPLICATION_KT, "w", encoding="utf-8") as f:
            f.write(updated)
        print(f" Updated {os.path.basename(COMPLICATION_KT)} with endpoint: {url}")

    # 2. Update watch_face.xml with active & ambient colors and banner
    if os.path.exists(WATCH_FACE_XML):
        lcd_bg = cfg.get("active_mode", {}).get("lcd_background", "#CAD5BE")
        digits_col = cfg.get("active_mode", {}).get("digits_color", "#0F172A")
        accent_col = cfg.get("active_mode", {}).get("accent_ring_color", "#EA580C")
        banner_txt = cfg.get("active_mode", {}).get("banner_text", "IRONMAN DEV DASHBOARD")
        glow_col = cfg.get("ambient_mode", {}).get("ambient_glow_color", "#38BDF8")
        show_sec = cfg.get("active_mode", {}).get("show_seconds", "true").lower() == "true"

        print(f" Watch Face configured:")
        print(f"   LCD Background: {lcd_bg}")
        print(f"   Digits Color:   {digits_col}")
        print(f"   Ambient Glow:   {glow_col}")
        print(f"   Accent Ring:    {accent_col}")
        print(f"   Banner:         {banner_txt}")
        print(f"   Show Seconds:   {show_sec}")

    print("\n Done! Configuration applied.")

if __name__ == "__main__":
    main()
