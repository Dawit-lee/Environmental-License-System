# AGENTS.md

환경 인허가 관리 시스템. Spring Boot 3(Java 17) 백엔드 + React(Vite) 프론트엔드.

## 명령
- 환경변수: `cp .env.example .env` 후 `DB_PASSWORD`, `JWT_SECRET`, `S3_SECRET_KEY` 채우기
- 전체 실행(DB·S3·API): `DB_PORT=5435 API_PORT=8081 docker compose up -d --build`
- 백엔드 테스트: `cd backend && ./gradlew test` (Testcontainers라 Docker가 켜져 있어야 함)
- 프론트 개발 서버: `cd frontend && npm install && API_URL=http://localhost:8081 npm run dev -- --port 5182`
- 프론트 검사(CI와 같음): `cd frontend && npm ci && npx tsc --noEmit && npx eslint . && npx vitest run && npx vite build`

## 구조
- `backend/src/main/java/com/envpermit/` 기능별 패키지(`core`, `law`, `extraction`, `obligation`, `schedule` 등)
- `backend/src/main/resources/db/migration/` Flyway 스키마(`V{번호}__{이름}.sql`)
- `backend/src/main/resources/db/dev`, `db/demo` dev·demo 프로필 전용 시드
- `frontend/src/` `pages`(화면), `components`, `api`(서버 호출)
- `docs/` 설계(ARCHITECTURE.md)·측정·평가 문서
- `.github/workflows/ci.yml` PR마다 백엔드 테스트 + 프론트 검사

## 규칙
- 작업 브랜치는 `dev`에서 따고 PR도 `dev`로 보낸다.
- 스키마 변경은 새 Flyway 파일을 더한다. 이미 머지된 `V*.sql`은 고치지 않는다.
- 새 API에는 Testcontainers 기반 REST 테스트를 함께 넣는다(권한 403 경우 포함).
- 외부 API(법제처·AI)는 테스트에서 녹화한 응답 픽스처로만 부른다. CI에서 실제 호출 금지.
- PR 전에 백엔드 테스트와 프론트 검사를 모두 통과시킨다.

## 하지 말 것
- `.env`, API 키, 실제 회사 문서·인허가 데이터 커밋 금지(실제 골든 사례는 레포 밖 `GOLDEN_DIR`에만 둔다).
- 운영 DB에 SQL을 직접 실행하지 않는다. 변경은 Flyway 마이그레이션으로만 한다.
- 이력 테이블(`*_event`, `*_revision_log`)은 DB 트리거로 append-only다. UPDATE·DELETE 하는 코드를 만들지 않는다.
