"""Build the private source archive used by the 212 deployment server.

The archive contains source code plus a generated .env file. It intentionally
does not contain local application*.yml files, because those files may include
credentials and are replaced in the image by docker/application.yml.
"""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import hashlib
import ipaddress
import io
import json
import os
import re
import sys
import tarfile
import time
from bundle_support import credentials, standalone_app, add_offline_images, file_hash

try:
    import yaml
except ImportError:
    sys.exit("PyYAML is required on the packaging computer: python -m pip install PyYAML")


ROOT = Path(__file__).resolve().parents[1]
ENV_FILE = ROOT / ".env"

PACKAGING_ENVIRONMENT_KEYS = {
    "APP_SECRET_KEY",
    "ADMIN_USER",
    "ADMIN_PASSWORD",
    "CK_MANAGEMENT_USERNAME",
    "CK_MANAGEMENT_PASSWORD",
    "CK_MANAGEMENT_ENABLED",
    "CK_DATABASES",
    "METADATA_URL",
    "METADATA_USERNAME",
    "METADATA_PASSWORD",
    "CK_HOST",
    "CK_PROTOCOL",
    "CK_PORT",
    "CK_DATABASE",
    "CK_USERNAME",
    "CK_PASSWORD",
    "CK_SECURE",
}

CK_PORT_PROTOCOLS = {
    9000: "native",
    9001: "native",
    8123: "http",
    8132: "http",
}


def release_info(file_hashes, packaged_at):
    """Publish only a release identifier and aggregate source hash; never configuration or credentials."""
    source = "\n".join(
        name + "\0" + digest for name, digest in sorted(file_hashes.items())
        if not Path(name).name.startswith(".env")
    )
    packed = datetime.fromtimestamp(packaged_at, timezone.utc)
    return {
        "releaseId": "212-" + packed.strftime("%Y%m%dT%H%M%SZ"),
        "packagedAt": packed.isoformat(),
        "sourceSha256": hashlib.sha256(source.encode("utf-8")).hexdigest(),
    }


def dotenv(value):
    """Render one value as a single-quoted, single-line .env entry."""
    text = str(value)
    if "\n" in text or "\r" in text or "${" in text:
        raise ValueError("Deployment configuration must contain resolved single-line values")
    return "'" + text.replace("'", "\\'") + "'"


def read_dotenv(path):
    """Read simple KEY=VALUE entries without printing any value."""
    values = {}
    if not path.exists():
        return values
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1].replace("\\'", "'")
        values[key.strip()] = value
    return values


def parse_boolean(value, name):
    """Parse a deployment boolean and report only the field name on failure."""
    if isinstance(value, bool):
        return value
    text = str(value).strip().lower()
    if text in {"true", "1", "yes", "on"}:
        return True
    if text in {"false", "0", "no", "off"}:
        return False
    raise ValueError(name + " must be true or false")


def normalize_host(value, field="CK_HOST"):
    """Validate a hostname/IP and reject URL-like credential carriers."""
    text = str(value).strip()
    if not text or any(character in text for character in "/@?#"):
        raise ValueError(field + " must be a hostname or IP address")

    if text.startswith("[") and text.endswith("]"):
        candidate = text[1:-1]
        try:
            ipaddress.IPv6Address(candidate)
        except ValueError as exc:
            raise ValueError(field + " must be a hostname or IP address") from exc
        return text

    if ":" in text:
        try:
            ipaddress.IPv6Address(text)
        except ValueError as exc:
            raise ValueError(field + " must be a hostname or IP address") from exc
        return text

    if not re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9._-]{0,251}[A-Za-z0-9])?", text):
        raise ValueError(field + " must be a hostname or IP address")
    return text


