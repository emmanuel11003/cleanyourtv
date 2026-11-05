import os
import sys
import subprocess
import time

ADB = r"D:\emmanuel\new tv app\platform-tools\adb.exe"
HOST = "192.168.31.51:38641"
PACKAGE = "com.gathin.tvhome"

def run_adb(args):
    cmd = [ADB, "-s", HOST] + args
    return subprocess.run(cmd, capture_output=True, text=True)

def ensure_connected():
    subprocess.run([ADB, "connect", HOST], capture_output=True)
    time.sleep(1)

def push_theme(theme_dir):
    ensure_connected()
    config_file = os.path.join(theme_dir, "config.json")
    wallpaper_file = os.path.join(theme_dir, "wallpaper.jpg")
    
    if not os.path.exists(config_file):
        raise FileNotFoundError(f"config.json not found in {theme_dir}")
        
    print(f"Applying theme from {theme_dir}...")
    
    # Push config.json to tmp
    tmp_config = "/data/local/tmp/cyt-theme-config.json"
    subprocess.run([ADB, "-s", HOST, "push", config_file, tmp_config], check=True)
    
    # Write via ThemeProvider & direct file path
    ext_dir = f"/sdcard/Android/data/{PACKAGE}/files"
    run_adb(["shell", f"mkdir -p {ext_dir}"])
    run_adb(["shell", f"content write --uri content://{PACKAGE}.theme/config.json < {tmp_config} || cp {tmp_config} {ext_dir}/config.json"])
    run_adb(["shell", f"rm -f {tmp_config}"])
    
    # Push wallpaper if present
    tmp_wall = "/data/local/tmp/cyt-theme-wallpaper.jpg"
    if os.path.exists(wallpaper_file):
        print("  Pushing wallpaper.jpg...")
        subprocess.run([ADB, "-s", HOST, "push", wallpaper_file, tmp_wall], check=True)
        run_adb(["shell", f"content write --uri content://{PACKAGE}.theme/wallpaper.jpg < {tmp_wall} || cp {tmp_wall} {ext_dir}/wallpaper.jpg"])
        run_adb(["shell", f"rm -f {tmp_wall}"])
    else:
        print("  Removing wallpaper.jpg (theme has none)...")
        run_adb(["shell", f"content delete --uri content://{PACKAGE}.theme/wallpaper.jpg; rm -f {ext_dir}/wallpaper.jpg"])
        
    # Restart Home activity to reload theme
    print("  Restarting launcher activity...")
    run_adb(["shell", "am force-stop " + PACKAGE])
    time.sleep(0.5)
    run_adb(["shell", f"am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n {PACKAGE}/.MainActivity"])
    time.sleep(1)
    print("Theme applied successfully.")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python apply_theme.py <theme_directory>")
        sys.exit(1)
    push_theme(sys.argv[1])
