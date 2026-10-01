set -eu
umask 077
unset DOCKER_CONTEXT BUILDX_BUILDER BUILDKIT_HOST
export DOCKER_HOST=unix:///var/run/docker.sock
docker() { command timeout -k 2s 10s docker "$@"; }
candidate=@CANDIDATE@
attempt=@ATTEMPT@
backup=@BACKUP@
deploy=@DEPLOY@
image=@IMAGE@
previous=@PREVIOUS@
container=@CONTAINER@
tag=@TAG@
stage=PRECHECK
switched=0
files_changed=0
migration_started=0
deployed=false
rolled_back=false
manual=false
test ! -L "$attempt" && test -d "$attempt"
chmod 700 "$attempt"
export DOCKER_CONFIG="$attempt/docker-config"
mkdir "$DOCKER_CONFIG"
test ! -L "$candidate/../.database-baseline.lock"
exec 9> "$candidate/../.database-baseline.lock"
flock -n 9 || exit 41
fingerprint() {
  for file in app.jar Dockerfile @COMPOSE_FILE@ @NGINX_FILE@ .env; do
    test ! -L "$deploy/$file" && test -s "$deploy/$file" || return 1
    sha256sum "$deploy/$file" | cut -d' ' -f1
  done | sha256sum | cut -d' ' -f1
}
app_id() { timeout -k 2s 10s @COMPOSE@ ps -q app; }
check_health() {
  wanted=$1
  limit=$(( $(date +%s) + 150 ))
  while test "$(date +%s)" -lt "$limit"; do
    cid=$(app_id) || return 1
    if test -n "$cid" && test "$(timeout -k 2s 10s docker inspect --format '{{.Image}}' "$cid")" = "$wanted"; then
      status=$(timeout -k 2s 10s docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "$cid") || return 1
      if test "$status" = healthy; then
        timeout -k 2s 10s @COMPOSE@ exec -T nginx nginx -t >/dev/null 2>&1 || return 1
        timeout -k 2s 10s @COMPOSE@ exec -T nginx nginx -s reload >/dev/null 2>&1 || return 1
        # A reload is asynchronous: tolerate a bounded handover, never accept a different running image.
        if curl --fail --silent --show-error --max-time 10 --output /dev/null @HEALTH@ >/dev/null 2>&1 \
          && curl --fail --silent --show-error --max-time 10 --output /dev/null @BUSINESS@ >/dev/null 2>&1; then return 0; fi
      fi
      if test "$status" = unhealthy; then return 1; fi
    fi
    sleep 3
  done
  return 1
}
finish() {
  code=$?
  trap - EXIT HUP INT TERM
  if test "$code" -ne 0 && test "$switched" -eq 1; then
    stage=ROLLBACK
    rollback_ok=1
    timeout -k 2s 10s docker image tag "$previous" "$tag" >/dev/null 2>&1 || rollback_ok=0
    if test "$files_changed" -eq 1; then
      install -m 644 "$attempt/previous.app.jar" "$deploy/app.jar" || rollback_ok=0
      install -m 644 "$attempt/previous.Dockerfile" "$deploy/Dockerfile" || rollback_ok=0
    fi
    timeout -k 5s 45s @COMPOSE@ up -d --no-deps --no-build --pull never --force-recreate app > "$attempt/rollback.log" 2>&1 || rollback_ok=0
    if test "$rollback_ok" -eq 1 && check_health "$previous" && test "$(fingerprint)" = @FINGERPRINT@; then rolled_back=true; stage=ROLLED_BACK
    else stage=RECOVERY_REQUIRED; manual=true; fi
  fi
  if test "$code" -ne 0 && test "$migration_started" -eq 1 && test "$switched" -eq 0; then manual=true; fi
  rm -f -- "$attempt/credentials" "$deploy/.agentstudio-app-@TOKEN@" "$deploy/.agentstudio-Dockerfile-@TOKEN@" || manual=true
  if test -f "$attempt/container.started" && docker container inspect "$container" >/dev/null 2>&1; then
    if test "$(docker inspect --format '{{index .Config.Labels "agentstudio.publishOwner"}}' "$container")" = "$container"; then
      timeout -k 2s 10s docker rm -f "$container" >/dev/null 2>&1 || manual=true
    else manual=true; fi
  fi
  if test "$manual" = true && test "$code" -eq 0; then code=45; deployed=false; stage=RECOVERY_REQUIRED; fi
  printf 'stage=%s\ndeployed=%s\nrolledBack=%s\nmanualInterventionRequired=%s\nexitCode=%s\npreviousImageId=%s\ncandidateImageId=%s\n' "$stage" "$deployed" "$rolled_back" "$manual" "$code" "$previous" "$image" > "$attempt/receipt.properties"
  printf 'AGENTSTUDIO_PUBLISH_STAGE=%s\nAGENTSTUDIO_DEPLOYED=%s\nAGENTSTUDIO_ROLLED_BACK=%s\nAGENTSTUDIO_MANUAL_REQUIRED=%s\nAGENTSTUDIO_MIGRATION_STARTED=%s\n' "$stage" "$deployed" "$rolled_back" "$manual" "$migration_started"
  exit "$code"
}
trap finish EXIT
trap 'exit 130' HUP INT TERM
cd "$deploy"
test "$(fingerprint)" = @FINGERPRINT@ || exit 42
timeout -k 2s 15s @COMPOSE@ config --quiet > "$attempt/compose-validation.log" 2>&1 || exit 42
cid=$(app_id)
test -n "$cid" || exit 42
test "$(docker inspect --format '{{.Image}}' "$cid")" = "$previous" || exit 42
test "$(docker inspect --format '{{.Config.Image}}' "$cid")" = "$tag" || exit 42
test "$(docker image inspect --format '{{.Id}}' "$tag")" = "$previous" || exit 42
test "$(docker inspect --format '{{.State.Health.Status}}' "$cid")" = healthy || exit 42
curl --fail --silent --max-time 10 --output /dev/null @HEALTH@ || exit 42
for file in app.jar Dockerfile compose.yml nginx.conf manifest.properties SHA256SUMS; do
  test ! -L "$candidate/$file" && test -s "$candidate/$file" || exit 42
