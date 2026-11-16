#!/usr/bin/env bash
# Executed only on the recipient computer when Docker is absent. No existing
# Docker/Podman packages are removed; conflicts must be resolved explicitly.
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo 'Administrator privileges are required.' >&2; exit 1; }
source /etc/os-release
case "$ID" in ubuntu|debian) ;; *) echo 'Automatic Docker installation supports Ubuntu/Debian. Install Docker Engine + Compose v2 for this distribution.' >&2; exit 1 ;; esac
command -v systemctl >/dev/null || { echo 'A systemd host is required for automatic installation.' >&2; exit 1; }
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y ca-certificates curl
install -m 0755 -d /etc/apt/keyrings
curl --fail --silent --show-error --location --proto '=https' "https://download.docker.com/linux/$ID/gpg" -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
arch="$(dpkg --print-architecture)"
codename="${VERSION_CODENAME:?Missing distribution codename}"
cat > /etc/apt/sources.list.d/el-docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/$ID
Suites: $codename
Components: stable
Architectures: $arch
Signed-By: /etc/apt/keyrings/docker.asc
EOF
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker
