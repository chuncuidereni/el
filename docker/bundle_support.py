"""Standalone credentials and offline images. Never print private values."""
import hashlib
import io
import json
import os
from pathlib import Path
import secrets
import subprocess
import tarfile
import tempfile
import time

import yaml

SECRET_KEYS = (
    "BUNDLED_MYSQL_ROOT_PASSWORD", "BUNDLED_MYSQL_PASSWORD",
    "BUNDLED_CK_PASSWORD", "BUNDLED_APP_SECRET_KEY", "BUNDLED_ADMIN_PASSWORD",
)


def credentials(root, admin_password=None):
    """Reuse credentials across releases; an incomplete existing file is an error."""
    path = root / ".env.standalone"
    if path.exists():
        values = {}
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            if not line or line.startswith("#"):
                continue
            key, value = line.split("=", 1)
            values[key] = value.strip("'")
        if set(values) != set(SECRET_KEYS) or any(not v for v in values.values()):
            raise ValueError("Invalid .env.standalone; restore the original credentials file")
        return values
    values = {key: secrets.token_hex(32) for key in SECRET_KEYS}
    if admin_password and all(c not in admin_password for c in "'\n\r\\"):
        values["BUNDLED_ADMIN_PASSWORD"] = admin_password
    with path.open("x", encoding="utf-8") as stream:
        stream.write("# Private standalone credentials. Keep for every subsequent release.\n")
        for key, value in values.items():
            stream.write(key + "='" + value + "'\n")
    path.chmod(0o600)
    return values


def standalone_app(values):
    return {
        "metadata": {"url": "jdbc:mysql://mysql:3306/eltock?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true",
                     "username": "el2ck", "password": values["BUNDLED_MYSQL_PASSWORD"]},
        "default-ck": {"host": "clickhouse", "port": 8123, "protocol": "http",
                       "database": "default", "username": "el2ck_admin",
                       "password": values["BUNDLED_CK_PASSWORD"], "secure": False},
        "ck-management": {"enabled": True, "username": "el2ck_admin", "password": values["BUNDLED_CK_PASSWORD"]},
        "secret-key": values["BUNDLED_APP_SECRET_KEY"], "user": "admin",
        "password": values["BUNDLED_ADMIN_PASSWORD"], "ck-databases": "ods_data,dwd_data",
    }


def file_hash(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def add_offline_images(archive, image_tag):
    """Build from the release, bundle all three Linux amd64 images, verify readback."""
    with tempfile.TemporaryDirectory(prefix="offline-", dir=archive.parent) as temporary:
        root = Path(temporary)
        source = root / "source"
        source.mkdir()
        with tarfile.open(archive, "r:gz") as package:
            package.extractall(source, filter="data")
        app_image = "el-to-ck:" + image_tag
        subprocess.run(["docker", "build", "--platform", "linux/amd64", "-t", app_image, str(source)], check=True, stderr=subprocess.STDOUT)
        compose = yaml.safe_load((source / "compose.standalone.yaml").read_text(encoding="utf-8"))
        images = [app_image] + [compose["services"][name]["image"] for name in ("mysql", "clickhouse")]
        for image in images[1:]:
            subprocess.run(["docker", "pull", "--platform", "linux/amd64", image], check=True, stderr=subprocess.STDOUT)
        identities = []
        for image in images:
            info = json.loads(subprocess.check_output(["docker", "image", "inspect", image], text=True))[0]
            if info["Os"] != "linux" or info["Architecture"] != "amd64":
                raise ValueError("Offline delivery requires Linux amd64 images")
            identities.append({"tag": image, "id": info["Id"], "digests": info.get("RepoDigests", [])})
        image_tar = root / "images.tar"
        subprocess.run(["docker", "save", "-o", str(image_tar), *images], check=True, stderr=subprocess.STDOUT)
        lock = root / "images.json"
        lock.write_text(json.dumps(identities, indent=2) + "\n", encoding="utf-8")
        ids = root / "images.ids"
        # PowerShell packaging runs on Windows, deployment reads these records
        # with Bash. A CR suffix would become part of the Docker image tag.
        ids.write_text("".join(item["id"] + " " + item["tag"] + "\n" for item in identities), encoding="ascii", newline="\n")
        additions = {"docker/offline/images.tar": image_tar, "docker/offline/images.json": lock, "docker/offline/images.ids": ids}
        output = root / "complete.tar.gz"
        with tarfile.open(archive, "r:gz") as original, tarfile.open(output, "w:gz", compresslevel=1) as result:
            manifest = original.extractfile("release-files.sha256").read().decode("utf-8")
            for member in original:
                if member.name != "release-files.sha256":
                    result.addfile(member, original.extractfile(member))
            for name, path in additions.items():
                entry = tarfile.TarInfo(name)
                entry.size, entry.mode, entry.mtime = path.stat().st_size, 0o600, int(time.time())
                with path.open("rb") as stream:
                    result.addfile(entry, stream)
                manifest += file_hash(path) + "  " + name + "\n"
            data = manifest.encode("utf-8")
            entry = tarfile.TarInfo("release-files.sha256")
            entry.size, entry.mode, entry.mtime = len(data), 0o600, int(time.time())
            result.addfile(entry, io.BytesIO(data))
        with tarfile.open(output, "r:gz") as package:
            for name, path in additions.items():
                digest = hashlib.sha256()
                with package.extractfile(name) as stream:
                    for block in iter(lambda: stream.read(1024 * 1024), b""):
                        digest.update(block)
                if digest.hexdigest() != file_hash(path):
                    raise ValueError("Offline archive readback failed")
        os.replace(output, archive)
