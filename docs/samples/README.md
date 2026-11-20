# 시연용 가상 문서

AI 검토 흐름(문서 → AI 후보 → 원문 인용 → 사람 검토 → 의무·일정 반영)을 직접 해 볼 수 있는 PDF 4종입니다.
시설은 데모 프로젝트 「새벽시 매립가스 자원화시설」에 맞췄습니다.
기관, 사업, 사람, 번호는 모두 지어낸 것입니다. 공개된 법령 서식과 입찰공고 형식만 참고했습니다.

| 문서 | 올리는 곳 | 해 볼 것 |
|---|---|---|
| [입찰공고](../../backend/src/main/resources/demo/documents/01-bid-notice.pdf) | 프로젝트 문서, 종류 「공고문」 | 일정 추출. 현장설명일·착공일 기준 상대 기한 |
| [허가서 v1](../../backend/src/main/resources/demo/documents/02-permit-v1.pdf) | 인허가 항목의 문서 | 조건 추출 → 검토 → 의무사항·기한 |
| [허가서 v2(전체본)](../../backend/src/main/resources/demo/documents/03-permit-v2.pdf) | 같은 인허가 항목, v1을 대체하는 문서 | 개정 비교. 변경 1, 추가 1, 삭제 1 |
| [협의의견서](../../backend/src/main/resources/demo/documents/04-consultation-opinion.pdf) | 「환경영향평가 협의」 항목의 문서 | 조건 추출 |

v2는 v1의 모든 승인이 끝난 뒤 올려야 개정 비교를 시작할 수 있습니다.
v1과 v2의 차이: 1호 자가측정 주기(월 2회 → 주 1회), 폐흡착제 보고 삭제, 황화수소 연속측정기 조건 추가.

## 공개 데모

공개 데모 서버는 AI를 부르지 않습니다.
`demo` 프로필로 시작할 때 `DemoAiSeed`가 이 4종을 실제 추출·검토 코드에 넣고, AI 응답은 미리 녹화한 Claude 응답(`backend/src/main/resources/demo/ai/`)으로 대신합니다.
그래서 데모 계정(조회 전용)으로 승인된 의무, 검토 대기, 반려 1건, v1 → v2 개정 비교를 볼 수 있습니다.

## 다시 만들기

HTML(이 폴더)을 고친 뒤 PDF와 녹화를 새로 만듭니다. 녹화는 Claude API를 4번 부릅니다.

```bash
cd docs/samples && for f in 01-bid-notice 02-permit-v1 03-permit-v2 04-consultation-opinion; do "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --no-pdf-header-footer --print-to-pdf="$PWD/../../backend/src/main/resources/demo/documents/$f.pdf" "file://$PWD/$f.html"; done
```

```bash
cd backend && RECORD_DEMO=1 ANTHROPIC_API_KEY=... ./gradlew test --tests '*DemoRecorder'
```
