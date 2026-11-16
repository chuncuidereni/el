"""Freeze a native launcher and assemble a public release; run on each target OS."""
import argparse
import json
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

from common import architecture, read_json, schema_hash, sha256
from build_installers import prepare

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--payload", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "release" / "public")
    parser.add_argument("--installer-cache", type=Path, default=ROOT / "release" / "installer-cache")
    args = parser.parse_args()
    runtime = read_json(args.payload / "runtime.json")
    arch, version = architecture(), runtime["version"]
    if runtime["architecture"] != arch or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,80}", version):
        raise RuntimeError("Payload does not match build host architecture or has an invalid version")
    if sha256(args.payload / "images.tar.gz") != runtime["images_sha256"] or schema_hash(args.payload) != runtime["schema_sha256"]:
        raise RuntimeError("Payload is damaged")
    system = {"Windows": "windows", "Darwin": "macos", "Linux": "linux"}[platform.system()]
    name = "EL-" + version + ("-bundled" if system != "linux" else "") + "-" + system + "-" + arch
    args.output.mkdir(parents=True, exist_ok=True)
    archive = args.output / (name + ".zip")
    if archive.exists():
        raise RuntimeError("Refusing to overwrite a release archive: " + str(archive))
    installers, installer_paths = prepare(system, arch, args.installer_cache)
    with tempfile.TemporaryDirectory(prefix="el-launcher-") as temporary:
        work = Path(temporary)
        subprocess.run([sys.executable, "-m", "PyInstaller", "--clean", "--noconfirm", "--onefile", "--console",
                        "--name", "el-launcher", "--distpath", str(work / "dist"), "--workpath", str(work / "work"),
                        "--specpath", str(work), str(ROOT / "distribution" / "launcher.py")], check=True)
        folder = work / name
        folder.mkdir()
        exe_name = "el-launcher.exe" if system == "windows" else "el-launcher"
        shutil.copyfile(work / "dist" / exe_name, folder / exe_name)
        (folder / exe_name).chmod(0o755)
        for label, action in (("Start", "start"), ("Stop", "stop"), ("Status", "status"), ("Login", "login"), ("Logs", "logs")):
            if system == "windows":
                (folder / (label + ".cmd")).write_text('@echo off\nchcp 65001 >nul\n"%~dp0el-launcher.exe" ' + action + '\n', encoding="ascii", newline="\r\n")
            else:
                script = folder / (label + (".command" if system == "macos" else ".sh"))
                script.write_text('#!/bin/sh\ncd -- "$(dirname -- "$0")" || exit 1\nexec ./el-launcher ' + action + '\n', encoding="utf-8", newline="\n")
                script.chmod(0o755)
        shutil.copyfile(ROOT / "docs" / "跨平台一键运行包.md", folder / "README.md")
        installer = folder / "install-docker-linux.sh"
        installer.write_bytes((ROOT / "distribution" / "install-docker-linux.sh").read_bytes().replace(b"\r\n", b"\n"))
        installer.chmod(0o755)
        if installers:
            (folder / "installers").mkdir()
            (folder / "installers" / "manifest.json").write_text(json.dumps(installers, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        files = {p.relative_to(folder).as_posix(): sha256(p) for p in folder.rglob("*") if p.is_file()}
        # Payload is streamed into the ZIP directly, avoiding another multi-GB copy.
        payload_paths = {}
        for path in sorted(args.payload.rglob("*")):
            if path.is_symlink():
                raise RuntimeError("Payload contains a symlink")
            if path.is_file():
                relative = "payload/" + path.relative_to(args.payload).as_posix()
                payload_paths[relative] = path
                files[relative] = sha256(path)
        for relative, path in installer_paths.items():
            payload_paths[relative] = path
            files[relative] = sha256(path)
        (folder / "files.json").write_text(json.dumps(files, indent=2) + "\n", encoding="utf-8")
        with zipfile.ZipFile(archive, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=1) as output:
            for path in folder.rglob("*"):
                if path.is_file():
                    output.write(path, name + "/" + path.relative_to(folder).as_posix())
            for relative, path in payload_paths.items():
                output.write(path, name + "/" + relative, compress_type=zipfile.ZIP_STORED if relative.endswith(".gz") else zipfile.ZIP_DEFLATED)
        if archive.stat().st_size >= 2 * 1024 ** 3:
            raise RuntimeError("Archive exceeds the GitHub Release per-asset limit; split the image payload before publishing")
    archive.with_suffix(".zip.sha256").write_text(sha256(archive) + "  " + archive.name + "\n", encoding="ascii")
    print("Public release ready: " + str(archive))


if __name__ == "__main__":
    main()
