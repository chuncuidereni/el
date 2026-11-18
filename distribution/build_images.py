"""Build a credential-free, architecture-specific offline payload."""
import argparse
from datetime import datetime, timezone
import gzip
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

from common import DATABASE_IMAGES, FORMAT, archive_config_ids, schema_hash, sha256

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED = {"node_modules", "dist", "target", ".git", "__pycache__", "test"}


def command(args):
    subprocess.run(args, check=True)


def copy_source(source, target):
    for path in source.rglob("*"):
        relative = path.relative_to(source)
        if any(part in EXCLUDED for part in relative.parts) or path.name.startswith(".env"):
            continue
        if path.name.startswith("application") and path.suffix in (".yml", ".yaml", ".properties"):
            continue
        if path.name.endswith((".tsbuildinfo", ".pyc")):
            continue
        if path.is_symlink():
            raise RuntimeError("Source contains a symlink: " + str(relative))
        if path.is_file():
            destination = target / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, destination)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--arch", choices=("amd64", "arm64"), required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--prefer-local-databases", action="store_true", help="Use exact existing database images after architecture validation")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,80}", args.version):
        parser.error("version must be a Docker-tag-safe release identifier")
    args.output.mkdir(parents=True, exist_ok=True)
    if any(args.output.iterdir()):
        raise RuntimeError("Output directory must be empty: " + str(args.output))
    platform = "linux/" + args.arch
    image = "el2ck-distribution:" + args.version + "-" + args.arch
    # Only explicit source paths are staged. Never reuse the private 212 archive,
    # local .env, application profiles, database contents or old release output.
    with tempfile.TemporaryDirectory(prefix="el-public-build-") as temporary:
        staging = Path(temporary)
        for name in ("src", "web"):
            copy_source(ROOT / name, staging / name)
        for name in ("Dockerfile", "pom.xml", ".dockerignore", "docker/application.yml"):
            target = staging / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, target)
        release_info = staging / "web" / "public" / "assets" / "release-info.json"
        release_info.parent.mkdir(parents=True, exist_ok=True)
        release_info.write_text(json.dumps({"releaseId": args.version, "packagedAt": datetime.now(timezone.utc).isoformat(),
                                            "distribution": "self-installing"}) + "\n", encoding="utf-8")
        command(["docker", "build", "--platform", platform, "-t", image, str(staging)])
    tags = {"app": image, **DATABASE_IMAGES}
    images = {}
    for role, tag in tags.items():
        if role != "app":
            found = subprocess.run(["docker", "image", "inspect", tag], capture_output=True)
            if not args.prefer_local_databases or found.returncode:
                command(["docker", "pull", "--platform", platform, tag])
        details = json.loads(subprocess.check_output(["docker", "image", "inspect", tag]))[0]
        if details["Os"] != "linux" or details["Architecture"] != args.arch:
            raise RuntimeError("Wrong architecture for " + tag + "; pull the requested platform explicitly")
        # Layer digests are content-addressed and survive both classic and
        # containerd image stores; config/manifest IDs are not portable.
        layers = list((details.get("RootFS") or {}).get("Layers") or [])
        images[role] = {"tag": tag, "id": details["Id"], "layers": layers}
    # Stream compression: never materialize both a multi-GB tar and gzip file.
    image_file = args.output / "images.tar.gz"
    process = subprocess.Popen(["docker", "save", *tags.values()], stdout=subprocess.PIPE)
    try:
        with gzip.open(image_file, "wb", compresslevel=1) as output:
            shutil.copyfileobj(process.stdout, output, 1024 * 1024)
        if process.wait() != 0:
            raise RuntimeError("Docker image export failed")
    finally:
        process.stdout.close()
        if process.poll() is None:
            process.kill()
            process.wait()
    config_ids = archive_config_ids(image_file)
    for role, item in images.items():
        item["config_id"] = config_ids[item["tag"]]
    schema = args.output / "schema"
    (schema / "archive").mkdir(parents=True)
    for version in range(1, 12):
        matches = list((ROOT / "docs" / "mysql" / "archive").glob("V" + str(version) + "__*.sql"))
        if len(matches) != 1:
            raise RuntimeError("Missing or ambiguous bootstrap SQL version " + str(version))
        (schema / "archive" / matches[0].name).write_bytes(matches[0].read_bytes().replace(b"\r\n", b"\n"))
    for name in ("20261029_review_hardening.sql", "20261110_sync_run_logical_delete.sql"):
        (schema / name).write_bytes((ROOT / "docs" / "mysql" / name).read_bytes().replace(b"\r\n", b"\n"))
    # Persist with the hashed bootstrap configuration, so it survives moving or
    # uninstalling the EXE directory and cannot silently change between runs.
    (schema / "clickhouse-users.xml").write_bytes(
        (ROOT / "docker" / "standalone" / "clickhouse-users.xml").read_bytes().replace(b"\r\n", b"\n"))
    init = args.output / "mysql-init"
    init.mkdir()
    (init / "001-schema.sh").write_bytes((ROOT / "docker" / "standalone" / "mysql-init" / "001-schema.sh").read_bytes().replace(b"\r\n", b"\n"))
    (init / "001-schema.sh").chmod(0o755)
    runtime = {"format": FORMAT, "version": args.version, "architecture": args.arch, "images": images,
               "schema_sha256": schema_hash(args.output), "images_sha256": sha256(image_file)}
    (args.output / "runtime.json").write_text(json.dumps(runtime, indent=2) + "\n", encoding="utf-8")
    print("Public payload ready: " + str(args.output))


if __name__ == "__main__":
    main()
