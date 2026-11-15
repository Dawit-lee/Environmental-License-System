# 가상 일정 문서 세트 (design-schedule/07)

가온시(가상) 공고문·과업내용서·공문·회의록 각 3건, 직접 작성. 실제 사업·기관·회사와 무관하다.

- `X.txt`: 첫 줄 `# docType: …`, 쪽 구분 `---page---`.
- `X.answers.csv`: 일정에 넣어야 할 날짜 목록(`when` 한 열). 형식: 고정일 `2027-03-08`, 기준일 규칙
  `CONTRACT+15D` / `KICKOFF+5M` / `COMPLETION-30D`(준공일까지 = `COMPLETION+0D`), 정기 보고 `MONTHLY` 등.
  공고일·시행일·회의 당일처럼 일정에 넣지 않을 날짜는 정답이 아니다(정밀도에서 감점).

여러 날 이벤트(출장)는 첫날·마지막 날 둘 다 정답이다. 채점: `ScheduleEval` (`./gradlew scheduleEval`). 날짜 단위 재현율·정밀도, 제목은 보지 않는다.
