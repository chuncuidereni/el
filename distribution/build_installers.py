"""Fetch vendor installers on the build machine; never install anything here."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

from common import atomic_json, sha256


def fetch(url, destination):
    """Use the system TLS/proxy stack through curl, with visible progress/retries."""
    destination.parent.mkdir(parents=True, exist_ok=True)
    curl = shutil.which("curl.exe" if os.name == "nt" else "curl")
    if not curl:
        raise RuntimeError("Packaging requires curl to download official installers")
    partial = destination.with_name(destination.name + ".part")
    print("Downloading official installer asset: " + destination.name, flush=True)
    subprocess.run([curl, "--fail", "--location", "--proto", "=https", "--proto-redir", "=https",
                    "--connect-timeout", "30", "--max-time", "1800", "--retry", "3", "--retry-delay", "3",
                    "--retry-connrefused", "--progress-bar", "--max-filesize", str(3 * 1024 ** 3),
                    "--output", str(partial), url], check=True, timeout=7350)
    if not partial.stat().st_size:
        raise RuntimeError("Empty installer download")
    partial.replace(destination)


def _windows_powershell_module_path():
    """Standard Windows PowerShell 5.1 module paths, independent of the caller's PSModulePath."""
    system_root = os.environ.get("SystemRoot", r"C:\Windows")
    program_files = os.environ.get("ProgramFiles", r"C:\Program Files")
    user_profile = os.environ.get("USERPROFILE", "")
    parts = [
        os.path.join(system_root, "system32", "WindowsPowerShell", "v1.0", "Modules"),
        os.path.join(program_files, "WindowsPowerShell", "Modules"),
    ]
    if user_profile:
        parts.append(os.path.join(user_profile, "Documents", "WindowsPowerShell", "Modules"))
    return os.pathsep.join(parts)


def verify_windows_signature(path, publisher):
    env = os.environ.copy()
    env.update(EL_INSTALLER_PATH=str(path.resolve()), EL_INSTALLER_PUBLISHER=publisher)
    script = r"""
$ErrorActionPreference='Stop'
Import-Module Microsoft.PowerShell.Security -ErrorAction Stop
$s=Get-AuthenticodeSignature -LiteralPath $env:EL_INSTALLER_PATH
if ($s.Status -ne 'Valid' -or $null -eq $s.SignerCertificate -or $s.SignerCertificate.Subject -notmatch $env:EL_INSTALLER_PUBLISHER) { throw 'Installer signature/publisher verification failed' }
[string](Get-Item -LiteralPath $env:EL_INSTALLER_PATH).VersionInfo.ProductVersion
"""
    # windows-2022 can inherit a PSModulePath that omits the Windows PowerShell
    # modules directory, making Get-AuthenticodeSignature fail to autoload
    # Microsoft.PowerShell.Security. Try PowerShell 7 first, then Windows
    # PowerShell with the standard module paths pinned.
    candidates = [p for p in (shutil.which("pwsh.exe"), shutil.which("powershell.exe")) if p]
    if not candidates:
        candidates = ["powershell.exe"]
    last_error = None
    for executable in candidates:
        run_env = env.copy()
        if os.path.basename(executable).lower() == "powershell.exe":
            run_env["PSModulePath"] = _windows_powershell_module_path()
        try:
            return subprocess.check_output(
                [executable, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script],
                env=run_env, encoding="utf-8", errors="replace", timeout=120).strip()
        except subprocess.CalledProcessError as exc:
            last_error = exc
            print("Signature verification failed with " + executable + "; retrying if possible", flush=True)
    raise last_error



def prepare(system, arch, cache):
    if system == "linux":
        return {}, {}
    if system == "windows" and arch != "amd64":
        raise RuntimeError("Windows ARM bundles are not supported yet")
    cache = Path(cache).resolve() / (system + "-" + arch)
    cache.mkdir(parents=True, exist_ok=True)
    filename = "Docker Desktop Installer.exe" if system == "windows" else "Docker.dmg"
    url = "https://desktop.docker.com/" + ("win" if system == "windows" else "mac") + "/main/" + arch + "/" + filename.replace(" ", "%20")
    specs = {"docker": (filename, url, "Docker")}
    if system == "windows":
        # Bundle WSL as well, so a clean Windows machine doesn't immediately hit
        # another online installer after the Docker download has been removed.
        metadata = cache / "wsl-release.json"
        if not metadata.exists():
            fetch("https://api.github.com/repos/microsoft/WSL/releases/latest", metadata)
        release = json.loads(metadata.read_text(encoding="utf-8"))
        candidates = [asset for asset in release["assets"] if asset["name"].endswith(".x64.msi")]
        if len(candidates) != 1:
            raise RuntimeError("Official WSL release has no unique x64 MSI")
        asset = candidates[0]
        if not asset["browser_download_url"].startswith("https://github.com/microsoft/WSL/releases/download/"):
            raise RuntimeError("Unexpected WSL download source")
        specs["wsl"] = (asset["name"], asset["browser_download_url"], "Microsoft Corporation")
    manifest, paths = {}, {}
    for role, (name, source, publisher) in specs.items():
        path = cache / name
        if not path.exists():
            fetch(source, path)
        if path.is_symlink() or not path.is_file():
            raise RuntimeError("Invalid installer cache path")
        version = ""
        if system == "windows":
            version = verify_windows_signature(path, publisher)
        else:
            # Assess the vendor-signed app without running its installer.
            with tempfile.TemporaryDirectory(prefix="el-docker-signature-") as mount:
                subprocess.run(["hdiutil", "attach", "-readonly", "-nobrowse", "-mountpoint", mount, str(path)], check=True)
                try:
                    subprocess.run(["spctl", "--assess", "--type", "execute", str(Path(mount) / "Docker.app")], check=True)
                finally:
                    subprocess.run(["hdiutil", "detach", mount], check=True)
        relative = "installers/" + name
        manifest[role] = {"path": relative, "system": system, "architecture": arch, "url": source,
                          "sha256": sha256(path), "version": version, "publisher": publisher}
        paths[relative] = path
        print("Verified vendor installer: " + name + (" (" + version + ")" if version else ""), flush=True)
    atomic_json(cache / "verified-installers.json", manifest)
    return manifest, paths


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--system", choices=("windows", "macos", "linux"), required=True)
    parser.add_argument("--arch", choices=("amd64", "arm64"), required=True)
    parser.add_argument("--cache", type=Path, default=Path(__file__).resolve().parents[1] / "release" / "installer-cache")
    args = parser.parse_args()
    prepare(args.system, args.arch, args.cache)