def normalize_clickhouse(ck, explicit_protocol=None, explicit_secure=None):
    """Apply CK endpoint overrides and enforce the supported protocol/port pairs."""
    try:
        port = int(ck.get("port", 9000))
    except (TypeError, ValueError) as exc:
        raise ValueError("CK_PORT must be an integer") from exc
    if port not in CK_PORT_PROTOCOLS:
        raise ValueError("CK_PORT must be one of 9000, 9001, 8123, or 8132")

    expected_protocol = CK_PORT_PROTOCOLS[port]
    if explicit_protocol is None or str(explicit_protocol).strip() == "":
        protocol = expected_protocol
    else:
        protocol = str(explicit_protocol).strip().lower()
        if protocol not in {"native", "http"}:
            raise ValueError("CK_PROTOCOL must be native or http")
        if protocol != expected_protocol:
            raise ValueError(
                "CK_PROTOCOL conflicts with CK_PORT: "
                + protocol + " is not valid for port " + str(port)
            )

    secure_value = ck.get("secure", False) if explicit_secure is None else explicit_secure
    ck["host"] = normalize_host(ck.get("host", ""))
    ck["port"] = port
    ck["protocol"] = protocol
    ck["database"] = str(ck.get("database", "default")).strip()
    ck["secure"] = parse_boolean(secure_value, "CK_SECURE")
    return ck


def metadata_target(url):
    """Return only the MySQL host and port from a JDBC URL."""
    match = re.match(
        r"^jdbc:mysql://(\[[^\]]+\]|[^/:?#]+)(?::(\d+))?(?:[/?#]|$)",
        str(url)
    )
    if not match:
        raise ValueError("METADATA_URL must be a valid JDBC MySQL URL")
    host = normalize_host(match.group(1), "METADATA_URL host")
    port = int(match.group(2) or 3306)
    if not 1 <= port <= 65535:
        raise ValueError("METADATA_URL port must be between 1 and 65535")
    return host, port


def target_summary(metadata, ck):
    """Return credential-free target lines for console output."""
    mysql_host, mysql_port = metadata_target(metadata["url"])
    return (
        "MySQL target: " + mysql_host + ":" + str(mysql_port),
        "ClickHouse target: " + normalize_host(ck["host"]) + ":" + str(ck["port"])
        + " (" + ck["protocol"] + ")",
    )


def deployment_config(profile):
    """Merge application.yml, the selected profile, .env, and process overrides."""
    base_path = ROOT / "src/main/resources/application.yml"
    profile_path = ROOT / f"src/main/resources/application-{profile}.yml"
    if not base_path.is_file() or not profile_path.is_file():
        raise ValueError("Required application configuration is missing: " + str(profile_path))

    config = yaml.safe_load(base_path.read_text(encoding="utf-8"))
    overlay = yaml.safe_load(profile_path.read_text(encoding="utf-8"))

    def merge(base, extra):
        for key, value in (extra or {}).items():
            if isinstance(value, dict) and isinstance(base.get(key), dict):
                merge(base[key], value)
            else:
                base[key] = value

    merge(config, overlay)

    environment = {
        key: value
        for key, value in read_dotenv(ENV_FILE).items()
        if key in PACKAGING_ENVIRONMENT_KEYS
    }
    environment.update({
        key: value
        for key, value in os.environ.items()
        if key in PACKAGING_ENVIRONMENT_KEYS
    })

    def resolve(value):
        if isinstance(value, dict):
            return {key: resolve(item) for key, item in value.items()}
        if not isinstance(value, str):
            return value

        def replace(match):
            key, separator, default = match.group(1).partition(":")
            if key in environment:
                return environment[key]
            if separator:
                return default
            raise ValueError("Missing environment variable: " + key)

        return re.sub(r"\$\{([^{}]+)\}", replace, value)

    app = resolve(config["app"])
    metadata = app.setdefault("metadata", {})
    ck = app.setdefault("default-ck", {})

    for field in ("url", "username", "password"):
        key = "METADATA_" + field.upper()
        if key in environment:
            metadata[field] = environment[key]

    for field in ("host", "database", "username", "password"):
        key = "CK_" + field.upper()
        if key in environment:
            ck[field] = environment[key]

    # ADMIN_* values in .env are compose interpolation inputs, but packaging must
    # also apply them to app.user/app.password so the generated archive reflects
    # the same administrator credentials that the container will receive.
    for field in ("user", "password"):
        key = "ADMIN_" + field.upper()
        if key in environment:
            app[field] = environment[key]
    if "CK_PORT" in environment:
        ck["port"] = environment["CK_PORT"]
    if "CK_SECURE" in environment:
        ck["secure"] = environment["CK_SECURE"]

    protocol_override = environment.get("CK_PROTOCOL")
    if protocol_override is None:
        raw_app = config.get("app", {})
        raw_ck = raw_app.get("default-ck", {}) if isinstance(raw_app, dict) else {}
        raw_protocol = raw_ck.get("protocol") if isinstance(raw_ck, dict) else None
        placeholder = (
            isinstance(raw_protocol, str)
            and re.fullmatch(r"\$\{CK_PROTOCOL(?::[^{}]*)?\}", raw_protocol.strip())
        )
        if not placeholder:
            protocol_override = ck.get("protocol")

    normalize_clickhouse(
        ck,
        explicit_protocol=protocol_override,
        explicit_secure=environment.get("CK_SECURE"),
    )

    if profile == "test":
        if not environment.get("APP_SECRET_KEY"):
            raise ValueError("Set the existing 212 APP_SECRET_KEY in .env before packaging")
        app["secret-key"] = environment["APP_SECRET_KEY"]

    return app


