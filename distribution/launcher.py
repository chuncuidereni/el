"""Recipient-side launcher. Default: verify, provision Docker, initialize, open UI.

Only private managed containers are touched; no external/212 configuration is
read, and stop/upgrade never removes volumes or changes database credentials.
"""
import argparse
import copy
from contextlib import contextmanager
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
import webbrowser

from common import atomic_json, available_port, compose_config, new_state, read_json, schema_hash, state_directory, validate_state, verify_bundle
from docker_runtime import ensure_docker, powershell, run


def configure_stdio():
    """Force UTF-8 output before any Chinese message reaches a Windows pipe/console.

    GitHub Actions and other non-console callers inherit a cp1252/charmap stdout;
    printing Chinese then raises UnicodeEncodeError before verification can run.
    """
    for stream in (sys.stdout, sys.stderr):
        if stream is None or not hasattr(stream, "reconfigure"):
            continue
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass
    if os.name == "nt":
        try:
            import ctypes
            ctypes.windll.kernel32.SetConsoleOutputCP(65001)
            ctypes.windll.kernel32.SetConsoleCP(65001)
        except Exception:
            pass


configure_stdio()


def bundle_directory():
    return Path(sys.executable if getattr(sys, "frozen", False) else __file__).resolve().parent


def private_directory(path):
    if path.is_symlink():
        raise RuntimeError("数据目录不能是符号链接")
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    if os.name == "nt":
        powershell(r"""
$ErrorActionPreference='Stop'
$acl=New-Object System.Security.AccessControl.DirectorySecurity
$acl.SetAccessRuleProtection($true,$false)
$ids=@([System.Security.Principal.WindowsIdentity]::GetCurrent().User,
  [System.Security.Principal.SecurityIdentifier]::new('S-1-5-18'),
  [System.Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))
foreach($id in $ids) {
  $acl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($id,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
}
[System.IO.Directory]::SetAccessControl($env:EL_BOOTSTRAP_STATE_DIR,$acl)
""", {"EL_BOOTSTRAP_STATE_DIR": str(path)})
    else:
        path.chmod(0o700)


@contextmanager
def installation_lock(directory):
    with (directory / "launcher.lock").open("a+b") as stream:
        stream.seek(0)
        stream.write(b"0")
        stream.flush()
        stream.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            raise RuntimeError("已有启动器正在操作此安装，请等待其完成") from error
        try:
            yield
        finally:
            stream.seek(0)
            if os.name == "nt":
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


def show_login(state, directory):
    print("\n访问地址：http://127.0.0.1:" + str(state["http_port"]))
    print("初始账号：admin")
    print("初始密码：" + state["admin_password"])
    print("如果已在平台修改密码，请使用修改后的密码。")
    print("配置与密钥目录：" + str(directory))


def resolve_image(prefix, env, item, architecture):
    identities = {item["id"], item["config_id"]}
    expected_layers = item.get("layers") or []
    for reference in dict.fromkeys((item["id"], item["config_id"], item["tag"])):
        result = run(prefix + ["image", "inspect", reference,
                               "--format", "{{.Id}}|{{.Os}}|{{.Architecture}}|{{json .RootFS.Layers}}"],
                     capture=True, check=False, timeout=30, env=env)
        parts = result.stdout.strip().split("|")
        if result.returncode != 0 or len(parts) != 4 or parts[1:3] != ["linux", architecture]:
            continue
        if parts[0] in identities:
            return parts[0]
        if expected_layers:
            try:
                actual_layers = json.loads(parts[3])
            except ValueError:
                continue
            # containerd stores report the manifest digest as .Id, while the
            # payload records the build host's image/config digest. Matching the
            # content-addressed layer list proves the imported image is ours.
            if actual_layers == expected_layers:
                return parts[0]
    return None


def load_images(prefix, env, bundle, runtime):
    def resolve():
        return {role: resolve_image(prefix, env, item, runtime["architecture"]) for role, item in runtime["images"].items()}
    resolved = resolve()
    if not all(resolved.values()):
        print("正在导入包内的程序、MySQL 和 ClickHouse 镜像……", flush=True)
        run(prefix + ["load", "--input", bundle / "payload" / "images.tar.gz"], env=env, timeout=1800)
        resolved = resolve()
    if not all(resolved.values()):
        raise RuntimeError("导入后的镜像与发行包记录不一致，已停止启动")
    return resolved


def stage_schema(bundle, directory, runtime):
    destination = directory / "schemas" / runtime["schema_sha256"]
    if destination.exists():
        if schema_hash(destination) != runtime["schema_sha256"]:
            raise RuntimeError("本机保存的初始化结构校验失败，原数据库未修改")
        return destination
    temporary = destination.with_name(destination.name + ".tmp")
    # Never overwrite or recursively delete a partially initialized installation.
    if temporary.exists():
        raise RuntimeError("上次复制初始化结构未完成，请检查目录：" + str(temporary))
    temporary.mkdir(parents=True)
    for name in ("schema", "mysql-init"):
        shutil.copytree(bundle / "payload" / name, temporary / name)
    temporary.rename(destination)
    return destination


