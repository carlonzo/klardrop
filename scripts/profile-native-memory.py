#!/usr/bin/env python3
import argparse, datetime, hashlib, json, os, pathlib, signal, subprocess, sys, tempfile, time

def setup_isolated_env(base_dir="/tmp") -> dict:
    root = pathlib.Path(tempfile.mkdtemp(prefix="klardrop-profile-memory-", dir=base_dir))
    data, runtime, downloads, config = root / "data", root / "runtime", root / "downloads", root / "data" / "config"
    config.mkdir(parents=True, mode=0o700, exist_ok=True)
    runtime.mkdir(parents=True, mode=0o700, exist_ok=True)
    downloads.mkdir(parents=True, mode=0o700, exist_ok=True)

    user_dirs = config / "user-dirs.dirs"
    user_dirs.write_text(f'XDG_DOWNLOAD_DIR="{downloads}"\n', encoding="utf-8")
    os.chmod(user_dirs, 0o600)

    env = os.environ.copy()
    for var in ("DBUS_SESSION_BUS_ADDRESS", "DISPLAY", "WAYLAND_DISPLAY"): env.pop(var, None)
    env.update(KLARDROP_HOME=str(data), XDG_CONFIG_HOME=str(config),
               XDG_RUNTIME_DIR=str(runtime), XDG_DOWNLOAD_DIR=str(downloads))
    return {"root": root, "data": data, "runtime": runtime, "downloads": downloads, "env": env}

def cleanup_process(proc: subprocess.Popen, timeout: float = 10.0) -> int:
    if proc.poll() is None:
        try:
            proc.send_signal(signal.SIGTERM)
            proc.wait(timeout=timeout)
        except (subprocess.TimeoutExpired, ProcessLookupError):
            proc.kill()
            proc.wait(timeout=5.0)
    return proc.returncode

def get_schedule(seconds: int) -> list[int]:
    times = set(range(1, min(seconds, 10) + 1))
    if seconds > 10: times.update(range(15, min(seconds, 30) + 1, 5))
    if seconds > 30: times.update(range(30, min(seconds, 60) + 1, 15))
    if seconds > 60: times.update(range(60, seconds + 1, 30))
    times.add(seconds)
    return sorted(t for t in times if 1 <= t <= seconds)

def parse_proc_sample(status_text: str, smaps_text: str, stat_text: str, ticks: int) -> dict:
    status = {k.strip(): v.strip() for line in status_text.splitlines() if ":" in line for k, v in [line.split(":", 1)]}
    if "VmHWM" not in status or "Threads" not in status: raise ValueError("Missing VmHWM or Threads in status")
    hwm_parts = status["VmHWM"].split()
    if not hwm_parts or not hwm_parts[0].isdigit(): raise ValueError(f"Invalid VmHWM: {status['VmHWM']}")
    hwm_kib, threads = int(hwm_parts[0]), int(status["Threads"])
    if hwm_kib <= 0 or threads <= 0: raise ValueError(f"VmHWM ({hwm_kib}) and Threads ({threads}) must be positive")

    smaps = {k.strip(): int(v.strip().split()[0]) for line in smaps_text.splitlines()
             if ":" in line for k, v in [line.split(":", 1)] if v.strip().split() and v.strip().split()[0].isdigit()}
    for req in ("Rss", "Pss", "Anonymous", "Private_Dirty"):
        if req not in smaps: raise ValueError(f"Missing {req} in smaps_rollup")
    rss, pss, anon, dirty = smaps["Rss"], smaps["Pss"], smaps["Anonymous"], smaps["Private_Dirty"]
    if rss <= 0 or pss <= 0: raise ValueError(f"Rss ({rss}) and Pss ({pss}) must be positive")
    if anon < 0 or dirty < 0: raise ValueError(f"Anonymous ({anon}) and Private_Dirty ({dirty}) must be non-negative")

    stat_fields = stat_text.rpartition(") ")[2].split()
    if len(stat_fields) < 13: raise ValueError(f"Stat format lacks >= 13 fields: {len(stat_fields)}")
    utime, stime = int(stat_fields[11]), int(stat_fields[12])
    if utime < 0 or stime < 0: raise ValueError(f"CPU ticks must be non-negative: {utime}, {stime}")

    return {"rss_kib": rss, "pss_kib": pss, "anonymous_kib": anon, "private_dirty_kib": dirty,
            "threads": threads, "vm_hwm_kib": hwm_kib, "cpu_seconds": round((utime + stime) / ticks, 3)}