def require_value(name, value, allow_empty=False):
    """Validate a required field without including its value in an exception."""
    if value is None or (not allow_empty and str(value).strip() == ""):
        raise ValueError("Missing existing configuration: " + name)
    if "CHANGE_ME" in str(value):
        raise ValueError("Unresolved CHANGE_ME placeholder in " + name)


def validate_deployment(app):
    """Validate all values that are written into the private archive."""
    metadata = app["metadata"]
    ck = app["default-ck"]
    management = app.get("ck-management", {})

    for name, value in (
        ("metadata.url", metadata.get("url")),
        ("metadata.username", metadata.get("username")),
        ("metadata.password", metadata.get("password")),
        ("secret-key", app.get("secret-key")),
        ("default-ck.host", ck.get("host")),
        ("default-ck.database", ck.get("database")),
        ("default-ck.username", ck.get("username")),
    ):
        require_value("app." + name, value)

    require_value("app.default-ck.password", ck.get("password"), allow_empty=True)
    management_enabled = parse_boolean(
        management.get("enabled", True), "CK_MANAGEMENT_ENABLED"
    )
    if management_enabled:
        require_value("app.ck-management.username", management.get("username"))
        require_value("app.ck-management.password", management.get("password"))
    metadata_target(metadata["url"])
    return metadata, ck


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", choices=("test", "dev"), default="test")
    parser.add_argument("--mode", choices=("external", "standalone"))
    parser.add_argument("--offline", action="store_true", help="Bundle Linux amd64 app/MySQL/ClickHouse images")
    args = parser.parse_args()

    os.umask(0o077)
    mode = args.mode or os.environ.get("DEPLOYMENT_MODE") or read_dotenv(ROOT / ".env").get("DEPLOYMENT_MODE", "external")
    if mode not in ("external", "standalone"):
        raise ValueError("DEPLOYMENT_MODE must be external or standalone")
    if mode == "standalone":
        bundled = credentials(ROOT)
        app = standalone_app(bundled)
    else:
        app = deployment_config(args.profile)
        bundled = credentials(ROOT, app.get("password"))
    metadata, ck = validate_deployment(app)

    values = {
        **bundled,
        "DEPLOYMENT_MODE": mode,
        "SPRING_PROFILES_ACTIVE": args.profile,
        "IMAGE_TAG": "212-" + datetime.now().strftime("%Y%m%d-%H%M%S"),
        "BIND_ADDRESS": "0.0.0.0",
        "HTTP_PORT": "17878",
        "TZ": "Asia/Shanghai",
        "JAVA_TOOL_OPTIONS": "-Xms256m -Xmx2g -Duser.timezone=Asia/Shanghai",
        "METADATA_URL": metadata["url"],
        "METADATA_USERNAME": metadata["username"],
        "METADATA_PASSWORD": metadata["password"],
        "APP_SECRET_KEY": app["secret-key"],
        "ADMIN_USER": app.get("user") or "admin",
        "ADMIN_PASSWORD": app.get("password") or "",
        "CK_HOST": ck["host"],
        "CK_PROTOCOL": ck["protocol"],
        "CK_PORT": ck["port"],
        "CK_DATABASE": ck["database"],
        "CK_USERNAME": ck["username"],
        "CK_PASSWORD": ck.get("password") or "",
        "CK_SECURE": str(ck["secure"]).lower(),
        "CK_MANAGEMENT_USERNAME": app.get("ck-management", {}).get("username", ""),
        "CK_MANAGEMENT_PASSWORD": app.get("ck-management", {}).get("password", ""),
        "CK_MANAGEMENT_ENABLED": str(
            parse_boolean(
                app.get("ck-management", {}).get("enabled", True),
                "CK_MANAGEMENT_ENABLED",
            )
        ).lower(),
        "CK_DATABASES": app.get("ck-databases", "ods_data,dwd_data"),
    }

    env_text = (
        "# Generated from application.yml + application-" + args.profile
        + ".yml; private deployment configuration.\n"
        + "\n".join(key + "=" + dotenv(value) for key, value in values.items())
        + "\n"
    )
    if "CHANGE_ME" in env_text:
        raise ValueError("Generated .env still contains a CHANGE_ME placeholder")
    env = env_text.encode("utf-8")

    required_roots = [
        "Dockerfile",
        "compose.yaml",
        "compose.standalone.yaml",
        "deploy.sh",
        "package.ps1",
        ".dockerignore",
        ".env.example",
        "pom.xml",
        "src",
        "web",
        "docker/application.yml",
        "docker/standalone",
        "docs/mysql",
    ]
    optional_roots = ["README.md", "docs"]
    roots = required_roots + [path for path in optional_roots if (ROOT / path).exists()]
    excluded = {
        "node_modules", "dist", "target", "data", "__pycache__", ".git", ".idea",
        ".vscode", "coverage",
    }

    def candidates(path):
        if path.is_file():
            yield path
            return
        for directory, dirs, names in os.walk(path):
            dirs[:] = sorted(name for name in dirs if name not in excluded)
            for name in names:
                yield Path(directory) / name

    files = []
    seen_files = set()
    for name in roots:
        path = ROOT / name
        if not path.exists():
            raise ValueError("Required packaging path missing: " + name)
        for item in candidates(path):
            relative = item.relative_to(ROOT)
            if not item.is_file() or any(part in excluded for part in relative.parts):
                continue
            if relative.parts[:2] == ("src", "test"):
                continue
            if item.is_symlink():
                raise ValueError("Unexpected symbolic link in package: " + str(relative))
            if (
                relative.as_posix().startswith("src/main/resources/application")
                or item.name.startswith(".env")
            ):
                continue
            if item.suffix in {".pyc", ".tsbuildinfo"}:
                continue
            if relative.as_posix() == "web/public/assets/release-info.json":
                continue  # Always regenerate the public release identity, never reuse a copied marker.
            if relative.as_posix() not in seen_files:
                files.append((relative.as_posix(), item))
                seen_files.add(relative.as_posix())

    release = ROOT / "release"
    release.mkdir(exist_ok=True)
    archive = release / "el-to-ck-212.tar.gz"
    temporary = release / "el-to-ck-212.tar.gz.tmp"
    packaged_at = int(time.time())

    def add(tar, name, data, mode, mtime=None):
        info = tarfile.TarInfo(name)
        info.size, info.mode = len(data), mode
        info.mtime = max(1, int(mtime if mtime is not None else packaged_at))
        tar.addfile(info, io.BytesIO(data))

    try:
        file_hashes = {}
        with tarfile.open(temporary, "w:gz", compresslevel=1) as tar:
            manifest = []
            for name, path in sorted(files):
                data = path.read_bytes()
                if name.endswith(".sh"):
                    data = data.replace(b"\r\n", b"\n")
                add(
                    tar,
                    name,
                    data,
                    0o755 if name.endswith(".sh") else 0o644,
                    path.stat().st_mtime,
                )
                digest = hashlib.sha256(data).hexdigest()
                file_hashes[name] = digest
                manifest.append(digest + "  " + name)

            # Vite copies public/assets into dist/assets; Spring already serves /assets/**.
            # The marker identifies the actual uploaded source bundle and contains no .env digest.
            info_name = "web/public/assets/release-info.json"
            info = release_info(file_hashes, packaged_at)
            info_data = (json.dumps(info, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
            add(tar, info_name, info_data, 0o644)
            file_hashes[info_name] = hashlib.sha256(info_data).hexdigest()
            manifest.append(file_hashes[info_name] + "  " + info_name)

            example = (ROOT / ".env.example").read_bytes().replace(b"\r\n", b"\n")
            add(tar, ".env.example", example, 0o644)
            manifest.append(hashlib.sha256(example).hexdigest() + "  .env.example")
            add(tar, ".env", env, 0o600)
            env_digest = hashlib.sha256(env).hexdigest()
            manifest.append(env_digest + "  .env")
            manifest_text = "\n".join(manifest) + "\n"
            add(tar, "release-files.sha256", manifest_text.encode("utf-8"), 0o600)

        temporary.chmod(0o600)
        with tarfile.open(temporary, "r:gz") as tar:
            if ".env" not in tar.getnames():
                raise ValueError("Archive is missing .env")
            env_member = tar.getmember(".env")
            env_stream = tar.extractfile(env_member)
            if env_stream is None:
                raise ValueError("Archive .env entry is unreadable")
            if env_stream.read() != env:
                raise ValueError("Archive .env content does not match generated configuration")
            if env_member.mode != 0o600:
                raise ValueError("Archive .env mode must be 0600")
            if "CHANGE_ME" in env.decode("utf-8"):
                raise ValueError("Archive .env contains CHANGE_ME")

            manifest_member = tar.getmember("release-files.sha256")
            if manifest_member.mode != 0o600:
                raise ValueError("Archive release-files.sha256 mode must be 0600")
            manifest_stream = tar.extractfile(manifest_member)
            if manifest_stream is None:
                raise ValueError("Archive release-files.sha256 is unreadable")
            manifest_hashes = {}
            for line in manifest_stream.read().decode("utf-8").splitlines():
                if "  " not in line:
                    raise ValueError("Archive manifest contains an invalid line")
                digest, name = line.split("  ", 1)
                if name in manifest_hashes:
                    raise ValueError("Archive manifest contains a duplicate entry: " + name)
                manifest_hashes[name] = digest
            if manifest_hashes.get(".env") != env_digest:
                raise ValueError("Archive manifest must contain the generated .env digest")

            names = tar.getnames()
            if not all(member.mtime > 0 for member in tar.getmembers()):
                raise ValueError("Archive contains a non-positive timestamp")
            if "src/main/resources/application.yml" in names:
                raise ValueError("Archive must not contain local application.yml")
            if any(name.startswith("src/main/resources/application-") for name in names):
                raise ValueError("Archive must not contain local application profile files")

            for name, expected_digest in file_hashes.items():
                member = tar.extractfile(name)
                if member is None:
                    raise ValueError("Archive member is unreadable: " + name)
                actual_digest = hashlib.sha256(member.read()).hexdigest()
                if actual_digest != expected_digest:
                    raise ValueError("Archive member failed readback: " + name)

        temporary.replace(archive)
        archive.chmod(0o600)
    finally:
        temporary.unlink(missing_ok=True)

    if args.offline:
        add_offline_images(archive, values["IMAGE_TAG"])
    digest = file_hash(archive)
    digest_file = archive.with_name(archive.name + ".sha256")
    digest_file.write_text(digest + "  " + archive.name + "\n", encoding="ascii")
    digest_file.chmod(0o644)

    mysql_summary, clickhouse_summary = target_summary(metadata, ck)
    print("Package ready: " + str(archive))
    print("Deployment mode:", mode, "| offline images:", args.offline)
    print("Release ID: " + info["releaseId"])
    print("Source SHA-256: " + info["sourceSha256"])
    print(mysql_summary)
    print(clickhouse_summary)
    print("Private archive: upload only to the deployment server.")


if __name__ == "__main__":
    main()
