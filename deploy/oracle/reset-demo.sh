#!/usr/bin/env bash
# Rebuild the demo DB from the fictional seed (Flyway reruns it on start), so dates relative to "today" stay current.
# Anything added through other logins is lost; remove the line in /etc/cron.d/envpermit to keep data instead.
set -euo pipefail
cd "$(dirname "$0")"
dc() { sudo docker compose --env-file .env.prod -f docker-compose.prod.yml "$@"; }
dc stop api
dc exec -T db psql -U envpermit -d postgres -c 'DROP DATABASE envpermit WITH (FORCE)' -c 'CREATE DATABASE envpermit OWNER envpermit'
dc start api
