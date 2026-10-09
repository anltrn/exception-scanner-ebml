#!/bin/sh
# Konteyner girisi. EXTRA_CA_BUNDLE (PEM) verilmisse sirket CA sertifikalari git'e (klonlama)
# eklenir; Java tarafina (Bitbucket REST) uygulama acilirken kendisi ekler. Root yetkisi gerekmez.
set -e

if [ -n "$EXTRA_CA_BUNDLE" ] && [ -s "$EXTRA_CA_BUNDLE" ]; then
    SYSTEM_BUNDLE=""
    for f in /etc/pki/tls/certs/ca-bundle.crt /etc/ssl/certs/ca-certificates.crt; do
        if [ -s "$f" ]; then SYSTEM_BUNDLE="$f"; break; fi
    done
    cat $SYSTEM_BUNDLE "$EXTRA_CA_BUNDLE" > /tmp/git-ca-bundle.crt
    export GIT_SSL_CAINFO=/tmp/git-ca-bundle.crt
fi

# shellcheck disable=SC2086
exec java -XX:MaxRAMPercentage=${MAX_RAM_PERCENTAGE:-75} $JAVA_OPTS_APPEND -jar /app/app.jar --server "$@"
