#!/usr/bin/env python3
"""Comprehensive TV performance test suite measuring RAM, CPU, GPU, FPS, Loading, Disk, Responsiveness, and Stability."""
import argparse
import json
import os
import re
import subprocess
import time

ADB = r"D:\emmanuel\clean tv glass\platform-tools\adb.exe"
HOST = "192.168.31.51:32891"
PKG = "com.gathin.tvhome"
SETTINGS = "com.android.tv.settings/.MainSettings"


def adb_cmd(args):
    full_cmd = [ADB, "connect", HOST]
    subprocess.run(full_cmd, capture_output=True, text=True)
    cmd = [ADB, "-s", HOST] + args
    res = subprocess.run(cmd, capture_output=True, text=True)
    return res.stdout.strip()


def measure_cold_starts(n=3):
    print(f"Measuring {n} cold starts...")
    times = []
    for i in range(n):
        adb_cmd(["shell", "am", "start", "-W", "-n", SETTINGS])
        time.sleep(2)
        adb_cmd(["shell", "am", "force-stop", PKG])
        time.sleep(2)
        out = adb_cmd(["shell", "am", "start", "-W", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME"])
        wait_m = re.search(r"WaitTime:\s*(\d+)", out)
        total_m = re.search(r"TotalTime:\s*(\d+)", out)
        wait_ms = int(wait_m.group(1)) if wait_m else 0
        total_ms = int(total_m.group(1)) if total_m else 0
        times.append({"run": i + 1, "wait_ms": wait_ms, "total_ms": total_ms})
        time.sleep(1)
    avg_wait = sum(t["wait_ms"] for t in times) / len(times)
    avg_total = sum(t["total_ms"] for t in times) / len(times)
    return {"runs": times, "avg_wait_ms": round(avg_wait, 1), "avg_total_ms": round(avg_total, 1)}


def parse_meminfo():
    out = adb_cmd(["shell", "dumpsys", "meminfo", PKG])
    res = {}
    total_pss_m = re.search(r"TOTAL PSS:\s*(\d+)", out)
    total_rss_m = re.search(r"TOTAL RSS:\s*(\d+)", out)
    java_heap_m = re.search(r"Java Heap:\s*(\d+)", out)
    native_heap_m = re.search(r"Native Heap:\s*(\d+)", out)
    code_m = re.search(r"Code:\s*(\d+)", out)
    stack_m = re.search(r"Stack:\s*(\d+)", out)
    graphics_m = re.search(r"Graphics:\s*(\d+)", out)
    priv_dirty_m = re.search(r"TOTAL\s+(\d+)\s+(\d+)", out)

    res["total_pss_kb"] = int(total_pss_m.group(1)) if total_pss_m else 0
    res["total_pss_mb"] = round(res["total_pss_kb"] / 1024, 2)
    res["total_rss_kb"] = int(total_rss_m.group(1)) if total_rss_m else 0
    res["total_rss_mb"] = round(res["total_rss_kb"] / 1024, 2)
    res["java_heap_kb"] = int(java_heap_m.group(1)) if java_heap_m else 0
    res["java_heap_mb"] = round(res["java_heap_kb"] / 1024, 2)
    res["native_heap_kb"] = int(native_heap_m.group(1)) if native_heap_m else 0
    res["native_heap_mb"] = round(res["native_heap_kb"] / 1024, 2)
    res["graphics_kb"] = int(graphics_m.group(1)) if graphics_m else 0
    res["graphics_mb"] = round(res["graphics_kb"] / 1024, 2)
    res["code_kb"] = int(code_m.group(1)) if code_m else 0
    res["code_mb"] = round(res["code_kb"] / 1024, 2)
    res["stack_kb"] = int(stack_m.group(1)) if stack_m else 0
    res["stack_mb"] = round(res["stack_kb"] / 1024, 2)
    return res


def measure_cpu():
    out = adb_cmd(["shell", "dumpsys", "cpuinfo"])
    launcher_cpu = 0.0
    total_cpu = 0.0
    for line in out.splitlines():
        if PKG in line:
            m = re.search(r"([0-9.]+)%", line)
            if m:
                launcher_cpu = float(m.group(1))
        if "TOTAL:" in line:
            m = re.search(r"([0-9.]+)%\s*TOTAL", line)
            if m:
                total_cpu = float(m.group(1))
    return {"launcher_cpu_pct": launcher_cpu, "total_cpu_pct": total_cpu}


def measure_framestats():
    adb_cmd(["shell", "dumpsys", "gfxinfo", PKG, "reset"])
    keys = [22, 22, 22, 22, 21, 21, 21, 21, 20, 22, 22, 21, 21, 19, 22, 21] * 3
    print(f"Injecting {len(keys)} UI navigation keys...")
    peak_mem_kb = 0
    for i, k in enumerate(keys):
        adb_cmd(["shell", "input", "keyevent", str(k)])
        time.sleep(0.08)
        if i % 12 == 0:
            mem = parse_meminfo()
            if mem["total_pss_kb"] > peak_mem_kb:
                peak_mem_kb = mem["total_pss_kb"]

    time.sleep(1)
    out = adb_cmd(["shell", "dumpsys", "gfxinfo", PKG, "framestats"])
    total_frames = 0
    janky_frames = 0
    jank_pct = 0.0
    p90 = 0
    p95 = 0
    p99 = 0

    m_tot = re.search(r"Total frames rendered:\s*(\d+)", out)
    if m_tot:
        total_frames = int(m_tot.group(1))
    m_jank = re.search(r"Janky frames:\s*(\d+)\s*\(([\d.]+)%\)", out)
    if m_jank:
        janky_frames = int(m_jank.group(1))
        jank_pct = float(m_jank.group(2))
    m_p90 = re.search(r"90th percentile:\s*(\d+)ms", out)
    if m_p90:
        p90 = int(m_p90.group(1))
    m_p95 = re.search(r"95th percentile:\s*(\d+)ms", out)
    if m_p95:
        p95 = int(m_p95.group(1))
    m_p99 = re.search(r"99th percentile:\s*(\d+)ms", out)
    if m_p99:
        p99 = int(m_p99.group(1))

    # Extract GPU histogram
    gpu_times = []
    gpu_m = re.search(r"GPU HISTOGRAM:\s*(.*)", out)
    if gpu_m:
        pairs = gpu_m.group(1).split()
        for p in pairs:
            km = re.match(r"(\d+)ms=(\d+)", p)
            if km:
                ms = int(km.group(1))
                cnt = int(km.group(2))
                gpu_times.extend([ms] * cnt)

    avg_gpu_ms = round(sum(gpu_times) / len(gpu_times), 2) if gpu_times else 0.0

    return {
        "total_frames": total_frames,
        "janky_frames": janky_frames,
        "jank_pct": jank_pct,
        "p90_ms": p90,
        "p95_ms": p95,
        "p99_ms": p99,
        "avg_gpu_render_ms": avg_gpu_ms,
        "peak_pss_kb": peak_mem_kb,
        "peak_pss_mb": round(peak_mem_kb / 1024, 2),
    }


def measure_disk():
    wp_stat = adb_cmd(["shell", "ls", "-l", "/sdcard/Android/data/com.gathin.tvhome/files/wallpaper.jpg"])
    du_out = adb_cmd(["shell", "du", "-k", "/sdcard/Android/data/com.gathin.tvhome/files"])
    wp_size_bytes = 0
    m = re.search(r"\s+(\d+)\s+\d{4}-\d{2}-\d{2}", wp_stat)
    if m:
        wp_size_bytes = int(m.group(1))
    du_kb = 0
    dm = re.search(r"(\d+)\s+", du_out)
    if dm:
        du_kb = int(dm.group(1))

    return {
        "wallpaper_bytes": wp_size_bytes,
        "wallpaper_kb": round(wp_size_bytes / 1024, 2),
        "total_files_dir_kb": du_kb,
    }


def measure_stability(cycles=60):
    print(f"Running stability stress test ({cycles} rapid interactions)...")
    keys = [22, 21, 20, 19] * (cycles // 4)
    for k in keys:
        adb_cmd(["shell", "input", "keyevent", str(k)])
        time.sleep(0.04)
    time.sleep(2)
    mem_after = parse_meminfo()
    logcat_errors = adb_cmd(["shell", "logcat", "-d", "-s", "AndroidRuntime:E", "ActivityManager:E"])
    crashes = [l for l in logcat_errors.splitlines() if "FATAL EXCEPTION" in l or PKG in l]
    return {
        "post_stress_pss_mb": mem_after["total_pss_mb"],
        "crashes_detected": len(crashes),
        "crash_lines": crashes[:5],
    }


def run_full_suite(label):
    print(f"=== STARTING TEST SUITE: {label} ===")
    cold = measure_cold_starts(3)

    print("Measuring idle state (waiting 4 seconds)...")
    time.sleep(4)
    idle_mem = parse_meminfo()
    idle_cpu = measure_cpu()

    print("Measuring rendering, jank, FPS and active UI responsiveness...")
    framestats = measure_framestats()
    active_cpu = measure_cpu()

    print("Measuring disk storage...")
    disk = measure_disk()

    print("Measuring stability and memory leak resistance...")
    stability = measure_stability(60)

    result = {
        "label": label,
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "cold_start": cold,
        "idle_memory": idle_mem,
        "idle_cpu": idle_cpu,
        "active_cpu": active_cpu,
        "rendering": framestats,
        "disk": disk,
        "stability": stability,
    }
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--label", default="baseline", help="Test label (e.g. before, after)")
    parser.add_argument("--out", default="bench_suite_result.json", help="Output file")
    args = parser.parse_args()

    data = run_full_suite(args.label)
    with open(args.out, "w") as f:
        json.dump(data, f, indent=2)
    print(f"=== COMPLETED {args.label} ===")
    print(json.dumps(data, indent=2))
