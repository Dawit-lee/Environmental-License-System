#!/usr/bin/env bash
# One-time setup of the public demo on a fresh Ubuntu VM (Oracle Always Free, arm64). Rerunning is safe:
# existing secrets in .env.prod are kept. Needs the tunnel credentials at ~/.cloudflared/tunnel.json first (README).
set -euo pipefail
REPO=https://github.com/Dawit-lee/Environmental-License-System.git
DIR="$HOME/envpermit"
CREDS="$HOME/.cloudflared/tunnel.json"

[ -f "$CREDS" ] || { echo "터널 인증 파일이 없습니다: $CREDS (README의 '터널 인증 파일 옮기기')" >&2; exit 1; }

# Docker from Ubuntu's own packages
if ! command -v docker >/dev/null; then
    sudo apt-get update
    sudo apt-get install -y docker.io docker-compose-v2 git
    sudo usermod -aG docker "$USER"
fi
sudo systemctl enable --now docker

# 2 GB swap: the backend and frontend builds peak above 1 GB on the smallest VM
if ! sudo swapon --show | grep -q /swapfile; then
    sudo fallocate -l 2G /swapfile
    sudo chmod 600 /swapfile
    sudo mkswap /swapfile
    sudo swapon /swapfile
    grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
fi

[ -d "$DIR/.git" ] || git clone -b dev "$REPO" "$DIR"
cd "$DIR/deploy/oracle"
chmod 600 "$CREDS"

if [ ! -f .env.prod ]; then
    umask 077
    cat > .env.prod <<ENV
# Written by setup.sh. Secret: never copy this file off the server.
DB_PASSWORD=$(openssl rand -hex 24)
JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')
S3_ACCESS_KEY=$(openssl rand -hex 12)
S3_SECRET_KEY=$(openssl rand -hex 24)
DEV_SEED_PASSWORD=$(openssl rand -hex 16)
TUNNEL_ID=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["TunnelID"])' "$CREDS")
TUNNEL_CREDENTIALS=$CREDS
HOST_UID=$(id -u)
APP_BASE_URL=https://permit.rema.sarl
API_JAVA_OPTS=-Xms128m -Xmx512m -XX:+UseSerialGC
LAW_API_OC=
ENV
fi
chmod 600 .env.prod
ln -sf .env.prod .env # so a plain "docker compose -f docker-compose.prod.yml ..." here finds the values

echo "빌드와 실행 (처음은 10~20분)"
sudo docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build

# daily DB backup 03:00 KST (VM clock is UTC), demo data re-seeded Monday 03:30 KST so its dates stay current
sudo tee /etc/cron.d/envpermit >/dev/null <<CRON
0 18 * * * $USER $DIR/deploy/oracle/backup.sh >> $HOME/envpermit-backups/cron.log 2>&1
30 18 * * 0 $USER $DIR/deploy/oracle/reset-demo.sh >> $HOME/envpermit-backups/cron.log 2>&1
CRON
mkdir -p "$HOME/envpermit-backups"

echo "API 기동 대기"
for _ in $(seq 60); do
    # an unauthenticated /api/me answers 401 once the API is up
    [ "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8088/api/me)" = 401 ] && { echo "완료: https://permit.rema.sarl"; exit 0; }
    sleep 5
done
echo "API가 5분 안에 뜨지 않았습니다. 로그: cd $PWD && sudo docker compose -f docker-compose.prod.yml logs api" >&2
exit 1
