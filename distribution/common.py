"""Public bundle format. No deployment credentials are read on the build machine."""
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import secrets
import socket
import tarfile
import uuid

FORMAT = 1
DATABASE_IMAGES = {"mysql": "mysql:8.0.46", "clickhouse": "clickhouse/clickhouse-server:26.8.38.9"}
SECRET_KEYS = ("mysql_root_password", "mysql_password", "ck_password", "app_secret", "admin_password")


def architecture():
    value = platform.machine().lower()
    if value in ("amd64", "x86_64"):
        return "amd64"
    if value in ("arm64", "aarch64"):
        return "arm64"
    raise RuntimeError("Unsupported CPU: " + value)


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def schema_hash(root):
    entries = []
    for name in ("schema", "mysql-init"):
        for path in sorted((Path(root) / name).rglob("*")):
            if path.is_symlink():
                raise RuntimeError("Schema payload cannot contain symlinks")
            if path.is_file():
                entries.append(path.relative_to(root).as_posix() + "\0" + sha256(path))
    return hashlib.sha256("\n".join(entries).encode()).hexdigest()


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def archive_config_ids(path):
    """Classic Docker uses config digests; containerd may use manifest digests.

    Save both identities from Docker's own export so receiving machines can use
    either image store. Never extract archive paths onto the filesystem.
    """
    with tarfile.open(path, "r|gz") as archive:
        for entry in archive:
            if entry.name == "manifest.json":
                if not entry.isfile() or entry.size > 1024 * 1024:
                    raise RuntimeError("Invalid Docker archive manifest")
                result = {}
                for item in json.load(archive.extractfile(entry)):
                    name = item["Config"].rsplit("/", 1)[-1].removesuffix(".json")
                    if not re.fullmatch(r"[a-f0-9]{64}", name):
                        raise RuntimeError("Invalid Docker config identity")
                    for tag in item.get("RepoTags") or []:
                        if tag in result and result[tag] != "sha256:" + name:
                            raise RuntimeError("Export contains multiple architectures for " + tag)
                        result[tag] = "sha256:" + name
                return result
    raise RuntimeError("Docker export has no compatibility manifest")


