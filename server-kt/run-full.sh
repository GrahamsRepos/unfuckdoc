#!/usr/bin/env bash
# Run the backend fully loaded: nomic (embeddings) + qwen2.5 (LLM) via local Ollama.
# Requires Ollama running with both models pulled (see ../SETUP.md).
set -euo pipefail
cd "$(dirname "$0")"

export PORT="${PORT:-8080}"
export EMBED_BASE_URL="${EMBED_BASE_URL:-http://localhost:11434/v1}"
export EMBED_MODEL="${EMBED_MODEL:-nomic-embed-text}"
export LLM_BASE_URL="${LLM_BASE_URL:-http://localhost:11434/v1}"
export LLM_MODEL="${LLM_MODEL:-qwen2.5:7b}"

echo "unfuckdoc backend → :$PORT   embed=$EMBED_MODEL   llm=$LLM_MODEL"
exec ./gradlew run
