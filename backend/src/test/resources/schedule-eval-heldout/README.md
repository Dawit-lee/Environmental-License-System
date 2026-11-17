# 보류(held-out) 일정 문서 세트

솔빛군(가상) 문서 13건과 정답. 실제 사업·기관·회사와 무관하다.
개발 세트(`../schedule-eval`, 가온시)와 다른 지자체·시설·회사로 새로 썼다.
정답은 문서와 함께 썼고, 추출기를 돌리기 전에 커밋해 고정했다.

## 파일 형식

- `X.txt`: 개발 세트와 같다. 첫 줄 `# docType: …`, 쪽 구분 `---page---`.
- `X.answers.csv`: 한 줄에 정답 하나.
  - `A|B`: 둘 중 하나만 찾아도 맞음(규칙과, 같은 문서에 기준일이 있어 풀린 날짜).
  - `!날짜`: 함정. 일정이 아닌 날짜(공고일·시행일·작성일·회의 당일·법령 제정일·과거 실적). 뽑으면 오검출이고 따로 센다.
- 채점: `./gradlew scheduleEval --args="src/test/resources/schedule-eval-heldout rule,claude"`

## 정답 기준

- 넣는 것: 마감, 회의·심의·설명회, 출장(첫날·마지막 날), 공정 시작·종료, 기준일 규칙, 정기 보고.
- 빼는 것: 위 함정 날짜. 일정 직전 "전까지" 마감이 날짜 없이 행사에 붙은 경우는 행사 날짜 하나로 본다.
- 날짜가 같은 항목은 날짜 하나로 센다(채점이 날짜 단위라서).

## 정답표

