#!/usr/bin/env bash
# Aplica native/patches/*.patch sobre third_party/OrcaSlicer (idempotente).
#
# Checar cada patch com "apply --reverse --check" não basta: um patch cujas linhas um patch
# posterior também altera não reverte isolado. Por isso descobre qual prefixo da série já está
# aplicado comparando a árvore com HEAD + patches 1..k (num índice temporário) e aplica o resto.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ORCA="$ROOT/third_party/OrcaSlicer"
PATCHES=("$ROOT"/native/patches/*.patch)

TMP_INDEX="$(mktemp)"
trap 'rm -f "$TMP_INDEX"' EXIT

# Verdadeiro se a árvore de trabalho é exatamente HEAD + os primeiros $1 patches.
tree_matches_prefix() {
    local k=$1 i
    GIT_INDEX_FILE="$TMP_INDEX" git -C "$ORCA" read-tree HEAD
    for ((i = 0; i < k; i++)); do
        GIT_INDEX_FILE="$TMP_INDEX" git -C "$ORCA" apply --cached "${PATCHES[i]}" 2>/dev/null || return 1
    done
    GIT_INDEX_FILE="$TMP_INDEX" git -C "$ORCA" diff --quiet
}

applied=-1
for ((k = ${#PATCHES[@]}; k >= 0; k--)); do
    if tree_matches_prefix "$k"; then
        applied=$k
        break
    fi
done

if ((applied < 0)); then
    echo "erro: $ORCA tem alterações que não correspondem a nenhum prefixo de native/patches." >&2
    echo "Restaure a árvore (git -C third_party/OrcaSlicer checkout -- . && git clean -fd) e rode de novo." >&2
    exit 1
fi

for ((i = 0; i < ${#PATCHES[@]}; i++)); do
    name="$(basename "${PATCHES[i]}")"
    if ((i < applied)); then
        echo "já aplicado: $name"
    else
        git -C "$ORCA" apply "${PATCHES[i]}"
        echo "aplicado: $name"
    fi
done