def atomic_json(path, value):
    path = Path(path)
    temporary = path.with_name(path.name + ".tmp")
    # The enclosing directory is private before any secret is written.
    fd = os.open(str(temporary), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        if os.name != "nt":
            path.chmod(0o600)
    finally:
        temporary.unlink(missing_ok=True)


def state_directory():
    if os.name == "nt":
        base = Path(os.environ["LOCALAPPDATA"])
    elif platform.system() == "Darwin":
        base = Path.home() / "Library" / "Application Support"
    else:
        base = Path(os.environ.get("XDG_DATA_HOME", str(Path.home() / ".local" / "share")))
    if not base.is_absolute():
        raise RuntimeError("Application data directory must be absolute")
    return base / "ELDataPlatform"


def available_port(preferred=17373):
    for port in range(preferred, min(preferred + 100, 65536)):
        with socket.socket() as listener:
            try:
                listener.bind(("127.0.0.1", port))
                return port
            except OSError:
                continue
    raise RuntimeError("No available local web port near " + str(preferred))


def new_state():
    result = {"format": FORMAT, "project": "el-bundle-" + uuid.uuid4().hex[:12],
              "http_port": available_port(), "admin_user": "admin"}
    result.update({key: secrets.token_hex(24) for key in SECRET_KEYS})
    return result


def validate_state(value):
    if (value.get("format") != FORMAT or not re.fullmatch(r"el-bundle-[a-f0-9]{12}", value.get("project", ""))
            or type(value.get("http_port")) is not int or not 1024 <= value["http_port"] <= 65535
            or value.get("admin_user") != "admin"
            or any(not re.fullmatch(r"[a-f0-9]{48}", value.get(key, "")) for key in SECRET_KEYS)):
        raise RuntimeError("Local configuration is incomplete. Restore state.json; do not generate new database passwords.")


def bundled_installer(root, role):
    """Return a checked local vendor installer; never fall back to a download."""
    root = Path(root).resolve()
    manifest_path = root / "installers" / "manifest.json"
    if not manifest_path.is_file():
        raise RuntimeError("发行包缺少内置安装程序，请使用带 bundled 标识的完整运行包")
    entry = read_json(manifest_path).get(role, {})
    relative = Path(entry.get("path", ""))
    system = {"Windows": "windows", "Darwin": "macos", "Linux": "linux"}.get(platform.system())
    if (relative.is_absolute() or ".." in relative.parts or "\\" in str(entry.get("path", ""))
            or not relative.parts or relative.parts[0] != "installers"
            or entry.get("system") != system or entry.get("architecture") != architecture()):
        raise RuntimeError("内置安装程序与当前系统不匹配")
    path = root / relative
    if root not in path.resolve().parents or path.is_symlink() or not path.is_file() or sha256(path) != entry.get("sha256"):
        raise RuntimeError("内置安装程序缺失或已损坏，请重新安装；便携包请重新完整解压")
    return path


def verify_bundle(root):
    root = Path(root).resolve()
    manifest = read_json(root / "files.json")
    if not isinstance(manifest, dict) or not manifest:
        raise RuntimeError("Invalid bundle checksum manifest")
    for name, digest in manifest.items():
        relative = Path(name)
        if relative.is_absolute() or ".." in relative.parts or "\\" in name:
            raise RuntimeError("Invalid bundle path")
        path = root / relative
        if root not in path.resolve().parents or path.is_symlink() or not path.is_file():
            raise RuntimeError("Missing or unsafe bundle file: " + name)
        if not re.fullmatch(r"[a-f0-9]{64}", digest) or sha256(path) != digest:
            raise RuntimeError("Bundle checksum failed: " + name)
    required = {"payload/runtime.json", "payload/images.tar.gz", "payload/mysql-init/001-schema.sh",
                "payload/schema/clickhouse-users.xml", "install-docker-linux.sh"}
    if not required.issubset(manifest):
        raise RuntimeError("Incomplete release bundle")
    if platform.system() in ("Windows", "Darwin"):
        if "installers/manifest.json" not in manifest:
            raise RuntimeError("发行包缺少内置 Docker 安装程序")
        for role in (("docker", "wsl") if os.name == "nt" else ("docker",)):
            path = bundled_installer(root, role)
            if path.relative_to(root).as_posix() not in manifest:
                raise RuntimeError("Installer is absent from bundle checksum manifest")
    runtime = read_json(root / "payload" / "runtime.json")
    if runtime.get("images_sha256") != manifest["payload/images.tar.gz"]:
        raise RuntimeError("Image payload checksum manifest mismatch")
    if runtime.get("format") != FORMAT or runtime.get("architecture") != architecture():
        raise RuntimeError("Download the bundle matching this CPU architecture")
    if not re.fullmatch(r"[a-f0-9]{64}", runtime.get("schema_sha256", "")) or schema_hash(root / "payload") != runtime["schema_sha256"]:
        raise RuntimeError("Schema payload checksum failed")
    if set(runtime.get("images", {})) != {"app", "mysql", "clickhouse"}:
        raise RuntimeError("Incomplete image manifest")
    for role, item in runtime["images"].items():
        if any(not re.fullmatch(r"sha256:[a-f0-9]{64}", item.get(key, "")) for key in ("id", "config_id")):
            raise RuntimeError("Invalid image identity: " + role)
        layers = item.get("layers")
        if layers is not None and (not isinstance(layers, list) or any(
                not isinstance(layer, str) or not re.fullmatch(r"sha256:[a-f0-9]{64}", layer) for layer in layers)):
            raise RuntimeError("Invalid image layers: " + role)
    return runtime


def compose_config(state, runtime, payload):
    """Use immutable image IDs; only the loopback web port is published."""
    images = runtime["images"]
    logging = {"driver": "json-file", "options": {"max-size": "20m", "max-file": "3"}}
    def health(command, start="60s"):
        return {"test": ["CMD-SHELL", command], "interval": "5s", "timeout": "5s", "start_period": start, "retries": 60}
    mysql_env = {"MYSQL_ROOT_PASSWORD": state["mysql_root_password"], "MYSQL_DATABASE": "eltock",
                 "MYSQL_USER": "el2ck", "MYSQL_PASSWORD": state["mysql_password"], "TZ": "Asia/Shanghai"}
    ck_env = {"CLICKHOUSE_DB": "default", "CLICKHOUSE_USER": "el2ck_admin", "CLICKHOUSE_PASSWORD": state["ck_password"],
              "CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT": "1"}
    app_env = {
        "DEPLOYMENT_MODE": "standalone", "SPRING_PROFILES_ACTIVE": "test", "TZ": "Asia/Shanghai",
        "JAVA_TOOL_OPTIONS": "-Xms256m -Xmx2g -Duser.timezone=Asia/Shanghai", "SERVER_PORT": "17878",
        "APP_DATADIR": "/app/data", "APP_WEBDIR": "/app/web/dist", "SERVER_ADDRESS": "0.0.0.0",
        "APP_METADATA_URL": "jdbc:mysql://mysql:3306/eltock?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true",
        "APP_METADATA_USERNAME": "el2ck", "APP_METADATA_PASSWORD": state["mysql_password"],
        "APP_SECRETKEY": state["app_secret"], "APP_USER": "admin", "APP_PASSWORD": state["admin_password"],
        "APP_DEFAULTCK_HOST": "clickhouse", "APP_DEFAULTCK_PROTOCOL": "http", "APP_DEFAULTCK_PORT": "8123",
        "APP_DEFAULTCK_DATABASE": "default", "APP_DEFAULTCK_USERNAME": "el2ck_admin", "APP_DEFAULTCK_PASSWORD": state["ck_password"],
        "APP_DEFAULTCK_SECURE": "false", "CK_MANAGEMENT_ENABLED": "true", "CK_MANAGEMENT_USERNAME": "el2ck_admin",
        "CK_MANAGEMENT_PASSWORD": state["ck_password"], "CK_DATABASES": "ods_data,dwd_data", "XXL_JOB_ENABLED": "false"
    }
    def service(role, environment, volumes, **extra):
        return {"image": images[role]["id"], "pull_policy": "never", "restart": "unless-stopped",
                "environment": environment, "volumes": volumes, "logging": logging, **extra}
    mysql_mounts = ["mysql-data:/var/lib/mysql"] + [
        {"type": "bind", "source": str((payload / source).resolve()), "target": target, "read_only": True,
         "bind": {"create_host_path": False}} for source, target in
        [("mysql-init", "/docker-entrypoint-initdb.d"), ("schema", "/opt/el2ck-schema")]]
    services = {
        "mysql": service("mysql", mysql_env, mysql_mounts,
                         command=["--character-set-server=utf8mb4", "--collation-server=utf8mb4_bin"],
                         healthcheck=health('MYSQL_PWD="$$MYSQL_PASSWORD" mysql --protocol=TCP -h127.0.0.1 -uel2ck eltock -Nse "SELECT ready FROM el_bundle_bootstrap WHERE id=1" | grep -qx 1', "90s")),
        "clickhouse": service("clickhouse", ck_env, ["ck-data:/var/lib/clickhouse", "ck-logs:/var/log/clickhouse-server",
                              {"type": "bind", "source": str((payload / "schema" / "clickhouse-users.xml").resolve()),
                               "target": "/etc/clickhouse-server/users.d/el-sync.xml", "read_only": True,
                               "bind": {"create_host_path": False}}],
                              ulimits={"nofile": {"soft": 262144, "hard": 262144}},
                              healthcheck=health('clickhouse-client --host 127.0.0.1 --user "$$CLICKHOUSE_USER" --password "$$CLICKHOUSE_PASSWORD" --query "SELECT 1" | grep -qx 1')),
        "app": service("app", app_env, ["app-data:/app/data"], init=True, mem_limit="4g", stop_grace_period="60s",
                       ports=[{"target": 17878, "published": str(state["http_port"]), "host_ip": "127.0.0.1", "protocol": "tcp"}],
                       depends_on={"mysql": {"condition": "service_healthy"}, "clickhouse": {"condition": "service_healthy"}})
    }
    return {"name": state["project"], "services": services, "volumes": {name: {} for name in ("mysql-data", "ck-data", "ck-logs", "app-data")}}
