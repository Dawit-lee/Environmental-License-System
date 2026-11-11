# 허가조건 AI 추출 품질 평가 (수동 실행)

협의의견서 PDF와 사람이 만든 정답표로 추출 제공자(Claude / Gemini / OpenAI)의 품질을 재는 스크립트입니다.
실제 API를 호출해 비용이 들기 때문에 **테스트·CI에서는 돌지 않고** 손으로만 실행합니다.
문서와 정답표는 저장소 밖 폴더에 둡니다(저장소에는 넣지 않습니다).

## 실행

```bash
cd backend
set -a; source ../.env; set +a        # ANTHROPIC_API_KEY / GEMINI_API_KEY / OPENAI_API_KEY (있는 제공자만 실행)
./gradlew extractionEval --args="$HOME/Desktop/projects/env-license-app-eval/r4"            # 3사 모두
./gradlew extractionEval --args="$HOME/Desktop/projects/env-license-app-eval/r4 gemini"     # 일부만
```

- 폴더 안의 `X.pdf` 중 `X.answers.csv`가 있는 것만 돌립니다.
- 키가 하나도 없으면 아무것도 호출하지 않고 정상 종료합니다.
- 모델은 앱과 같은 설정(`application.yml`, `CLAUDE_MODEL` / `GEMINI_MODEL` / `OPENAI_MODEL`)을 씁니다.
- 비용 칸은 `EVAL_PRICES="claude=2/10,gemini=0.75/3.75,openai=1/5"`(입력/출력 100만 토큰당 USD)를 줄 때만 계산합니다. 단가는 실행 시점의 공식 가격표로 확인해 넣습니다.
- 결과: 표는 화면과 `<폴더>/out/results.md`, 제공자 응답 원문은 `out/X.<제공자>.response-N.json`, 추출 항목별 대조는 `out/X.<제공자>.detail.txt`.

앱과 같은 경로를 탑니다: `ClaudePdfDirectPreparer` → `LlmClient.send`(`ObligationPrompt`) → `Provider`의 본문·토큰 읽기 → `Extraction.parse`(스키마 검증), 실패 시 1회 재시도. DB·S3·검토 큐는 거치지 않습니다.

## 정답표 형식 (`X.answers.csv`, UTF-8)

| 열 | 내용 |
|---|---|
| 조건번호 | 문서에 적힌 번호(또는 목차 번호). 없으면 비움 |
| 내용 | 조건 원문. 한 조건이 한 행(하위 항목 포함) |
| 주기 | 문서에 주기가 적힌 경우만 `ONCE` / `MONTHLY` / `QUARTERLY` / `SEMIANNUAL` / `ANNUAL` / `OTHER`. 여러 개면 `\|`로 구분(하나만 맞아도 일치) |
| 구분 | `채점`(기본) 또는 `참고`. 참고 = 모델이 뽑아도 되는 본문이지만 채점하지 않는 부분(예: 평가서 요약, 승인기관 대상 행정사항) |
| 그 뒤 열 | 메모용. 채점에 쓰지 않음 |

## 채점 규칙 (`EvalScorer`, 결정적)

추출 항목마다 정답표의 한 행에 붙입니다. 프롬프트가 조건을 의무별로 나누라고 하므로 여러 항목이 같은 행에 붙을 수 있습니다.

1. 글 유사도 = 글자·숫자만 남긴 뒤, 짧은 쪽의 2글자 조각 중 긴 쪽에도 있는 비율. `requirement_text`와 `source_quote` 중 높은 값.
2. 조건번호(`CitationKey.normalize` 기준)가 같은 행 중 가장 비슷한 행, 유사도 0.5 이상이면 일치.
3. 아니면 전체 행 중 가장 비슷한 행, 유사도 0.6 이상이면 일치(동점이면 채점 행 우선).
4. 어디에도 못 붙으면 오답.

| 지표 | 계산 |
|---|---|
| 재현율 | 항목이 하나라도 붙은 채점 행 / 채점 행 |
| 정밀도 | 채점 행에 붙은 항목 / (전체 항목 − 참고 행에 붙은 항목) |
| 번호 일치(정확) | 찾은 채점 행 중, 붙은 항목의 조건번호가 정규화 후 같은 비율 |
| 번호 일치(포함) | 위와 같되 상·하위 목차를 더 쓰거나 덜 쓴 것도 인정(`2-가` ≈ `Ⅱ-2-가`) |
| 주기 일치 | 주기가 적힌 채점 행 중, 붙은 항목의 `frequency`가 맞는 비율 |

조건마다 번호가 없는 문서(목차 아래 ○ 항목 나열)는 번호 일치율이 "어느 목차 아래인지"만 뜻합니다.

## 스캔본이 없을 때

```bash
./gradlew extractionEval --args="rasterize in.pdf out.scan.pdf"   # 글자층 없는 이미지 PDF(150dpi 회색조)
```

같은 정답표를 `out.scan.answers.csv`로 복사해 쓰면 같은 문서의 텍스트본·스캔본을 비교할 수 있습니다. 실제 스캔(기울어짐·잡티)보다 깨끗하다는 점은 결과에 밝혀 둡니다.
