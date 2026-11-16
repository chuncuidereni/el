#!/usr/bin/env bash
# Unified deployment entry point for the 212 server. Secrets are read only from
# private files and are never accepted on the command line or printed.
set -Eeuo pipefail
umask 077
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

rollback_env_keys=(METADATA_URL METADATA_USERNAME METADATA_PASSWORD APP_SECRET_KEY ADMIN_USER ADMIN_PASSWORD CK_HOST CK_PROTOCOL CK_PORT CK_DATABASE CK_USERNAME CK_PASSWORD CK_SECURE BIND_ADDRESS HTTP_PORT)

action="${1:-up}"
case "$action" in
  up|build|start|stop|restart|status|logs|check|preflight|rollback) ;;
  *) echo "Usage: bash deploy.sh {up|build|start|stop|restart|status|logs|check|preflight|rollback}" >&2; exit 2 ;;
esac

command -v docker >/dev/null || { echo 'Docker is required.' >&2; exit 1; }
docker compose version >/dev/null

# The deployment and rollback paths depend on these Compose v2 flags. Check the
# actual CLI capabilities before a potentially long build or before stopping the
# current service, so an old Compose release cannot leave the host half-upgraded.
require_compose_features() {
  local help
  help="$(docker compose up --help 2>&1)" || {
    echo 'Unable to inspect docker compose up options.' >&2
    return 1
  }
  grep -Eq -- '(^|[[:space:]])--wait([[:space:]]|$)' <<<"$help" || {
    echo 'Docker Compose is too old: docker compose up --wait is required.' >&2
    return 1
  }
  grep -Eq -- '(^|[[:space:]])--wait-timeout([[:space:]]|$)' <<<"$help" || {
    echo 'Docker Compose is too old: docker compose up --wait-timeout is required.' >&2
    return 1
  }
}
require_compose_features

validate_private_env() {
  if [[ ! -f .env ]]; then
    echo 'Missing packaged .env. Extract a new release archive into a new directory.' >&2
    return 1
  fi
  if [[ "$(stat -c '%a' .env)" != "600" ]]; then
    echo '.env must have mode 0600. Re-extract the archive and keep its file modes.' >&2
    return 1
  fi
  if grep -q 'CHANGE_ME' .env; then
    echo 'Packaged .env contains CHANGE_ME. Repackage on the build machine; do not edit it on the server.' >&2
    return 1
  fi
}

verify_release() {
  if [[ -f release-files.sha256 ]]; then
    if ! awk '{print $2}' release-files.sha256 | grep -Fxq '.env'; then
      echo 'release-files.sha256 is missing the .env digest; repackage with package.ps1.' >&2
      return 1
    fi
    # GNU coreutils and BusyBox both support -c; BusyBox has no --status.
    # Suppress per-file output (the manifest also includes private configuration).
    sha256sum -c release-files.sha256 >/dev/null 2>&1 || {
      echo 'Release checksum failed. Re-extract an intact archive into a new directory.' >&2
      return 1
    }
  elif [[ -d .git ]]; then
    echo 'Working checkout: no release manifest; verify the archive checksum when deploying a package.'
  else
    echo 'Missing release-files.sha256. Repackage with package.ps1.' >&2
    return 1
  fi
}

if [[ "$action" != rollback ]]; then
  validate_private_env
  # Never source dotenv: it is data, not executable shell code. A caller may
  # select a mode explicitly without editing the checksum-protected package.
  packaged_mode="$(sed -n "s/^DEPLOYMENT_MODE='\(external\|standalone\)'$/\1/p" .env)"
  deployment_mode="${DEPLOYMENT_MODE:-${packaged_mode:-external}}"
  case "$deployment_mode" in external|standalone) ;; *) echo 'DEPLOYMENT_MODE must be external or standalone.' >&2; exit 1 ;; esac
  compose=(docker compose --env-file .env -f compose.yaml)
  if [[ "$deployment_mode" == standalone ]]; then
    compose+=(-f compose.standalone.yaml)
  fi
  "${compose[@]}" config --quiet
  echo "Deployment mode: $deployment_mode"