| 문서 | 제목 | 종류 | 날짜·규칙 | 기준일 |
|---|---|---|---|---|
| bid-notice-1 | 현장설명회 | 행사 | 2028-03-15 | — |
| bid-notice-1 | 입찰참가 등록 마감 | 마감 | 2028-03-24 | — |
| bid-notice-1 | 전자입찰서 제출 마감·개찰 | 마감 | 2028-03-30 | — |
| bid-notice-1 | 적격심사 서류 제출 | 마감 | NOTICE+7D | 적격자 통보일 |
| bid-notice-1 | 용역기간 종료 | 마감 | KICKOFF+450D | 착수일 |
| bid-notice-2 | 현장설명 | 행사 | 2028-04-07 | — |
| bid-notice-2 | 질의 마감 | 마감 | 2028-04-10 | — |
| bid-notice-2 | 입찰 마감·개찰 | 마감 | 2028-04-13 | — |
| bid-notice-2 | 착수신고서·과업수행계획서 제출 | 마감 | CONTRACT+10D | 계약일 |
| bid-notice-3 | 공사기간 종료 | 마감 | CONSTRUCTION_START+18M | 착공일 |
| bid-notice-3 | 기술제안서 제출 | 마감 | 2028-11-22 | — |
| bid-notice-3 | 기술제안서 평가(이틀) | 행사 | 2028-12-04, 2028-12-05 | — |
| bid-notice-3 | 실시설계서 제출 | 마감 | NOTICE+10D | 적격자 통보일 |
| site-briefing-1 | 현장설명 | 행사 | 2028-05-10 | — |
| site-briefing-1 | 질의서 제출 | 마감 | SITE_BRIEFING+5D 또는 2028-05-15 | 현장설명일 |
| site-briefing-1 | 입찰 서류 제출 | 마감 | SITE_BRIEFING+14D 또는 2028-05-24 | 현장설명일 |
| site-briefing-2 | 사업수행능력평가 서류 | 마감 | SITE_BRIEFING+4D | 현장설명일 |
| site-briefing-2 | 계약 체결 | 마감 | NOTICE+7D | 적격자 통보일 |
| site-briefing-2 | 착수 | 마감 | CONTRACT+5D | 계약일 |
| task-spec-1 | 과업기간 종료 | 마감 | KICKOFF+450D | 착수일 |
| task-spec-1 | 착수계·과업수행계획서 | 마감 | CONTRACT+7D | 계약일 |
| task-spec-1 | 기본설계 중간보고 | 마감 | KICKOFF+4M | 착수일 |
| task-spec-1 | 기본설계 최종보고 | 마감 | KICKOFF+7M | 착수일 |
| task-spec-1 | 실시설계 성과품 초안 | 마감 | COMPLETION-1M | 준공일 |
| task-spec-1 | 준공검사 요청 | 마감 | COMPLETION-14D | 준공일 |
| task-spec-1 | 공정보고 | 정기 보고 | MONTHLY | — |
| task-spec-1 | 기술자문회의 결과보고 | 정기 보고 | QUARTERLY | — |
| task-spec-2 | 시공 단계 설계지원 종료 | 마감 | CONSTRUCTION_START+6M | 착공일 |
| task-spec-2 | 하수도 설치(변경) 인가 신청서 | 마감 | KICKOFF+5M | 착수일 |
| task-spec-2 | 소규모환경영향평가 협의서 | 마감 | KICKOFF+3M | 착수일 |
| task-spec-2 | 시공상세도 검토 회의 | 마감 | CONSTRUCTION_START+30D | 착공일 |
| task-spec-2 | 시공 단계 설계지원 보고 | 정기 보고 | QUARTERLY | — |
| schedule-chart-1 | 착수(현황조사 시작) | 공정 | 2028-06-01 | — |
| schedule-chart-1 | 현황조사 및 측량 종료 | 공정 | 2028-07-14 | — |
| schedule-chart-1 | 기본설계 시작·종료 | 공정 | 2028-07-17, 2028-10-31 | — |
| schedule-chart-1 | 중간보고회 | 행사 | 2028-08-24 | — |
| schedule-chart-1 | 관계기관 협의 시작·종료 | 공정 | 2028-09-01, 2028-11-15 | — |
| schedule-chart-1 | 최종보고회 | 행사 | 2028-12-07 | — |
| schedule-chart-1 | 성과품 납품 시작·준공 예정 | 공정 | 2028-12-20, 2028-12-27 | — |
| schedule-chart-1 | 월간 공정회의 | 정기 보고 | MONTHLY | — |
| letter-1 | 공동위원회 심의 | 행사 | 2028-07-26 | — |
| letter-1 | 심의 자료 제출 | 마감 | REVIEW-7D 또는 2028-07-19 | 심의일 |
| letter-1 | 조치계획서 제출 | 마감 | REVIEW+14D 또는 2028-08-09 | 심의일 |
| letter-2 | 보완서 제출 | 마감 | 2028-10-06 | — |
| letter-2 | 보완 사항 설명회 | 행사 | 2028-09-13 | — |
| letter-2 | 현지 확인 출장(1박 2일) | 출장 | 2028-09-20, 2028-09-21 | — |
| letter-3 | 배출시설 변경신고 서류 | 마감 | 2029-02-09 | — |
| letter-3 | 주민협의체 설명 자료 | 정기 보고 | QUARTERLY | — |
| letter-3 | 최종보고회(전까지 반영 결과 보고) | 행사 | 2029-03-22 | — |
| minutes-1 | 현장 정밀조사 출장(2박 3일) | 출장 | 2028-06-07, 2028-06-09 | — |
| minutes-1 | 기술자문회의 | 행사 | 2028-06-28 | — |
| minutes-1 | 인터뷰 자료 회신 기한 | 마감 | 2028-05-31 | — |
| minutes-1 | 공정회의 | 정기 보고 | BIWEEKLY | — |
| minutes-2 | 대안 비교표 보완 | 마감 | 2028-10-20 | — |
| minutes-2 | 하천관리청 방문 협의 | 행사 | 2028-10-26 | — |
| minutes-2 | 단가 조사 완료 | 마감 | 2028-11-03 | — |
| minutes-2 | 설계 VE 검토 | 마감 | KICKOFF+8M | 착수일 |
| minutes-2 | 주간보고 | 정기 보고 | WEEKLY | — |

날짜 단위 정답 64개, 함정 24개.
