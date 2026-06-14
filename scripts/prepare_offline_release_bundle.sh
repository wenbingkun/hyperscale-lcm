#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

DOCKER_NAMESPACE="${DOCKER_NAMESPACE:-}"
DOCKER_TAG="${DOCKER_TAG:-v0.1.0}"
OUTPUT_ROOT="$ROOT_DIR/.local/release-bundles"
PULL_IMAGES=true
INCLUDE_HELM=true
DRY_RUN=false

usage() {
  cat <<'USAGE'
Usage:
  scripts/prepare_offline_release_bundle.sh --namespace <dockerhub-user> [options]

Options:
  --namespace <name>  Docker namespace that owns lcm-core/lcm-satellite/lcm-frontend.
  --tag <tag>         Release image tag to bundle. Defaults to DOCKER_TAG or v0.1.0.
  --output <dir>      Output parent directory. Defaults to .local/release-bundles.
  --no-pull           Do not pull images; require all images to exist locally.
  --skip-helm         Do not package the Helm chart and dependency archives.
  --dry-run           Print the bundle plan without Docker/Helm access or file writes.
  -h, --help          Show this help.

Examples:
  DOCKER_NAMESPACE=my-dockerhub-user scripts/prepare_offline_release_bundle.sh
  scripts/prepare_offline_release_bundle.sh --namespace my-dockerhub-user --tag v0.1.0

The bundle is intended for a restricted target host:
  1. Copy the generated directory to the target host.
  2. Run ./load-images.sh inside that directory.
  3. Follow documentation/runbooks/offline-deployment.md.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --namespace)
      DOCKER_NAMESPACE="${2:-}"
      shift 2
      ;;
    --tag)
      DOCKER_TAG="${2:-}"
      shift 2
      ;;
    --output)
      OUTPUT_ROOT="${2:-}"
      shift 2
      ;;
    --no-pull)
      PULL_IMAGES=false
      shift
      ;;
    --skip-helm)
      INCLUDE_HELM=false
      shift
      ;;
    --dry-run)
      DRY_RUN=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "$DOCKER_NAMESPACE" ]]; then
  echo "DOCKER namespace is required. Pass --namespace or set DOCKER_NAMESPACE." >&2
  exit 2
fi

images=(
  "$DOCKER_NAMESPACE/lcm-core:$DOCKER_TAG"
  "$DOCKER_NAMESPACE/lcm-satellite:$DOCKER_TAG"
  "$DOCKER_NAMESPACE/lcm-frontend:$DOCKER_TAG"
  "postgres:15-alpine"
  "redis:7-alpine"
  "confluentinc/cp-zookeeper:7.5.0"
  "confluentinc/cp-kafka:7.5.0"
  "jaegertracing/all-in-one:1.50"
  "prom/prometheus:v2.53.5"
  "prom/alertmanager:v0.28.1"
  "grafana/grafana:11.0.0"
)

if [[ "$DRY_RUN" == true ]]; then
  cat <<EOF
Hyperscale LCM offline release bundle plan
docker_namespace=$DOCKER_NAMESPACE
docker_tag=$DOCKER_TAG
output_root=$OUTPUT_ROOT
pull_images=$PULL_IMAGES
include_helm=$INCLUDE_HELM

images:
EOF
  for image in "${images[@]}"; do
    echo "  - $image"
  done
  exit 0
fi

for required in docker tar sha256sum; do
  if ! command -v "$required" >/dev/null 2>&1; then
    echo "Required command not found: $required" >&2
    exit 2
  fi
done

mkdir -p "$OUTPUT_ROOT"
OUTPUT_ROOT=$(cd "$OUTPUT_ROOT" && pwd)

timestamp=$(date -u +%Y%m%dT%H%M%SZ)
bundle_name="hyperscale-lcm-${DOCKER_TAG}-${timestamp}"
bundle_dir="$OUTPUT_ROOT/$bundle_name"
images_dir="$bundle_dir/images"
mkdir -p "$images_dir"

safe_image_name() {
  printf '%s' "$1" | tr '/:@' '___'
}

manifest="$bundle_dir/manifest.txt"
manifest_env="$bundle_dir/manifest.env"
checksums="$bundle_dir/SHA256SUMS"
: >"$manifest"
: >"$checksums"

{
  echo "Hyperscale LCM offline release bundle"
  echo "created_utc=$timestamp"
  echo "docker_namespace=$DOCKER_NAMESPACE"
  echo "docker_tag=$DOCKER_TAG"
  echo
  echo "images:"
  for image in "${images[@]}"; do
    echo "  - $image"
  done
} >>"$manifest"

{
  echo "DOCKER_NAMESPACE=$DOCKER_NAMESPACE"
  echo "DOCKER_TAG=$DOCKER_TAG"
} >"$manifest_env"

(
  cd "$bundle_dir"
  sha256sum manifest.txt manifest.env >>"$checksums"
)

echo "Preparing bundle: $bundle_dir"

for image in "${images[@]}"; do
  if [[ "$PULL_IMAGES" == true ]]; then
    echo "Pulling $image"
    docker pull "$image"
  fi

  if ! docker image inspect "$image" >/dev/null 2>&1; then
    echo "Image is not available locally: $image" >&2
    echo "Re-run without --no-pull on a host with registry access, or pre-load this image." >&2
    exit 1
  fi

  image_tar="$images_dir/$(safe_image_name "$image").tar"
  echo "Saving $image -> $image_tar"
  docker save "$image" -o "$image_tar"
  (
    cd "$bundle_dir"
    sha256sum "images/$(basename "$image_tar")" >>"$checksums"
  )
done

if [[ "$INCLUDE_HELM" == true ]]; then
  if command -v helm >/dev/null 2>&1; then
    echo "Building Helm chart dependencies"
    helm dependency build "$ROOT_DIR/helm/hyperscale-lcm"
  else
    echo "helm not found; packaging existing chart files only" >&2
  fi

  helm_deps=("$ROOT_DIR"/helm/hyperscale-lcm/charts/*.tgz)
  if [[ ! -e "${helm_deps[0]}" ]]; then
    echo "Helm dependency archives are missing under helm/hyperscale-lcm/charts." >&2
    echo "Run with helm available on a connected host, or pass --skip-helm." >&2
    exit 1
  fi

  chart_tar="$bundle_dir/helm-hyperscale-lcm-${DOCKER_TAG}.tgz"
  echo "Packaging Helm chart -> $chart_tar"
  tar -C "$ROOT_DIR/helm" -czf "$chart_tar" hyperscale-lcm
  (
    cd "$bundle_dir"
    sha256sum "$(basename "$chart_tar")" >>"$checksums"
  )
fi

cat >"$bundle_dir/load-images.sh" <<'LOAD_IMAGES'
#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

if ! command -v docker >/dev/null 2>&1; then
  echo "docker is required on the target host." >&2
  exit 2
fi

if command -v sha256sum >/dev/null 2>&1 && [[ -f "$SCRIPT_DIR/SHA256SUMS" ]]; then
  (
    cd "$SCRIPT_DIR"
    sha256sum -c SHA256SUMS
  )
fi

for image_tar in "$SCRIPT_DIR"/images/*.tar; do
  echo "Loading $image_tar"
  docker load -i "$image_tar"
done

echo "Images loaded."
LOAD_IMAGES

chmod +x "$bundle_dir/load-images.sh"
(
  cd "$bundle_dir"
  sha256sum load-images.sh >>"$checksums"
)

echo
echo "Bundle ready: $bundle_dir"
echo "Next: copy this directory to the restricted host and run ./load-images.sh."