fi

prepare_images() {
  if [[ -f docker/offline/images.tar ]]; then
    echo 'Loading bundled Linux amd64 images (no registry or build required).'
    docker load -i docker/offline/images.tar
    while IFS=' ' read -r expected image; do
      image="${image%$'\r'}"
      [[ -n "$expected" && -n "$image" ]] || return 1
      [[ "$(docker image inspect --format '{{.Id}}' "$image")" == "$expected" ]] || {
        echo 'Loaded image ID does not match the delivery manifest.' >&2; return 1;
      }
    done < docker/offline/images.ids
  else
    "${compose[@]}" build app
  fi
}

prepare_dependencies() {
  if [[ "$deployment_mode" == standalone ]]; then
    local pull_policy=missing
    [[ ! -f docker/offline/images.tar ]] || pull_policy=never
    "${compose[@]}" up -d --no-build --pull "$pull_policy" --wait --wait-timeout 300 mysql clickhouse
  fi
}

dotenv_quote() {
  local value="$1"
  value="${value//\'/\\\'}"
  printf "'%s'" "$value"
}

append_alias() {
  local source_key="$1" target_key="$2" line value
  grep -q "^${target_key}=" "$previous_env_file" && return 0
  line="$(grep -m1 "^${source_key}=" "$raw_env_file" || true)"
  [[ -n "$line" ]] || return 0
  value="${line#*=}"
  printf '%s=' "$target_key" >> "$previous_env_file"
  dotenv_quote "$value" >> "$previous_env_file"
  printf '\n' >> "$previous_env_file"
}

write_previous_env() {
  local container="$1" raw_env_file="$2" previous_env_file="$3"
  local line key value bind_address http_port
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" == *=* ]] || continue
    key="${line%%=*}"
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    value="${line#*=}"
    printf '%s=' "$key" >> "$previous_env_file"
    dotenv_quote "$value" >> "$previous_env_file"
    printf '\n' >> "$previous_env_file"
  done < "$raw_env_file"

  # Compose interpolation uses names different from the effective container
  # environment. Keep the effective APP_* lines and add aliases required to
  # recreate the old container with the same credentials.
  append_alias APP_METADATA_URL METADATA_URL
  append_alias APP_METADATA_USERNAME METADATA_USERNAME
  append_alias APP_METADATA_PASSWORD METADATA_PASSWORD
  append_alias APP_SECRETKEY APP_SECRET_KEY
  append_alias APP_USER ADMIN_USER
  append_alias APP_PASSWORD ADMIN_PASSWORD
  append_alias APP_DEFAULTCK_HOST CK_HOST
  append_alias APP_DEFAULTCK_PROTOCOL CK_PROTOCOL
  append_alias APP_DEFAULTCK_PORT CK_PORT
  append_alias APP_DEFAULTCK_DATABASE CK_DATABASE
  append_alias APP_DEFAULTCK_USERNAME CK_USERNAME
  append_alias APP_DEFAULTCK_PASSWORD CK_PASSWORD
  append_alias APP_DEFAULTCK_SECURE CK_SECURE

  bind_address="$(docker inspect --format '{{with (index .HostConfig.PortBindings "17878/tcp")}}{{with index . 0}}{{.HostIp}}{{end}}{{end}}' "$container")"
  http_port="$(docker inspect --format '{{with (index .HostConfig.PortBindings "17878/tcp")}}{{with index . 0}}{{.HostPort}}{{end}}{{end}}' "$container")"
  bind_address="${bind_address:-0.0.0.0}"
  http_port="${http_port:-17878}"
  if ! grep -q '^BIND_ADDRESS=' "$previous_env_file"; then
    printf 'BIND_ADDRESS=' >> "$previous_env_file"
    dotenv_quote "$bind_address" >> "$previous_env_file"
    printf '\n' >> "$previous_env_file"
  fi
  if ! grep -q '^HTTP_PORT=' "$previous_env_file"; then
    printf 'HTTP_PORT=' >> "$previous_env_file"
    dotenv_quote "$http_port" >> "$previous_env_file"
    printf '\n' >> "$previous_env_file"
  fi
}