def start(bundle, directory, runtime, state, prefix, env, browser):
    previous_state = copy.deepcopy(state)
    prior = state.get("active_runtime")
    if prior:
        # New versions with different database formats need an explicit managed
        # migration, never a second initialization over an existing data volume.
        if prior["schema_sha256"] != runtime["schema_sha256"] or any(
                prior["images"][role]["tag"] != runtime["images"][role]["tag"] for role in ("mysql", "clickhouse")):
            raise RuntimeError("此版本包含数据库结构或版本变化，需提供配套升级流程；原数据和服务未修改")
    resolved = load_images(prefix, env, bundle, runtime)
    payload = stage_schema(bundle, directory, runtime)
    active_path = directory / "compose.json"
    previous = read_json(active_path) if active_path.exists() else None
    running = run(prefix + ["ps", "-q", "--filter", "label=com.docker.compose.project=" + state["project"],
                            "--filter", "label=com.docker.compose.service=app"], capture=True, env=env).stdout.strip()
    if not running:
        state["http_port"] = available_port(state["http_port"])
    effective_runtime = copy.deepcopy(runtime)
    for role, identity in resolved.items():
        effective_runtime["images"][role]["id"] = identity
    config = compose_config(state, effective_runtime, payload)
    atomic_json(directory / "state.json", state)
    atomic_json(active_path, config)
    compose = prefix + ["compose", "--project-name", state["project"], "-f", str(active_path)]
    print("正在初始化并启动数据库和平台，首次启动可能需要数分钟……", flush=True)
    try:
        run(compose + ["config", "--quiet"], capture=True, env=env)
        run(compose + ["up", "-d", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "600"], env=env, timeout=900)
        # Never send local health requests through a host proxy.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open("http://127.0.0.1:" + str(state["http_port"]) + "/api/health/ready", timeout=10) as response:
            if response.status != 200:
                raise RuntimeError("平台就绪检查未通过")
    except Exception:
        if previous and prior:
            print("新版本未就绪，正在恢复原应用版本；不删除数据卷。", flush=True)
            atomic_json(active_path, previous)
            atomic_json(directory / "state.json", previous_state)
            try:
                restored = run(compose + ["up", "-d", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "300"],
                               env=env, timeout=600, check=False)
                if restored.returncode:
                    print("原配置已恢复，但服务尚未就绪，请通过 Logs 入口检查。", file=sys.stderr)
            except Exception:
                print("原配置已恢复，但恢复服务失败，请通过 Logs 入口检查。", file=sys.stderr)
        raise
    state["active_runtime"] = runtime
    atomic_json(directory / "state.json", state)
    show_login(state, directory)
    print("关闭启动器不会停止服务；需要停止时运行“停止”入口。")
    if browser:
        webbrowser.open("http://127.0.0.1:" + str(state["http_port"]))


def main():
    if os.name == "nt" and getattr(sys, "frozen", False):
        # PyInstaller adds its extraction directory to Windows DLL search. Child
        # system installers must load their own DLLs, not Python's bundled DLLs.
        import ctypes
        ctypes.windll.kernel32.SetDllDirectoryW(None)
    parser = argparse.ArgumentParser(description="EL 一键运行包：自动安装 Docker、生成配置并启动平台")
    parser.add_argument("action", nargs="?", default="start", choices=("start", "stop", "status", "logs", "login", "verify"))
    parser.add_argument("--no-browser", action="store_true")
    parser.add_argument("--no-pause", action="store_true")
    args = parser.parse_args()
    code = 0
    try:
        bundle = bundle_directory()
        print("正在校验发行包……", flush=True)
        runtime = verify_bundle(bundle)
        if args.action == "verify":
            print("发行包校验通过；未安装或启动任何服务。")
            return 0
        directory = state_directory()
        if args.action != "start" and not (directory / "state.json").is_file():
            raise RuntimeError("尚未初始化，请先启动平台")
        private_directory(directory)
        with installation_lock(directory):
            state_file = directory / "state.json"
            if state_file.exists():
                state = read_json(state_file)
                validate_state(state)
            else:
                if (directory / "compose.json").exists():
                    raise RuntimeError("配置丢失，请恢复 state.json，不能为已有数据库重新生成密码")
                state = new_state()
                atomic_json(state_file, state)
            if args.action == "login":
                show_login(state, directory)
                return 0
            prefix, env = ensure_docker(bundle, directory, args.action == "start")
            if args.action == "start":
                start(bundle, directory, runtime, state, prefix, env, not args.no_browser)
            else:
                compose = prefix + ["compose", "--project-name", state["project"], "-f", str(directory / "compose.json")]
                command = {"stop": ["stop", "--timeout", "60"], "status": ["ps"], "logs": ["logs", "--tail", "150"]}[args.action]
                run(compose + command, env=env)
    except (Exception, KeyboardInterrupt) as error:
        print("\n操作未完成：" + (str(error) or "已取消"), file=sys.stderr)
        print("没有删除数据库或任务数据。处理提示后可再次双击启动。", file=sys.stderr)
        code = 1
    finally:
        if not args.no_pause and sys.stdin.isatty():
            try:
                input("\n按回车关闭启动器……")
            except (EOFError, KeyboardInterrupt):
                pass
    return code


if __name__ == "__main__":
    raise SystemExit(main())
