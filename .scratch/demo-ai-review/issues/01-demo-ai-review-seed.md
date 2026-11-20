# 01 공개 데모에서 AI 검토 흐름 전체 보이기

Status: resolved
Type: task

## 무엇을

조회 전용 데모 계정으로 문서 → AI 후보 → 원문 인용 → 사람 검토 → 의무·일정 반영 흐름을 끝까지 볼 수 있게 한다.
공개 서버는 AI를 부르지 않는다(2026-11-20 사용자 결정). 미리 녹화한 Claude 응답을 쓴다.

## 할 일

- 가상 문서 PDF 4개: 입찰공고문, 통합허가서 v1·v2, 협의의견서. 실제 문서는 참고하지 않는다.
- 로컬에서 실제 Claude로 한 번 돌려 응답을 녹화한다.
- 데모 시드: 승인(의무·일정 반영), 검토 대기, 반려 1건(사유), v1 → v2 변경 비교.
- 재실행해도 같은 결과. `reset-demo.sh`(DB 삭제 후 재시작)와 맞는다.

## 완료 기준

- 데모 계정으로 검토함, 의무 목록, 일정, 변경 비교 화면에 데이터가 보인다.
- 백엔드 테스트, 프론트 build·tsc·lint·vitest 통과.
- 대외비 grep 0건(diff와 PDF 본문).
- `dev` 대상 PR, CI 통과.

## Answer

- 문서: `docs/samples/*.html` → `backend/src/main/resources/demo/documents/*.pdf`(Chrome 헤드리스).
- 녹화: `DemoRecorder`(수동 테스트)가 실제 Claude(claude-sonnet-5-5)로 4건을 돌려 `demo/ai/*.json`에 저장.
- 시드: SQL이 아니라 `DemoAiSeed`(demo 프로필, 시작 시). 녹화 응답을 실제 `Extraction`·`Review` 코드에 흘려 넣는다. 승인 로직을 SQL로 흉내 내지 않아 화면과 어긋날 일이 없다.
  - 허가서 v1 조건 20건 승인(의무·기한), 협의의견서 12건 중 1건 반려·11건 대기, 입찰공고 현장설명회 승인(달력)·5건 대기, v2 개정 비교 21건 중 변경 1건 승인.
- 결정성: 시험 DB를 지우고 다시 띄워 제안 지문(md5)이 같음을 확인. `reset-demo.sh`(DB 삭제 후 API 재시작)와 같은 순서.
