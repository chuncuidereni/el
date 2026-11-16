"""Docker provisioning for the destination computer, never for package builds."""
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import time

from common import architecture, bundled_installer


def run(args, *, capture=False, check=True, timeout=600, env=None):
    result = subprocess.run([str(x) for x in args], capture_output=capture, text=True,
                            encoding="utf-8", errors="replace", timeout=timeout, env=env if env is not None else docker_environment())
    if check and result.returncode:
        # Arguments can contain a local configuration filename. Never dump config contents.
        raise RuntimeError("命令执行失败：" + str(args[0]) + "，退出码 " + str(result.returncode))
    return result


def docker_environment():
    env = os.environ.copy()
    for key in list(env):
        if key.startswith(("DOCKER_", "COMPOSE_")):
            env.pop(key)
    # PyInstaller changes dynamic-library lookup for itself; installed Docker and
    # system tools must use system libraries, not bundled Python libraries.
    for key in ("LD_LIBRARY_PATH", "DYLD_LIBRARY_PATH"):
        original = env.get(key + "_ORIG")
        if original is None:
            env.pop(key, None)
        else:
            env[key] = original
    return env


def docker_binary():
    paths = [shutil.which("docker")]
    if os.name == "nt":
        for base in (Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Docker" / "Docker",
                     Path(os.environ.get("LOCALAPPDATA", "")) / "Programs" / "DockerDesktop"):
            paths.append(str(base / "resources" / "bin" / "docker.exe"))
    elif platform.system() == "Darwin":
        paths += [str(Path.home() / ".docker" / "bin" / "docker"), "/Applications/Docker.app/Contents/Resources/bin/docker"]
    return next((str(Path(p).absolute()) for p in paths if p and Path(p).is_file()), None)


def powershell(script, extra_env=None, *, check=True, timeout=600):
    env = docker_environment()
    if extra_env:
        env.update(extra_env)
    return run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script], env=env, check=check, timeout=timeout)


def wsl_ready():
    try:
        result = run(["wsl.exe", "--version"], capture=True, check=False, timeout=30)
        # wsl.exe may emit UTF-16 text even when redirected. Its ASCII version
        # digits remain readable after stripping the interleaved NUL bytes.
        version = re.search(r"(\d+)\.(\d+)\.(\d+)", result.stdout.replace("\x00", ""))
        return result.returncode == 0 and version is not None and tuple(map(int, version.groups())) >= (2, 1, 5)
    except (OSError, subprocess.TimeoutExpired):
        return False


def install_wsl(bundle):
    installer = bundled_installer(bundle, "wsl")
    print("正在使用包内 Microsoft WSL 安装程序；完成后系统可能需要重启。", flush=True)
    result = powershell(r"""
$ErrorActionPreference='Stop'
$s=Get-AuthenticodeSignature -LiteralPath $env:EL_WSL_INSTALLER
if ($s.Status -ne 'Valid' -or $s.SignerCertificate.Subject -notmatch 'Microsoft Corporation') { throw 'WSL signature verification failed' }
$reboot=$false
$p=Start-Process -FilePath 'msiexec.exe' -ArgumentList '/i',('"{0}"' -f $env:EL_WSL_INSTALLER),'/passive','/norestart' -Verb RunAs -Wait -PassThru -WindowStyle Hidden
if ($p.ExitCode -eq 3010 -or $p.ExitCode -eq 1641) { $reboot=$true } elseif ($p.ExitCode -ne 0) { exit $p.ExitCode }
# LimitAccess prevents DISM from trying Windows Update for feature files.
$p=Start-Process -FilePath 'dism.exe' -ArgumentList '/online','/enable-feature','/featurename:VirtualMachinePlatform','/all','/norestart','/LimitAccess' -Verb RunAs -Wait -PassThru -WindowStyle Hidden
if ($p.ExitCode -eq 3010) { $reboot=$true } elseif ($p.ExitCode -ne 0) { exit $p.ExitCode }
if ($reboot) { exit 3010 }
""", {"EL_WSL_INSTALLER": str(installer)}, check=False, timeout=1800)
    if result.returncode in (3010, 1641):
        raise RuntimeError("WSL 与虚拟机组件已安装，请重启 Windows 后再次双击 Start.cmd")
    if result.returncode or not wsl_ready():
        raise RuntimeError("WSL 尚未就绪，请检查系统组件安装提示或重启后重试；退出码 " + str(result.returncode))


def install_desktop(bundle, cache):
    installer = bundled_installer(bundle, "docker")
    arch = architecture()
    if os.name == "nt":
        if arch != "amd64":
            raise RuntimeError("本发行包暂不提供 Windows ARM 自动安装")
        if not wsl_ready():
            install_wsl(bundle)
        print("正在使用包内 Docker Desktop 安装程序……", flush=True)
        # Verify Authenticode before executing the downloaded installer. Do not
        # silently accept the Docker Desktop license on behalf of the user.
        powershell(r"""
$ErrorActionPreference='Stop'
$signature=Get-AuthenticodeSignature -LiteralPath $env:EL_DOCKER_INSTALLER
if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'Docker') { throw 'Docker installer signature verification failed' }
$p=Start-Process -FilePath $env:EL_DOCKER_INSTALLER -ArgumentList 'install','--backend=wsl-2' -Verb RunAs -Wait -PassThru -WindowStyle Hidden
if ($p.ExitCode -eq 3010 -or $p.ExitCode -eq 1641) { Write-Host 'Installation requires a restart. Run the EL launcher again afterwards.'; exit 10 }
if ($p.ExitCode -ne 0) { exit $p.ExitCode }
""", {"EL_DOCKER_INSTALLER": str(installer)}, timeout=1800)
    else:
        print("正在使用包内 Docker Desktop 安装程序……", flush=True)
        mount = cache / "docker-mount"
        mount.mkdir(exist_ok=True)
        run(["hdiutil", "attach", "-nobrowse", "-mountpoint", mount, installer])
        try:
            app = mount / "Docker.app"
            run(["spctl", "--assess", "--type", "execute", "--verbose", app])
            run(["sudo", str(app / "Contents" / "MacOS" / "install")], timeout=1200)
        finally:
            run(["hdiutil", "detach", mount], check=False)
    # Keep the bundled installer intact for a retry or another destination PC.


