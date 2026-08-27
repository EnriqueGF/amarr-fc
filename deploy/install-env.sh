#!/bin/sh
set -eu

target=${1:-.env}
: "${AMULE_PASSWORD:?Set AMULE_PASSWORD to the existing aMule EC password}"

umask 077
temporary="${target}.tmp.$$"
trap 'rm -f "$temporary"' EXIT HUP INT TERM

random_secret() {
    openssl rand -hex 32
}

cat >"$temporary" <<EOF
AMULE_HOST=amule
AMULE_PORT=4712
AMULE_PASSWORD=$AMULE_PASSWORD
AMULE_WEB_PASSWORD=$(random_secret)
AMARR_PORT=8080
AMARR_CONFIG_PATH=/config
AMULE_FINISHED_PATH=/data/amule/complete
AMARR_API_KEY=$(random_secret)
AMARR_QBIT_USERNAME=sonarr
AMARR_QBIT_PASSWORD=$(random_secret)
AMARR_SEARCH_CACHE_SECONDS=900
AMARR_LOG_LEVEL=INFO
EOF

mv "$temporary" "$target"
trap - EXIT HUP INT TERM
