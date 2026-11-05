import os
import sys
import subprocess
import time

ADB = r"D:\emmanuel\new tv app\platform-tools\adb.exe"
HOST = "192.168.31.51:38641"
BACKUP_DIR = r"D:\emmanuel\new tv app\backups\restored_from_home3"

def run_adb(args, stdin_data=None):
    cmd = [ADB, "-s", HOST] + args
    res = subprocess.run(cmd, input=stdin_data, capture_output=True, text=(stdin_data is None or isinstance(stdin_data, str)))
    return res

def ensure_connected():
    print(f"Connecting to {HOST}...")
    subprocess.run([ADB, "connect", HOST], capture_output=True)
    time.sleep(1)

def main():
    ensure_connected()
    os.makedirs(BACKUP_DIR, exist_ok=True)
    
    files_to_restore = {
        "shared_prefs/accessibility_helpers.xml": "accessibility_helpers.xml",
        "shared_prefs/launcher_settings.xml": "launcher_settings.xml",
        "shared_prefs/recent_apps_store.xml": "recent_apps_store.xml",
        "shared_prefs/last_opened.xml": "last_opened.xml",
        "files/config.json": "config.json",
        "files/order.txt": "order.txt"
    }

    contents = {}
    print("Reading data from com.gathin.tvhome3...")
    for remote_rel, local_name in files_to_restore.items():
        res = run_adb(["shell", f"run-as com.gathin.tvhome3 cat /data/data/com.gathin.tvhome3/{remote_rel}"])
        if res.returncode == 0 and res.stdout.strip():
            contents[remote_rel] = res.stdout
            local_path = os.path.join(BACKUP_DIR, local_name)
            with open(local_path, "w", encoding="utf-8") as f:
                f.write(res.stdout)
            print(f"  Extracted {remote_rel} ({len(res.stdout)} bytes)")
        else:
            print(f"  Warning: could not read {remote_rel}: {res.stderr}")

    print("\nInstalling debug build of com.gathin.tvhome...")
    debug_apk = r"D:\emmanuel\new tv app\launcher\app\build\outputs\apk\debug\app-debug.apk"
    res = subprocess.run([ADB, "-s", HOST, "install", "-r", debug_apk], capture_output=True, text=True)
    print(res.stdout.strip())
    if "Success" not in res.stdout:
        print("Failed to install debug build:", res.stderr)
        return

    print("\nWriting restored data into com.gathin.tvhome...")
    run_adb(["shell", "run-as com.gathin.tvhome mkdir -p /data/data/com.gathin.tvhome/shared_prefs /data/data/com.gathin.tvhome/files"])
    
    for remote_rel, data in contents.items():
        # Pipe data into cat inside run-as
        cmd = [ADB, "-s", HOST, "shell", f"run-as com.gathin.tvhome sh -c 'cat > /data/data/com.gathin.tvhome/{remote_rel}'"]
        p = subprocess.run(cmd, input=data, capture_output=True, text=True)
        print(f"  Wrote {remote_rel}: exit {p.returncode}")

    # Verify files
    print("\nVerifying written files in com.gathin.tvhome:")
    res = run_adb(["shell", "run-as com.gathin.tvhome ls -la /data/data/com.gathin.tvhome/shared_prefs; run-as com.gathin.tvhome ls -la /data/data/com.gathin.tvhome/files"])
    print(res.stdout)

    print("\nReinstalling release build of com.gathin.tvhome...")
    release_apk = r"D:\emmanuel\new tv app\launcher\app\build\outputs\apk\release\app-release.apk"
    res = subprocess.run([ADB, "-s", HOST, "install", "-r", release_apk], capture_output=True, text=True)
    print(res.stdout.strip())

    print("\nSpeed-compiling package...")
    run_adb(["shell", "cmd package compile -m speed -f com.gathin.tvhome"])

    print("\nGranting permissions & configuring accessibility services...")
    cmds = [
        "pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION",
        "pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS",
        "appops set com.gathin.tvhome GET_USAGE_STATS allow",
        "cmd appops set com.gathin.tvhome AUTO_START allow",
        "cmd appops set com.gathin.tvhome APP_AUTO_START allow",
        "appops write-settings",
        'settings put secure enabled_accessibility_services "com.gathin.tvhome/.HomeKeyService:com.gathin.tvhome/.InputKeyService"',
        "settings put secure accessibility_enabled 1"
    ]
    for c in cmds:
        r = run_adb(["shell", c])
        print(f"  {c} -> {r.returncode}")

    print("\nStarting CleanYourTV Home activity...")
    res = run_adb(["shell", "am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n com.gathin.tvhome/.MainActivity"])
    print(res.stdout)

    print("\nChecking dumpsys accessibility:")
    res = run_adb(["shell", "dumpsys accessibility | grep -A 10 'User state'"])
    print(res.stdout)

if __name__ == "__main__":
    main()
