# 공개 데모 서버 (Oracle Cloud 무료)

https://permit.rema.sarl 데모를 Mac 대신 Oracle Cloud 무료 서버에서 돌린다.
Mac을 꺼도 데모가 계속 열린다.

## 이 폴더에 있는 것

| 파일 | 하는 일 |
|---|---|
| `docker-compose.prod.yml` | DB, 문서 저장소, API, 웹, 터널을 한 번에 띄운다 |
| `cloudflared.yml` | 터널 규칙. 가입·AI 추출 주소는 403으로 막는다 |
| `Caddyfile`, `web.Dockerfile` | 웹 화면을 빌드해 서빙하고 `/api`를 API로 넘긴다 |
| `setup.sh` | 새 서버에서 한 번 실행한다. 설치, 비밀값 생성, 실행, 백업 예약까지 한다 |
| `update.sh` | 최신 dev 브랜치로 다시 빌드한다 |
| `backup.sh` | DB를 매일 백업한다. 7일치만 남긴다 |
| `reset-demo.sh` | 매주 월요일 데모 데이터를 새로 만든다. 날짜가 늘 오늘 기준으로 보인다 |
| `.env.prod.example` | 서버 설정값 목록. 실제 값은 `setup.sh`가 서버에서 만든다 |

서버에는 가상의 예시 데이터만 들어간다.
로컬 DB는 서버로 옮기지 않는다.
AI 키와 구글 캘린더 설정은 서버에 넣지 않는다.

## 1. Oracle Cloud 계정 만들기

