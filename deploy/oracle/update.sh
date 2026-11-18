#!/usr/bin/env bash
# Pull the latest dev branch, rebuild and restart. Data stays (named volumes).
set -euo pipefail
cd "$(dirname "$0")"
git pull --ff-only
sudo docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build
sudo docker image prune -f
