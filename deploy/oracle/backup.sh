#!/usr/bin/env bash
# Daily pg_dump to ~/envpermit-backups, keeping the last 7 (cron from setup.sh).
set -euo pipefail
cd "$(dirname "$0")"
out="$HOME/envpermit-backups"
mkdir -p "$out"
sudo docker compose --env-file .env.prod -f docker-compose.prod.yml exec -T db pg_dump -U envpermit envpermit \
    | gzip > "$out/envpermit-$(date +%F).sql.gz"
find "$out" -name 'envpermit-*.sql.gz' -mtime +6 -delete
