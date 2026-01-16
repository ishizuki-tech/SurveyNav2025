#!/usr/bin/env bash
# ============================================================
# ✅ Whisper models downloader — macOS Bash 3.2 compatible
# ------------------------------------------------------------
# • Works on /bin/bash 3.2 (no associative arrays)
# • Atomic writes via *.part → mv
# • Resume (-C -), retries, timeouts, backoff
# • Debug: DEBUG=1
# • Env overrides:
#     MODEL_DIR / BASE_URL (or MODEL_URL) / MODEL_NAMES
#     NDIZI_GGML_Q4_URL / NDIZI_GGML_Q5_URL
#     CURL_CONNECT_TIMEOUT / CURL_MAX_TIME / CURL_RETRY / CURL_RETRY_DELAY
# ============================================================

set -Eeuo pipefail

# --- Config (env-overridable) --------------------------------

MODEL_DIR="${MODEL_DIR:-src/main/assets/models}"

# Prefer BASE_URL, but allow MODEL_URL for backward compatibility
BASE_URL="${BASE_URL:-${MODEL_URL:-https://huggingface.co/ggerganov/whisper.cpp/resolve/main}}"

# space-separated list
# Recommended: use ndizi-q4_0 / ndizi-q5_0 aliases to avoid name collisions
MODEL_NAMES="${MODEL_NAMES:-ggml-tiny-q5_1.bin ggml-base-q5_1.bin ggml-small-q5_1.bin ndizi-q4_0.bin ndizi-q5_0.bin}"

# Model-specific URL overrides (support legacy env var names too)
NDIZI_GGML_Q4_URL="${NDIZI_GGML_Q4_URL:-${JACARANDA_Q4_URL:-https://huggingface.co/smutuvi/finetuning-whisper-small-swahili-asr-model_ndizi_gguf/resolve/main/ggml-model-q4_0.bin}}"
NDIZI_GGML_Q5_URL="${NDIZI_GGML_Q5_URL:-https://huggingface.co/smutuvi/ndizi-whisper-small-GGUF/resolve/main/ggml-model-q5_0.bin}"

# Curl tuning
CURL_RETRY="${CURL_RETRY:-5}"
CURL_RETRY_DELAY="${CURL_RETRY_DELAY:-2}"
CURL_RETRY_MAX_TIME="${CURL_RETRY_MAX_TIME:-180}"
CURL_CONNECT_TIMEOUT="${CURL_CONNECT_TIMEOUT:-20}"
# Default max time per attempt (seconds). Set 0 to disable.
CURL_MAX_TIME="${CURL_MAX_TIME:-1800}"
CURL_SPEED_TIME="${CURL_SPEED_TIME:-30}"
CURL_SPEED_LIMIT="${CURL_SPEED_LIMIT:-1024}"

# Optional safety: reject files smaller than this many bytes
MIN_BYTES="${MIN_BYTES:-1048576}"  # 1 MiB

DEBUG="${DEBUG:-0}"

log()  { printf '%s\n' "$*"; }
dbg()  { if [ "$DEBUG" = "1" ]; then printf '[DEBUG] %s\n' "$*"; fi; }
die()  { printf '❌ %s\n' "$*" >&2; exit 1; }

# --- Preconditions -------------------------------------------
need_cmd() { command -v "$1" >/dev/null 2>&1 || die "Required command '$1' not found"; }
need_cmd curl
mkdir -p "$MODEL_DIR"

# Cleanup only stale .part files (older than 1 day) to avoid killing active downloads
cleanup_stale_parts() {
  # find is available on macOS; -mtime +1 means older than 1 day
  find "$MODEL_DIR" -type f -name '*.part' -mtime +1 -print -delete 2>/dev/null || true
}
cleanup_stale_parts || true

