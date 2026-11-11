# 인허가 자동 도출 — 골든 정확도 측정

규칙 세트(`derivation_rule`의 ACTIVE 규칙)를 실제 사업의 인허가 목록과 대조해 재현율·정밀도·보류를 잰다.
하네스는 `backend/src/test/java/com/envpermit/derivation/GoldenAccuracyTest.java` 하나다.

## 실제 사례는 레포에 넣지 않는다

실제 사업의 속성값과 인허가 목록은 대외비다. 레포에는 **지어낸 사례**
(`backend/src/test/resources/golden/cases/synthetic-incineration.json`)만 있고, 실제 사례는 레포 밖 폴더에 둔다.

```bash
cd backend && GOLDEN_DIR=/레포/밖/골든폴더 ./gradlew test --tests '*GoldenAccuracyTest'
```

- `GOLDEN_DIR`이 없으면 실제 사례 테스트는 건너뛰고 합성 사례만 돈다(CI는 이 상태).
- `GOLDEN_DIR` 안의 `*.json`이 각각 한 사례다. 결과는 **같은 폴더**에 `result-<파일명>.md`(사례별, 인허가명 포함)와
  `result-total.md`(합계)로 쓴다. 레포나 빌드 폴더에는 아무것도 남기지 않는다.
- 합성 사례의 결과는 `backend/build/reports/golden/`에 쓴다.

## 사례 파일 형식

```json
{
  "name": "사례 이름",
  "as_of": "2026-01-01",
  "notes": ["결과 파일 끝에 그대로 붙는 메모"],
  "attributes": { "facility_type": "INCINERATION", "capacity_tpd": 120, "…": "필드 사전(Fields) 키" },
  "expected": [
    {"permit_name": "환경영향평가"},
    {"permit_name": "대기배출시설 설치신고", "aliases": ["이 사례에서만 쓰는 다른 표기"],
     "cause": "MISSING_RULE", "note": "놓쳤을 때 결과표에 같이 찍히는 메모"},
    {"permit_name": "폐기물처분업 허가", "expected": "NOT_APPLICABLE"},
    {"permit_name": "재원협의", "scope": "OUT_OF_SCOPE", "scope_reason": "예산 트랙 — 속성으로 판정 불가"}
  ]
}
```

- `attributes`는 화면 입력과 같은 검증을 거친다(모르는 키·형식 오류·필수값 누락이면 실패).
  **확보하지 못한 값은 추정하지 말고 빼 둔다.** 그 값을 쓰는 규칙은 ON_HOLD로 나온다.
- `expected` 기본값은 `APPLICABLE` / `IN_SCOPE`.
- `cause`는 사람이 다는 놓친 원인이다. 값은 자유지만 `MISSING_RULE`(규칙 없음), `RULE_CONDITION`(조건 오류),
  `INPUT_MISSING`(입력 미확보), `NAME_MISMATCH`(별칭 누락), `KIND_GAP`(신설 전용 규칙이라 증설에 안 걸림),
  `LAW_CHANGED`(기준일 이후 법 개정)를 쓴다. 결과에 원인별 건수가 집계된다.

## 이름 매칭

규칙의 `permit_name`과 정답의 `permit_name`·`aliases`를 정규화(NFC, 공백·괄호·가운뎃점 제거)해서 같으면 같은 항목이다.
어느 사업에서나 쓰는 다른 표기는 `backend/src/test/resources/golden/aliases.json`(규칙 이름 → 별칭)에,
한 사업 문서에만 나오는 표기는 그 사례 파일의 `aliases`에 둔다.

## 지표 (설계 §6.3)

| 지표 | 정의 |
|---|---|
| 재현율(엄격) | IN_SCOPE·APPLICABLE 정답 중 엔진이 APPLICABLE로 낸 비율 |
| 재현율(완화) | 같은 분모 중 APPLICABLE 또는 ON_HOLD |
| 정밀도 | APPLICABLE 출력 중 정답과 대조된 것(적중+오탐) 가운데 적중 비율. 정답셋에 없는 출력은 "미확인"으로 따로 센다 |
| 정답에 있는 출력 / 전체 해당 출력 | 미확인까지 분모에 넣은 값 |
| 오탐 | NOT_APPLICABLE 정답을 APPLICABLE로 낸 건수 |
| 보류 | ON_HOLD 규칙 수와 규칙별 빠진 입력 |

놓친 정답에는 엔진 쪽 상태가 같이 찍힌다: `NO_RULE`(맞는 규칙 없음), `SCOPE_EXCLUDED`(규칙은 있지만 시설유형·사업구분
범위 밖), `NOT_APPLICABLE`(조건 불충족, 사유 포함), `ON_HOLD`(입력 부족).

## 아직 없는 것

- `golden_case`/`golden_expected` 테이블과 Flyway 시드, 규칙 승인 직후 자동 평가, 화면의 [골든 평가] 버튼
  (실제 사례를 DB나 레포에 둘 수 없어서 파일 기반 하네스만 만들었다).
- "직전 커밋 대비 재현율 하락 시 실패" 게이트. 지금 CI는 합성 사례의 지표가 고정값과 같은지만 본다.
  규칙을 바꾸면 `GoldenAccuracyTest`의 기대값을 같이 고친다.