remember_previous() {
  local container image healthy raw_env normalized_env image_tmp key
  # Keep the first verified baseline for this release directory. Later up/stop
  # calls must not replace it with the newly deployed image, otherwise rollback
  # would only return to the failed upgrade. A fresh release directory naturally
  # starts a new baseline.
  if [[ -e .deploy/previous.env || -e .deploy/previous-image ]]; then
    if [[ ! -f .deploy/previous.env || ! -f .deploy/previous-image ]]; then
      echo 'Rollback snapshot is incomplete; inspect .deploy before deploying.' >&2
      return 1
    fi
    if [[ "$(stat -c '%a' .deploy/previous.env)" != 600 || "$(stat -c '%a' .deploy/previous-image)" != 600 ]]; then
      echo 'Rollback snapshot permissions must be 0600.' >&2
      return 1
    fi
    image="$(cat .deploy/previous-image)"
    [[ "$image" =~ ^sha256:[a-f0-9]{64}$ ]] || { echo 'Invalid recorded rollback image ID.' >&2; return 1; }
    docker image inspect "$image" >/dev/null || { echo 'Recorded rollback image is no longer present locally.' >&2; return 1; }
    return 0
  fi

  container="$("${compose[@]}" ps -aq app)"
  [[ -n "$container" ]] || return 0

  image="$(docker inspect --format '{{.Image}}' "$container")"
  healthy="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container")"
  # Capture the current effective environment when it is the only rollback
  # state available. On a retry against an unhealthy new container, retain the
  # last known-good snapshot instead of overwriting it.
  if [[ "$healthy" != healthy && "$healthy" != running ]] && \
     [[ -f .deploy/previous.env && -f .deploy/previous-image ]]; then
    return 0
  fi
  [[ "$image" =~ ^sha256:[a-f0-9]{64}$ ]] || { echo 'Invalid previous image ID.' >&2; return 1; }

  mkdir -p .deploy
  chmod 700 .deploy
  raw_env="$(mktemp .deploy/previous.env.raw.XXXXXX)"
  normalized_env="$(mktemp .deploy/previous.env.XXXXXX)"
  image_tmp="$(mktemp .deploy/previous-image.XXXXXX)"

  if ! docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$container" > "$raw_env"; then
    rm -f "$raw_env" "$normalized_env" "$image_tmp"
    echo 'Unable to save the previous container environment.' >&2
    return 1
  fi
  chmod 600 "$raw_env" "$normalized_env" "$image_tmp"

  for key in APP_METADATA_URL APP_METADATA_USERNAME APP_METADATA_PASSWORD APP_SECRETKEY APP_DEFAULTCK_HOST APP_DEFAULTCK_PORT APP_DEFAULTCK_USERNAME; do
    if ! grep -q "^${key}=" "$raw_env"; then
      rm -f "$raw_env" "$normalized_env" "$image_tmp"
      echo "Previous container is missing required environment key ${key}." >&2
      return 1
    fi
  done

  write_previous_env "$container" "$raw_env" "$normalized_env"
  for key in "${rollback_env_keys[@]}"; do
    if ! grep -q "^${key}=" "$normalized_env"; then
      rm -f "$raw_env" "$normalized_env" "$image_tmp"
      echo "Unable to create a complete rollback snapshot: missing ${key}." >&2
      return 1
    fi
  done
  printf '%s\n' "$image" > "$image_tmp"
  chmod 600 "$normalized_env" "$image_tmp"
  mv -f "$normalized_env" .deploy/previous.env
  mv -f "$image_tmp" .deploy/previous-image
  rm -f "$raw_env"
  chmod 600 .deploy/previous.env .deploy/previous-image
}