# Return: "url|final_filename"
# Supports aliases:
#   ndizi-q4_0.bin -> NDIZI_GGML_Q4_URL
#   ndizi-q5_0.bin -> NDIZI_GGML_Q5_URL
# Backward compatible:
#   ggml-model-q4_0.bin / ggml-model-q5_0.bin -> Ndizi URLs (old behavior)
resolve_model() {
  local name="$1"

  case "$name" in
    ndizi-q4_0.bin)
      echo "${NDIZI_GGML_Q4_URL}|ggml-model-q4_0.bin"
      ;;
    ndizi-q5_0.bin)
      echo "${NDIZI_GGML_Q5_URL}|ggml-model-q5_0.bin"
      ;;
    ggml-model-q4_0.bin)
      echo "${NDIZI_GGML_Q4_URL}|ggml-model-q4_0.bin"
      ;;
    ggml-model-q5_0.bin)
      echo "${NDIZI_GGML_Q5_URL}|ggml-model-q5_0.bin"
      ;;
    *)
      echo "${BASE_URL}/${name}|${name}"
      ;;
  esac
}

size_ok_or_warn() {
  local file="$1"
  if [ ! -f "$file" ]; then
    return 1
  fi
  # wc -c works on macOS
  local bytes
  bytes="$(wc -c < "$file" | tr -d ' ')"
  dbg "File size: ${bytes} bytes (${file})"
  if [ "$bytes" -lt "$MIN_BYTES" ]; then
    log "⚠️  File is suspiciously small (< ${MIN_BYTES} bytes): $(basename "$file")"
    return 1
  fi
  return 0
}

download_one() {
  local requested="$1"
  local resolved url final_name
  resolved="$(resolve_model "$requested")"
  url="${resolved%%|*}"
  final_name="${resolved##*|}"

  local out="$MODEL_DIR/${final_name}"
  local tmp="$MODEL_DIR/${final_name}.part"

  if [ -f "$out" ] && [ -s "$out" ]; then
    dbg "Found existing file: $out"
    # Optional sanity check
    if size_ok_or_warn "$out"; then
      log "✅ ${final_name} already exists. Skipping."
      return 0
    else
      log "⚠️  Existing file looks wrong; re-downloading: ${final_name}"
      rm -f "$out" || true
    fi
  fi

  log "⬇️  Downloading ${final_name}"
  log "    → $url"

  local max_time_args=()
  if [ "$CURL_MAX_TIME" != "0" ]; then
    max_time_args=( --max-time "$CURL_MAX_TIME" )
  else
    max_time_args=( --max-time 0 )
  fi

  for attempt in 1 2 3 4 5; do
    dbg "Attempt ${attempt} / 5"
    # -f: fail on HTTP errors
    # -L: follow redirects
    # -C -: resume
    # -o: write to .part file
    if curl -fL \
        --retry "$CURL_RETRY" \
        --retry-delay "$CURL_RETRY_DELAY" \
        --retry-max-time "$CURL_RETRY_MAX_TIME" \
        --connect-timeout "$CURL_CONNECT_TIMEOUT" \
        "${max_time_args[@]}" \
        --speed-time "$CURL_SPEED_TIME" \
        --speed-limit "$CURL_SPEED_LIMIT" \
        -H "User-Agent: curl/8.x (WhispersCpp-Android)" \
        -C - -o "$tmp" "$url"; then

      mv -f "$tmp" "$out"

      if [ ! -s "$out" ]; then
        log "❌ File is empty after download: ${final_name}"
        rm -f "$out" || true
        return 1
      fi

      # sanity size check
      if ! size_ok_or_warn "$out"; then
        log "⚠️  Downloaded file failed sanity size check; retrying: ${final_name}"
        rm -f "$out" || true
        # keep going to retry
      else
        log "✅ Download complete: ${final_name}"
        return 0
      fi
    else
      log "⚠️  Attempt ${attempt} failed for ${final_name}"
      # Exponential-ish backoff
      sleep $(( attempt * 2 ))
    fi
  done

  log "❌ Failed to download: ${final_name}"
  return 1
}

# --- Main -----------------------------------------------------
log "📦 MODEL_DIR=${MODEL_DIR}"
log "🌐 BASE_URL=${BASE_URL}"
dbg "MODEL_NAMES=${MODEL_NAMES}"
dbg "NDIZI_GGML_Q4_URL=${NDIZI_GGML_Q4_URL}"
dbg "NDIZI_GGML_Q5_URL=${NDIZI_GGML_Q5_URL}"

# Track overall failures without aborting on first (more convenient in CI)
fail=0
for model in $MODEL_NAMES; do
  if ! download_one "$model"; then
    fail=1
  fi
done

if [ "$fail" = "1" ]; then
  die "One or more downloads failed."
fi

log "🎉 All models present in: ${MODEL_DIR}"