done
cd "$candidate"
printf '%s  %s\n' @MANIFEST@ manifest.properties @ARTIFACT@ app.jar @DOCKERFILE@ Dockerfile @COMPOSE_SHA@ compose.yml @NGINX_SHA@ nginx.conf | sha256sum -c - >/dev/null || exit 42
# Infrastructure changes require a separate approval flow, not an implicit application release.
cmp -s "$candidate/compose.yml" "$deploy/"@COMPOSE_FILE@ || exit 42
cmp -s "$candidate/nginx.conf" "$deploy/"@NGINX_FILE@ || exit 42
cd "$backup"
test ! -e FAILED || exit 42
for file in database.sql uploads.tar.gz app.jar Dockerfile compose.yml nginx.conf .env images.json services.json manifest.properties manifest.sha256 SHA256SUMS; do
  test ! -L "$file" && test -s "$file" || exit 42
done
printf '%s  manifest.properties\n' @BACKUP_SHA@ | sha256sum -c - >/dev/null || exit 42
awk 'NF!=2 || length($1)!=64 || $1 ~ /[^0-9a-f]/ {exit 1} {if ($2!="database.sql" && $2!="uploads.tar.gz" && $2!="app.jar" && $2!="Dockerfile" && $2!="compose.yml" && $2!="nginx.conf" && $2!=".env" && $2!="images.json" && $2!="services.json") exit 1; if (seen[$2]++) exit 1} END {if (NR!=9) exit 1}' SHA256SUMS || exit 42
sha256sum -c SHA256SUMS >/dev/null || exit 42
gzip -t uploads.tar.gz || exit 42
created=$(sed -n 's/^createdAt=//p' manifest.properties)
age=$(( $(date -u +%s) - $(date -u -d "$created" +%s) ))
test "$age" -ge 0 && test "$age" -le 1800 || exit 43
cmp -s app.jar "$deploy/app.jar" && cmp -s Dockerfile "$deploy/Dockerfile" && cmp -s compose.yml "$deploy/"@COMPOSE_FILE@ && cmp -s nginx.conf "$deploy/"@NGINX_FILE@ && cmp -s .env "$deploy/.env" || exit 42
test "$(docker image inspect --format '{{.Id}}' "$image")" = "$image" || exit 42
test "$(docker image inspect --format '{{index .Config.Labels "agentstudio.releaseId"}}' "$image")" = @RELEASE@ || exit 42
test "$(docker image inspect --format '{{index .Config.Labels "agentstudio.manifestSha256"}}' "$image")" = @MANIFEST@ || exit 42
if docker container inspect "$container" >/dev/null 2>&1; then exit 44; fi
cp "$deploy/app.jar" "$attempt/previous.app.jar"
cp "$deploy/Dockerfile" "$attempt/previous.Dockerfile"
cd "$deploy"
stage=CANDIDATE_RUNTIME
: > "$attempt/container.started"
probe_exit=0
timeout -k 5s 20s docker run --rm --pull=never --name "$container" --label "agentstudio.publishOwner=$container" \
  --network none --user 65534:65534 --read-only --tmpfs /tmp:rw,noexec,nosuid,size=128m \
  --cap-drop ALL --security-opt no-new-privileges --memory 512m --memory-swap 512m --cpus 0.5 \
  --entrypoint java "$image" -Xmx128m -Dloader.main=com.mylove.database.DatabaseReleaseMain \
  -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher invalid-sha \
  > "$attempt/release-runtime.log" 2>&1 || probe_exit=$?