# A one-off process checks image contents and both data dependencies without
# starting Web or schedulers. Check output is suppressed because driver errors
# may include connection details; only fixed, credential-free messages are shown.
preflight() {
  mkdir -p .deploy
  chmod 700 .deploy

  "${compose[@]}" run --rm --no-deps --entrypoint sh app -c '
    test -s /app/app.jar || { echo "Missing /app/app.jar in the image." >&2; exit 1; }
    test -s /app/web/dist/index.html || { echo "Missing /app/web/dist/index.html in the image." >&2; exit 1; }
    for path in /app/data /app/data/logs /app/data/uploads /app/data/sync-resume /app/data/duckdb; do
      mkdir -p "$path" || {
        echo "Cannot create data path $path as UID 10001; repair volume ownership before deploying." >&2
        exit 1
      }
      probe="$path/.el2ck-write-probe-$$"
      (umask 077 && : > "$probe") || {
        echo "Data path $path is not writable by UID 10001; repair ownership before deploying." >&2
        exit 1
      }
      rm -f "$probe" || {
        echo "Data path $path cannot remove temporary files; repair ownership before deploying." >&2
        exit 1
      }
    done'

  local metadata_log clickhouse_log metadata_pid clickhouse_pid
  local metadata_status=0 clickhouse_status=0
  metadata_log="$(mktemp .deploy/preflight-metadata.XXXXXX)"
  clickhouse_log="$(mktemp .deploy/preflight-clickhouse.XXXXXX)"
  chmod 600 "$metadata_log" "$clickhouse_log"

  "${compose[@]}" run --rm --no-deps --entrypoint java app \
    -Dloader.main=com.el2ck.repository.metadata.MetadataCheckCommand \
    -cp /app/app.jar org.springframework.boot.loader.PropertiesLauncher \
    > "$metadata_log" 2>&1 &
  metadata_pid=$!

  "${compose[@]}" run --rm --no-deps --entrypoint java app \
    -Dloader.main=com.el2ck.ckpool.ClickHouseCheckCommand \
    -cp /app/app.jar org.springframework.boot.loader.PropertiesLauncher \
    > "$clickhouse_log" 2>&1 &
  clickhouse_pid=$!

  wait "$metadata_pid" || metadata_status=$?
  wait "$clickhouse_pid" || clickhouse_status=$?

  if [[ "$metadata_status" -ne 0 ]]; then
    # Only the validator's structure summary is safe to show. Never print the
    # full log because driver errors may contain connection details or secrets.
    schema_error="$(grep -m1 '^MySQL 元数据结构检查失败' "$metadata_log" || true)"
    if [[ -n "$schema_error" ]]; then
      printf '%s\n' "$schema_error" >&2
    else
      echo 'Metadata preflight failed. Check MySQL connectivity and the required schema.' >&2
    fi
  fi
  if [[ "$clickhouse_status" -ne 0 ]]; then
    echo 'ClickHouse preflight failed. Check CK connectivity, protocol/port, and credentials.' >&2
  fi
  rm -f "$metadata_log" "$clickhouse_log"
  [[ "$metadata_status" -eq 0 && "$clickhouse_status" -eq 0 ]]
}

# Delete sync-task files left in the data volume (source snapshots, SFTP download
# cache, xlsx cache, rotated logs) and the matching el_sync_checkpoints rows.
# The app is stopped before this runs, so stale 'running' rows must not block it.
# Set CLEAN_SYNC_CACHE_ON_DEPLOY=false to keep the resume caches across deploys.
cleanup_sync_cache() {
  if [[ "${CLEAN_SYNC_CACHE_ON_DEPLOY:-true}" == "false" ]]; then
    echo 'Sync cache cleanup skipped (CLEAN_SYNC_CACHE_ON_DEPLOY=false).'
    return 0
  fi
  echo 'Cleaning sync task caches in the data volume...'
  if ! "${compose[@]}" run --rm --no-deps -e SYNC_CACHE_CLEANUP_FORCE=true --entrypoint java app \
      -Dloader.main=com.el2ck.sync.SyncCacheCleanupCommand \
      -cp /app/app.jar org.springframework.boot.loader.PropertiesLauncher; then
    # A cleanup failure must not block the deployment; the application's own
    # scheduled cleanup remains as a fallback.
    echo 'WARNING: sync cache cleanup failed; continuing deployment.' >&2
  fi
}