def run_profile(binary: pathlib.Path, label: str = "baseline", seconds: int = 120, quiet: bool = False) -> dict:
    binary = binary.resolve()
    if not binary.is_file() or not os.access(binary, os.X_OK): raise FileNotFoundError(f"Binary not found: {binary}")

    raw_bytes = binary.read_bytes()
    sha256 = hashlib.sha256(raw_bytes).hexdigest()
    iso = setup_isolated_env()
    root, env, flags, ticks = iso["root"], iso["env"], ["daemon", "--no-ble", "--debug"], os.sysconf("SC_CLK_TCK")

    meta = {
        "label": label, "binary": str(binary), "sha256": sha256, "bytes": len(raw_bytes), "flags": flags,
        "utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "environment": {"root": str(root), "klardrop_home": str(iso["data"]), "xdg_runtime_dir": str(iso["runtime"]),
                        "xdg_download_dir": str(iso["downloads"]), "stripped": ["DBUS_SESSION_BUS_ADDRESS", "DISPLAY", "WAYLAND_DISPLAY"]},
    }
    (root / "metadata.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    log = (root / "daemon.log").open("w", encoding="utf-8")
    proc = subprocess.Popen([str(binary), *flags], env=env, stdout=log, stderr=log)
    if not quiet:
        print(json.dumps({"status": "started", "pid": proc.pid, "artifact_dir": str(root), "sha256": sha256}), flush=True)

    start, samples, prev = time.monotonic(), [], None
    proc_dir = pathlib.Path("/proc") / str(proc.pid)

    try:
        for at in get_schedule(seconds):
            time.sleep(max(0.0, (start + at) - time.monotonic()))
            if proc.poll() is not None:
                raise RuntimeError(f"Daemon exited prematurely at {at}s with code {proc.returncode}")

            metrics = parse_proc_sample((proc_dir / "status").read_text(encoding="utf-8", errors="replace"),
                                        (proc_dir / "smaps_rollup").read_text(encoding="utf-8", errors="replace"),
                                        (proc_dir / "stat").read_text(encoding="utf-8", errors="replace"), ticks)
            row = {"elapsed_s": round(time.monotonic() - start, 2), **metrics}
            if prev:
                dt = row["elapsed_s"] - prev["elapsed_s"]
                dcpu = row["cpu_seconds"] - prev["cpu_seconds"]
                if dt > 0: row["interval_cpu_percent"] = round(100.0 * dcpu / dt, 3)
            prev = row
            samples.append(row)

            with (root / "samples.jsonl").open("a", encoding="utf-8") as stream: stream.write(json.dumps(row) + "\n")
            if at in (1, 10, 30, 60, 90, seconds) and (proc_dir / "smaps").exists():
                (root / f"smaps-{at}s.txt").write_text((proc_dir / "smaps").read_text(encoding="utf-8", errors="replace"))
            if not quiet: print(json.dumps(row), flush=True)
    finally:
        cleanup_process(proc)
        log.close()

    if proc.returncode:
        tail = (root / "daemon.log").read_text(encoding="utf-8", errors="replace")[-1500:]
        raise RuntimeError(f"Daemon failed with code {proc.returncode}:\n{tail}")

    last = samples[-1] if samples else {}
    max_sampled_rss = max((s["rss_kib"] for s in samples), default=0)
    max_vm_hwm = max((s["vm_hwm_kib"] for s in samples), default=0)
    summary = {
        "label": label, "sha256": sha256, "seconds": seconds,
        "peak_rss_kib": max(max_sampled_rss, max_vm_hwm),
        "sampled_peak_rss_kib": max_sampled_rss, "hwm_peak_rss_kib": max_vm_hwm,
        "settled_rss_kib": last.get("rss_kib", 0), "settled_pss_kib": last.get("pss_kib", 0),
        "settled_threads": last.get("threads", 0), "total_cpu_seconds": last.get("cpu_seconds", 0.0),
        "artifact_dir": str(root),
    }
    (root / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    if not quiet: print(json.dumps(summary), flush=True)
    return summary

def self_test() -> int:
    # 1. Stat parsing with parentheses
    raw_stat = "9999 (klardrop (daemon)) S 1 2 3 4 5 6 7 8 9 10 35 15 16 17"
    fields = raw_stat.rpartition(") ")[2].split()
    assert (int(fields[11]), int(fields[12])) == (35, 15), "stat parser failed on parentheses"

    # 2. Reusable proc parser validation & peak calculation
    stat, status = "1 (sh) S 0 1 1 0 0 0 0 0 0 0 10 20", "VmHWM: 76820 kB\nThreads: 4\n"
    smaps = "Rss: 11720 kB\nPss: 10000 kB\nAnonymous: 5000 kB\nPrivate_Dirty: 2000 kB\n"
    parsed = parse_proc_sample(status, smaps, stat, ticks=100)
    assert parsed["vm_hwm_kib"] == 76820 and parsed["rss_kib"] == 11720 and parsed["threads"] == 4
    assert max(parsed["rss_kib"], parsed["vm_hwm_kib"]) == 76820, "peak calculation failed"

    # Validation errors on missing or invalid required metrics
    for bad_status in ("Threads: 4\n", "VmHWM: 0 kB\nThreads: 4\n", "VmHWM: 100 kB\nThreads: 0\n"):
        try: parse_proc_sample(bad_status, smaps, stat, 100); assert False
        except ValueError: pass

    for bad_smaps in ("Pss: 10 kB\n", "Rss: 0 kB\nPss: 1 kB\nAnonymous: 0 kB\nPrivate_Dirty: 0 kB\n",
                      "Rss: 10 kB\nPss: 10 kB\nAnonymous: -1 kB\nPrivate_Dirty: 0 kB\n"):
        try: parse_proc_sample(status, bad_smaps, stat, 100); assert False
        except ValueError: pass

    try: parse_proc_sample(status, smaps, "1 (sh) S 0 1", 100); assert False
    except ValueError: pass

    # 3. Environment isolation: assert caller environment unchanged, child env isolated
    saved_env = dict(os.environ)
    os.environ["DBUS_SESSION_BUS_ADDRESS"] = "unix:path=/test"
    os.environ["DISPLAY"] = ":0"
    os.environ["WAYLAND_DISPLAY"] = "wayland-0"
    caller_env = dict(os.environ)
    try:
        iso = setup_isolated_env()
        assert os.environ == caller_env, "setup_isolated_env modified caller environment"
        c_env, root = iso["env"], iso["root"]
        for v in ("DBUS_SESSION_BUS_ADDRESS", "DISPLAY", "WAYLAND_DISPLAY"):
            assert v not in c_env, f"{v} not stripped from child env"
        assert c_env["KLARDROP_HOME"] == str(iso["data"]), "KLARDROP_HOME mismatch"
        u_file = iso["data"] / "config" / "user-dirs.dirs"
        assert u_file.is_file() and f'XDG_DOWNLOAD_DIR="{iso["downloads"]}"' in u_file.read_text()
        import shutil; shutil.rmtree(root, ignore_errors=True)
    finally:
        os.environ.clear(); os.environ.update(saved_env)

    # 4. Actual run_profile premature exit (/bin/true --seconds 1)
    true_bin = pathlib.Path("/bin/true")
    if true_bin.exists():
        try:
            run_profile(true_bin, label="test-exit", seconds=1, quiet=True); assert False
        except RuntimeError as e:
            assert "Daemon exited prematurely" in str(e), f"unexpected error: {e}"

    # 5. Bounded cleanup on active child
    dummy = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])
    cleanup_process(dummy, timeout=1.0)
    assert dummy.poll() is not None, "bounded cleanup failed to terminate child"

    print("Self-test passed.")
    return 0

def main():
    parser = argparse.ArgumentParser(description="Profile Klardrop native daemon memory.")
    parser.add_argument("binary", nargs="?", help="Path to the klardrop-engine native executable (the engine, not the klardrop Rust client)")
    parser.add_argument("--label", default="baseline", help="Profile run label")
    parser.add_argument("--seconds", type=int, default=120, help="Profile duration in seconds")
    parser.add_argument("--self-test", action="store_true", help="Run self-test suite")
    args = parser.parse_args()

    if args.self_test: sys.exit(self_test())
    if not args.binary: parser.error("Positional argument 'binary' is required when not running --self-test.")
    if args.seconds <= 0: parser.error("--seconds must be positive.")

    try:
        run_profile(pathlib.Path(args.binary), label=args.label, seconds=args.seconds)
    except Exception as exc:
        print(json.dumps({"error": str(exc)}), file=sys.stderr); sys.exit(1)

if __name__ == "__main__":
    main()
