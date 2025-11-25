#!/usr/bin/env bash
# ============================================================
# ✅ Whisper models downloader — macOS Bash 3.2 compatible
# ------------------------------------------------------------
# • No associative arrays (works on /bin/bash 3.2)
# • Atomic writes via *.part → mv
# • Resume (-C -), retries, timeouts, backoff
# • Jacaranda URL for ggml-model-q4_0.bin (overrideable)
# • Env overrides: MODEL_DIR / BASE_URL / MODEL_NAMES / JACARANDA_Q4_URL
# ============================================================

set -Eeuo pipefail

# --- Config (env-overridable) --------------------------------
MODEL_DIR="${MODEL_DIR:-src/main/assets/models}"
MODEL_URL="${MODEL_URL:-https://huggingface.co/ggerganov/whisper.cpp/resolve/main}"
# space-separated list
MODEL_NAMES="${MODEL_NAMES:-ggml-tiny-q5_1.bin ggml-base-q5_1.bin ggml-small-q5_1.bin ggml-model-q4_0.bin}"
JACARANDA_Q4_URL="${JACARANDA_Q4_URL:-https://huggingface.co/jboat/jacaranda-asr-whispercpp/resolve/main/ggml-model-q4_0.bin}"

# --- Preconditions -------------------------------------------
need_cmd() { command -v "$1" >/dev/null 2>&1 || { echo "❌ Required command '$1' not found"; exit 127; }; }
need_cmd curl
mkdir -p "$MODEL_DIR"

cleanup() { rm -f "$MODEL_DIR"/*.part 2>/dev/null || true; }
trap cleanup EXIT

# Return final URL for a given model name (no associative arrays)
url_for_model() {
  local name="$1"
  if [ "$name" = "ggml-model-q4_0.bin" ]; then
    echo "$JACARANDA_Q4_URL"
  else
    echo "$MODEL_URL/$name"
  fi
}

download() {
  local name="$1"
  local url; url="$(url_for_model "$name")"
  local tmp="$MODEL_DIR/${name}.part"
  local out="$MODEL_DIR/${name}"

  if [ -f "$out" ] && [ -s "$out" ]; then
    echo "✅ $name already exists. Skipping."
    return 0
  fi

  echo "⬇️  Downloading $name"
  echo "    → $url"

  # 5 attempts with simple backoff
  for attempt in 1 2 3 4 5; do
    if curl -fL \
        --retry 5 --retry-delay 2 --retry-max-time 180 \
        --connect-timeout 20 --max-time 0 \
        --speed-time 30 --speed-limit 1024 \
        -H "User-Agent: curl/8.x (WhispersCpp-Android)" \
        -C - -o "$tmp" "$url"; then
      mv -f "$tmp" "$out"
      if [ ! -s "$out" ]; then
        echo "❌ File is empty after download: $name"
        rm -f "$out"
        return 1
      fi
      echo "✅ Download complete: $name"
      return 0
    else
      echo "⚠️  Attempt $attempt failed for $name"
      sleep $(( attempt * 2 ))
    fi
  done

  echo "❌ Failed to download: $name"
  return 1
}

# --- Main -----------------------------------------------------
for model in $MODEL_NAMES; do
  download "$model"
done

echo "🎉 All models present in: $MODEL_DIR"