activate() {
  remember_previous
  # Stop first: the cleanup command deletes files inside the data volume and must
  # not race a running sync task that still holds them open.
  "${compose[@]}" stop app >/dev/null 2>&1 || true
  cleanup_sync_cache
  if ! "${compose[@]}" up -d --no-build --no-deps --pull never --wait --wait-timeout 300 app; then
    echo 'Deployment did not become healthy. Inspect logs; use bash deploy.sh rollback to restore the previous image and environment.' >&2
    return 1
  fi
}

case "$action" in
  up) verify_release; prepare_images; prepare_dependencies; preflight; activate ;;
  build) verify_release; prepare_images ;;
  start) verify_release; prepare_dependencies; preflight; activate ;;
  stop) verify_release; remember_previous; "${compose[@]}" stop app ;;
  restart)
    verify_release
    prepare_dependencies
    preflight
    # Restart the already active image in place. Do not call activate(): that
    # would overwrite the saved pre-upgrade rollback baseline after a successful
    # upgrade. Stop first so the cleanup command does not race a running task.
    "${compose[@]}" stop app >/dev/null 2>&1 || true
    cleanup_sync_cache
    "${compose[@]}" up -d --no-build --no-deps --pull never --no-recreate --wait --wait-timeout 300 app
    ;;
  status) "${compose[@]}" ps ;;
  logs) "${compose[@]}" logs --tail=200 -f app ;;
  check) verify_release; echo 'Compose configuration and release checksums are valid. Run preflight after building the image.' ;;
  preflight) verify_release; prepare_dependencies; preflight ;;
  rollback)
    verify_release
    previous_env=".deploy/previous.env"
    previous_image_file=".deploy/previous-image"
    [[ -f "$previous_env" ]] || { echo 'No previous environment recorded in this release directory.' >&2; exit 1; }
    [[ -f "$previous_image_file" ]] || { echo 'No previous image recorded in this release directory.' >&2; exit 1; }
    [[ "$(stat -c '%a' "$previous_env")" == "600" ]] || { echo '.deploy/previous.env must have mode 0600.' >&2; exit 1; }
    [[ "$(stat -c '%a' "$previous_image_file")" == "600" ]] || { echo '.deploy/previous-image must have mode 0600.' >&2; exit 1; }
    if grep -q 'CHANGE_ME' "$previous_env"; then
      echo 'Rollback environment contains an unresolved CHANGE_ME placeholder.' >&2
      exit 1
    fi
    previous_image="$(cat "$previous_image_file")"
    [[ "$previous_image" =~ ^sha256:[a-f0-9]{64}$ ]] || { echo 'Invalid rollback image ID.' >&2; exit 1; }
    docker image inspect "$previous_image" >/dev/null
    for key in "${rollback_env_keys[@]}"; do
      grep -q "^${key}=" "$previous_env" || { echo "Rollback environment is missing ${key}." >&2; exit 1; }
    done

    printf 'services:\n  app:\n    image: %s\n' "$previous_image" > .deploy/rollback.yaml
    if grep -Fxq "DEPLOYMENT_MODE='standalone'" "$previous_env"; then
      printf '    environment:\n      DEPLOYMENT_MODE: standalone\n    volumes:\n      - standalone-app-data:/app/data\nvolumes:\n  standalone-app-data:\n' >> .deploy/rollback.yaml
    fi
    chmod 600 .deploy/rollback.yaml
    rollback_compose=(docker compose --env-file .deploy/previous.env -f compose.yaml -f .deploy/rollback.yaml)
    "${rollback_compose[@]}" config --quiet
    "${rollback_compose[@]}" up -d --no-build --no-deps --pull never --wait --wait-timeout 300 app
    ;;
esac