test "$probe_exit" -eq 1 && grep -Fxq 'MIGRATION_NOT_CONFIRMED=IllegalArgumentException' "$attempt/release-runtime.log" || exit 48
timeout -k 2s 10s @COMPOSE@ exec -T mysql sh -c 'printf "%s\0" "$MYSQL_DATABASE" "$MYSQL_USER" "$MYSQL_PASSWORD"' > "$attempt/credentials"
stage=DATABASE_COMPATIBILITY
migration_started=1
timeout -k 5s 120s docker run --rm -i --pull=never --name "$container" --label "agentstudio.publishOwner=$container" \
  --network @NETWORK@ --user 65534:65534 --read-only --tmpfs /tmp:rw,noexec,nosuid,size=128m \
  --cap-drop ALL --security-opt no-new-privileges --memory 512m --memory-swap 512m --cpus 0.5 \
  --entrypoint java "$image" -Xmx256m -XX:MaxMetaspaceSize=128m \
  -Dloader.main=com.mylove.database.DatabaseReleaseMain -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher @SCHEMA@ \
  < "$attempt/credentials" > "$attempt/migration.log" 2>&1 || { grep -E '^MIGRATION_NOT_CONFIRMED=[A-Za-z]+$' "$attempt/migration.log" || true; exit 46; }
grep -Eq '^AGENTSTUDIO_MIGRATION_VERIFIED=[0-9]+$' "$attempt/migration.log" || exit 46
migrations=$(sed -n 's/^AGENTSTUDIO_MIGRATION_VERIFIED=//p' "$attempt/migration.log")
case "$migrations" in ''|*[!0-9]*) exit 46;; esac
printf 'AGENTSTUDIO_MIGRATIONS_EXECUTED=%s\n' "$migrations"
test "$(fingerprint)" = @FINGERPRINT@ || exit 42
test "$(docker inspect --format '{{.Image}}' "$(app_id)")" = "$previous" || exit 42
stage=APP_SWITCH
switched=1
timeout -k 2s 10s docker image tag "$image" "$tag" >/dev/null 2>&1
timeout -k 5s 45s @COMPOSE@ up -d --no-deps --no-build --pull never --force-recreate app > "$attempt/switch.log" 2>&1
stage=HEALTH_VERIFY
check_health "$image" || exit 47
stage=ARTIFACT_SYNC
files_changed=1
install -m 644 "$candidate/app.jar" "$deploy/.agentstudio-app-@TOKEN@"
install -m 644 "$candidate/Dockerfile" "$deploy/.agentstudio-Dockerfile-@TOKEN@"
mv -f -- "$deploy/.agentstudio-app-@TOKEN@" "$deploy/app.jar"
mv -f -- "$deploy/.agentstudio-Dockerfile-@TOKEN@" "$deploy/Dockerfile"
cmp -s "$candidate/app.jar" "$deploy/app.jar" && cmp -s "$candidate/Dockerfile" "$deploy/Dockerfile"
check_health "$image" || exit 47
stage=DEPLOYED
deployed=true