def desktop_application():
    if os.name == "nt":
        candidates = [Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Docker" / "Docker" / "Docker Desktop.exe",
                      Path(os.environ.get("LOCALAPPDATA", "")) / "Programs" / "DockerDesktop" / "Docker Desktop.exe"]
        binary = docker_binary()
        if binary and len(Path(binary).parents) > 2:
            candidates.append(Path(binary).parents[2] / "Docker Desktop.exe")
        return next((path for path in candidates if path.is_file()), None)
    path = Path("/Applications/Docker.app")
    return path if platform.system() == "Darwin" and path.is_dir() else None


def start_desktop():
    app = desktop_application()
    if app:
        if os.name == "nt":
            subprocess.Popen([str(app)], env=docker_environment())
        else:
            run(["open", "-a", str(app)])


def ensure_docker(bundle, state_dir, install):
    binary = docker_binary()
    needs_install = not binary
    if binary and install and (os.name == "nt" or platform.system() == "Darwin") and not desktop_application():
        # A separately installed CLI is not a complete Docker installation. Reuse
        # a working local engine, otherwise provision Desktop for the recipient.
        try:
            endpoint = run([binary, "--context", "default", "context", "inspect", "default", "--format", "{{.Endpoints.docker.Host}}"],
                           capture=True, check=False, timeout=10)
            local = endpoint.returncode == 0 and endpoint.stdout.strip().startswith(("unix://", "npipe://"))
            needs_install = not local or run([binary, "--context", "default", "info"], capture=True, check=False, timeout=15).returncode != 0
        except subprocess.TimeoutExpired:
            needs_install = True
    if needs_install and install:
        print("未发现可用 Docker，准备安装。系统可能要求管理员授权或重启；许可条款请在安装界面确认。", flush=True)
        if os.name == "nt" or platform.system() == "Darwin":
            cache = state_dir / "installation"
            cache.mkdir(exist_ok=True)
            install_desktop(bundle, cache)
        elif platform.system() == "Linux":
            prefix = [] if os.geteuid() == 0 else ["sudo"]
            run(prefix + ["bash", str(bundle / "install-docker-linux.sh")], env=docker_environment(), timeout=1800)
        else:
            raise RuntimeError("不支持的操作系统")
        binary = docker_binary()
    if not binary:
        raise RuntimeError("Docker 尚未安装完成，请完成安装或重启后再次启动")
    env = docker_environment()
    if install:
        if os.name == "nt" or platform.system() == "Darwin":
            start_desktop()
        else:
            probe = run([binary, "--context", "default", "info"], capture=True, check=False, timeout=15, env=env)
            if probe.returncode:
                prefix = [] if os.geteuid() == 0 else ["sudo"]
                run(prefix + ["systemctl", "start", "docker"], env=env)
    print("正在检查本机 Docker（Desktop 首次启动可能需要确认许可/完成 WSL 安装）……", flush=True)
    deadline = time.monotonic() + (300 if install else 15)
    contexts = ["desktop-linux", "default"] if os.name == "nt" or platform.system() == "Darwin" else ["default"]
    sudo_prefix = []
    if platform.system() == "Linux" and os.geteuid() != 0:
        probe = run([binary, "--context", "default", "info"], capture=True, check=False, timeout=15, env=env)
        if probe.returncode:
            # Do not add the user permanently to the privileged docker group.
            run(["sudo", "-v"], env=env)
            sudo_prefix = ["sudo", "-n"]
    while True:
        for context in contexts:
            prefix = sudo_prefix + [binary, "--context", context]
            try:
                endpoint = run(prefix + ["context", "inspect", context, "--format", "{{.Endpoints.docker.Host}}"],
                               capture=True, check=False, timeout=10, env=env)
                if endpoint.returncode or not endpoint.stdout.strip().startswith(("unix://", "npipe://")):
                    continue
                info = run(prefix + ["info", "--format", "{{.OSType}}/{{.Architecture}}"], capture=True,
                           check=False, timeout=15, env=env)
                if info.returncode == 0:
                    expected = "aarch64" if architecture() == "arm64" else "x86_64"
                    if info.stdout.strip() not in ("linux/" + architecture(), "linux/" + expected):
                        raise RuntimeError("Docker 必须运行与本机 CPU 对应的 Linux 容器，请检查 Desktop 容器模式")
                    run(prefix + ["compose", "version"], capture=True, env=env)
                    help_text = run(prefix + ["compose", "up", "--help"], capture=True, env=env).stdout
                    if "--wait-timeout" not in help_text:
                        raise RuntimeError("请升级 Docker Compose，当前版本不支持服务就绪等待")
                    return prefix, env
            except subprocess.TimeoutExpired:
                continue
        if time.monotonic() >= deadline:
            raise RuntimeError("Docker 未就绪。请完成 Desktop/WSL 安装、许可确认或系统重启后，再双击启动；不会清理已有数据。")
        time.sleep(3)