1. https://signup.cloud.oracle.com 에 들어간다.
2. 이메일, 이름, 국가(대한민국)를 넣는다.
3. 홈 리전은 **South Korea Central (Seoul)** 을 고른다. 나중에 바꿀 수 없다.
4. 본인 카드를 직접 넣는다. 본인 확인용이고 무료 범위에서는 청구되지 않는다.
5. 가입이 끝나면 콘솔(https://cloud.oracle.com)에 로그인한다.

## 2. SSH 키 만들기 (Mac)

Mac 터미널에서 실행한다.

```bash
ssh-keygen -t ed25519 -f ~/.ssh/oracle_permit -C oracle-permit
```

비밀번호(passphrase)는 비워도 된다.
공개 키는 다음 단계에서 붙여 넣는다.

```bash
cat ~/.ssh/oracle_permit.pub
```

## 3. 서버(VM) 만들기

1. 콘솔 왼쪽 위 메뉴 → **Compute** → **Instances** → **Create instance**.
2. 이름: `permit-demo`.
3. **Image**: Change image → **Ubuntu** → **Canonical Ubuntu 24.04** (22.04도 된다).
4. **Shape**: Change shape → **Ampere** → **VM.Standard.A1.Flex**.
5. OCPU **2**, 메모리 **12 GB** 로 둔다. 무료 한도는 합계 4 OCPU / 24 GB다.
6. **Networking**: 기본값(새 VCN, 공인 IP 할당)을 그대로 둔다.
7. **Add SSH keys**: Paste public keys → 2단계에서 출력한 한 줄을 붙여 넣는다.
8. **Boot volume**: 기본값(약 50 GB)을 그대로 둔다.
9. **Create** 를 누른다.
10. 상태가 RUNNING이 되면 **Public IP address** 를 적어 둔다.

"Out of capacity" 오류가 나면 OCPU 1 / 메모리 6 GB로 줄이거나, 몇 시간 뒤 다시 만든다.
방화벽 포트는 열지 않는다. 터널이 서버에서 바깥으로 연결하므로 22번(SSH) 말고는 필요 없다.

## 4. SSH 설정 (Mac)

`~/.ssh/config` 끝에 아래를 붙인다. `공인IP` 자리에 3단계의 IP를 넣는다.

```
Host oracle-permit
    HostName 공인IP
    User ubuntu
    IdentityFile ~/.ssh/oracle_permit
```

접속되는지 확인한다. 처음 물어보면 `yes` 를 입력한다.

```bash
ssh oracle-permit echo ok
```

## 5. 터널 인증 파일 옮기기 (Mac)

지금 Mac에서 쓰는 터널을 그대로 서버로 옮긴다. 도메인 설정은 바꾸지 않는다.

```bash
ssh oracle-permit 'mkdir -p ~/.cloudflared && chmod 700 ~/.cloudflared'
```

```bash
scp "$(awk '/credentials-file/{print $2}' ~/.cloudflared/permit.yml)" oracle-permit:.cloudflared/tunnel.json
```

이 파일은 터널 비밀번호다.
저장소에 올리거나 다른 곳에 붙여 넣지 않는다.

## 6. 서버 설치 (서버)

서버에 접속한다.

```bash
ssh oracle-permit
```

저장소를 받고 설치 스크립트를 실행한다.

```bash
git clone -b dev https://github.com/Dawit-lee/Environmental-License-System.git ~/envpermit
```

```bash
bash ~/envpermit/deploy/oracle/setup.sh
```

처음에는 빌드에 10~20분 걸린다.
마지막에 `완료: https://permit.rema.sarl` 이 나오면 끝이다.

스크립트가 하는 일:

- Docker 설치, 스왑 2 GB 추가
- 비밀값(DB 비밀번호, JWT, 저장소 키)을 무작위로 만들어 `~/envpermit/deploy/oracle/.env.prod` 에 저장 (본인만 읽기)
- 전체 빌드와 실행
- 매일 03:00 DB 백업 (`~/envpermit-backups`, 7일 보관)
- 매주 월요일 03:30 데모 데이터 새로 만들기

## 7. 사이트 확인

이 시점에는 Mac과 서버가 같은 터널에 함께 붙어 있다.
요청이 둘로 나뉘므로 바로 8단계로 넘어가 Mac 쪽을 끈다.

서버 안에서 먼저 확인한다. `401` 이 나오면 API가 떠 있는 것이다.

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8088/api/me
```

## 8. Mac 터널 끄기 (Mac)

Mac에서 데모용 터널과 미리보기 서버를 끈다.
`rema` 터널은 다른 서비스용이니 건드리지 않는다.

```bash
pkill -f 'cloudflared/permit.yml'
```

```bash
pkill -f 'vite preview --port 4182'
```

그다음 브라우저에서 확인한다.

1. https://permit.rema.sarl 을 연다.
2. **데모 계정으로 둘러보기** 를 누른다.
3. 프로젝트 5개, 인허가, 공정표, 캘린더가 보이면 된다.
4. 아무 항목이나 저장해 본다. "데모 계정은 조회만 가능합니다." 가 나오면 정상이다.

Mac을 꺼도 계속 열리는지 한 번 확인한다.

## 평소 관리 (서버)

최신 코드로 바꾸기:

```bash
~/envpermit/deploy/oracle/update.sh
```

아래 명령은 이 폴더에서 실행한다.

```bash
cd ~/envpermit/deploy/oracle
```

상태 보기:

```bash
sudo docker compose -f docker-compose.prod.yml ps
```

API 로그 보기:

```bash
sudo docker compose -f docker-compose.prod.yml logs --tail 100 api
```

데모 데이터 바로 새로 만들기:

```bash
~/envpermit/deploy/oracle/reset-demo.sh
```

## 배포 시 주의

V71 마이그레이션 파일의 출처 표기를 2026-11-22에 고쳤다.
그 전에 만든 DB는 API가 뜰 때 Flyway 체크섬 검증에서 멈춘다.
`update.sh` 전에 이 폴더에서 한 번만 실행한다(다시 실행해도 같다).

```bash
sudo docker compose -f docker-compose.prod.yml exec -T db psql -U envpermit -d envpermit -c "UPDATE flyway_schema_history SET checksum = -768518334 WHERE version = '71';"
```

`reset-demo.sh`로 DB를 새로 만들었다면 필요 없다.
로컬 DB도 같은 SQL을 한 번 실행한다.

## 알아 둘 것

- 서버 로그인은 데모 계정(조회 전용)만 쓴다. 다른 예시 계정의 비밀번호는 무작위라 아무도 모른다.
- 매주 데이터를 새로 만들므로 서버에서 직접 넣은 데이터는 지워진다. 남기려면 `/etc/cron.d/envpermit` 의 두 번째 줄을 지운다.
- Oracle은 오래 거의 쓰지 않는 무료 VM을 회수할 수 있다. 계정을 유료(Pay As You Go)로 올리면 회수 대상에서 빠지고, 무료 한도 안에서는 여전히 청구되지 않는다.
- 다시 Mac에서 돌리려면 서버의 이 폴더에서 `sudo docker compose -f docker-compose.prod.yml stop cloudflared` 를 하고 Mac 터널을 다시 켠다.
