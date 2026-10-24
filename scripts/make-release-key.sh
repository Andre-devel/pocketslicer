#!/usr/bin/env bash
# Gera a chave de assinatura do APK de release, fora do repositório:
#   ~/.android-slicer/release.jks e ~/.android-slicer/keystore.properties (senha).
# O Gradle lê o keystore.properties (app/build.gradle.kts). Faça backup dos dois
# arquivos: sem a chave, não dá para publicar atualizações do app.
#
# Uso: scripts/make-release-key.sh   (não sobrescreve uma chave existente)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/env.sh"

DIR="$HOME/.android-slicer"
STORE="$DIR/release.jks"
PROPS="$DIR/keystore.properties"
if [ -e "$STORE" ]; then
    echo "já existe: $STORE" >&2
    exit 1
fi

mkdir -p "$DIR"
chmod 700 "$DIR"
PASSWORD="$(head -c 24 /dev/urandom | base64 | tr -d '/+=')"
# PKCS12 usa a mesma senha para o arquivo e para a chave.
"$JAVA_HOME/bin/keytool" -genkeypair -v -storetype PKCS12 -keystore "$STORE" \
    -alias release -keyalg RSA -keysize 4096 -validity 36500 \
    -storepass "$PASSWORD" -dname "CN=Andre-devel, O=Android Slicer" > /dev/null
umask 077
cat > "$PROPS" <<PROPS
storeFile=$STORE
storePassword=$PASSWORD
keyAlias=release
keyPassword=$PASSWORD
PROPS
chmod 600 "$STORE" "$PROPS"
echo "chave criada em $STORE; faça backup de $DIR"
