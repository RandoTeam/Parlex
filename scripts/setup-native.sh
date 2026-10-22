#!/usr/bin/env bash
# Restores the optional native engines for Parlex on Linux/macOS.
# A clean clone builds a CPU-only APK; this script adds the OpenCL link stub,
# llama.cpp at the pinned revision, and optionally MNN for PP-OCR.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CPP="$ROOT/app/src/main/cpp"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/27.3.13750724}"

WITH_MNN=0
SKIP_LLAMA=0
for arg in "$@"; do
  case "$arg" in
    --with-mnn) WITH_MNN=1 ;;
    --skip-llama) SKIP_LLAMA=1 ;;
  esac
done

command -v git >/dev/null || { echo "git is required" >&2; exit 1; }

# 1. OpenCL headers (link-time only).
if [ ! -f "$CPP/third_party/OpenCL-Headers/CL/cl.h" ]; then
  echo "Initialising OpenCL-Headers submodule..."
  git -C "$ROOT" submodule update --init --recursive
fi
[ -f "$CPP/third_party/OpenCL-Headers/CL/cl.h" ] || {
  echo "OpenCL headers are missing at $CPP/third_party/OpenCL-Headers" >&2; exit 1; }

# 2. Link-time stub.
[ -d "$NDK" ] || { echo "Android NDK not found at $NDK (set ANDROID_NDK_HOME)" >&2; exit 1; }
HOST_TAG="linux-x86_64"
[ "$(uname -s)" = "Darwin" ] && HOST_TAG="darwin-x86_64"
CLANG="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/aarch64-linux-android26-clang"
[ -x "$CLANG" ] || { echo "NDK clang not found: $CLANG" >&2; exit 1; }

STUB_DIR="$CPP/opencl-linker/arm64-v8a"
mkdir -p "$STUB_DIR"
cat > "$STUB_DIR/opencl_stub.c" <<'EOF'
/* Link-time placeholder. The real Adreno libOpenCL.so is supplied by the device. */
void parlex_opencl_link_stub(void) {}
EOF
"$CLANG" -shared -fPIC -o "$STUB_DIR/libOpenCL.so" "$STUB_DIR/opencl_stub.c"
echo "OpenCL link stub: $STUB_DIR/libOpenCL.so"

# 3. llama.cpp at the tracked revision.
if [ "$SKIP_LLAMA" -eq 0 ]; then
  COMMIT="$(awk '/^llama.cpp commit:/ { print $3 }' "$CPP/llama.cpp.version")"
  [ -n "$COMMIT" ] || { echo "Cannot parse llama.cpp.version" >&2; exit 1; }
  if [ ! -f "$CPP/llama.cpp/CMakeLists.txt" ]; then
    rm -rf "$CPP/llama.cpp"
    git clone --filter=blob:none https://github.com/ggml-org/llama.cpp.git "$CPP/llama.cpp"
  fi
  git -C "$CPP/llama.cpp" fetch --all --tags --prune
  git -C "$CPP/llama.cpp" checkout "$COMMIT"
  echo "llama.cpp: $COMMIT"
fi

# 4. MNN (optional, PP-OCR bridge).
if [ "$WITH_MNN" -eq 1 ]; then
  MNN_COMMIT="$(tr -d '[:space:]' < "$CPP/MNN.version")"
  if [ ! -f "$CPP/MNN/CMakeLists.txt" ]; then
    rm -rf "$CPP/MNN"
    git clone --filter=blob:none https://github.com/alibaba/MNN.git "$CPP/MNN"
  fi
  if [ -n "$MNN_COMMIT" ]; then
    git -C "$CPP/MNN" fetch --all --tags --prune
    git -C "$CPP/MNN" checkout "$MNN_COMMIT"
    echo "MNN: $MNN_COMMIT"
  fi
fi

echo "Native prerequisites are ready. Rebuild with: ./gradlew :app:assembleDebug"
