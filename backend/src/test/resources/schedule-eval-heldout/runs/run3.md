| 문서 | 추출기 | 정답 | 추출 | 맞음 | 재현율 | 정밀도 | 함정 | 놓침 | 오검출 |
|---|---|---|---|---|---|---|---|---|---|
Standard Commons Logging discovery in action with spring-jcl: please remove commons-logging.jar from classpath in order to avoid potential conflicts
| bid-notice-1 | claude | 5 | 3 | 3 | 60% | 100% | 0 | 2028-03-24 NOTICE+7D |  |
| bid-notice-2 | claude | 4 | 4 | 4 | 100% | 100% | 0 |  |  |
| bid-notice-3 | claude | 5 | 4 | 4 | 80% | 100% | 0 | 2028-12-05 |  |
| letter-1 | claude | 3 | 3 | 3 | 100% | 100% | 0 |  |  |
| letter-2 | claude | 4 | 3 | 3 | 75% | 100% | 0 | 2028-09-21 |  |
| letter-3 | claude | 3 | 2 | 2 | 67% | 100% | 0 | QUARTERLY |  |
| minutes-1 | claude | 5 | 5 | 4 | 80% | 80% | 1 | 2028-06-07 | 2028-05-18 |
| minutes-2 | claude | 5 | 5 | 5 | 100% | 100% | 0 |  |  |
  schedule-chart-1: 실패 400: {"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to upgrade or purchase credits."},"request_id":"req_011Ch83qJd4VcBZ6EgG28neR"}
| schedule-chart-1 | claude | 11 | 0 | 0 | 0% | — | 0 | 2028-06-01 2028-07-14 2028-07-17 2028-08-24 2028-09-01 2028-10-31 2028-11-15 2028-12-07 2028-12-20 2028-12-27 MONTHLY |  |
  site-briefing-1: 실패 400: {"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to upgrade or purchase credits."},"request_id":"req_011Ch83qMBc7G4RhcTZBWuRi"}
| site-briefing-1 | claude | 3 | 0 | 0 | 0% | — | 0 | 2028-05-10 SITE_BRIEFING+14D|2028-05-24 SITE_BRIEFING+5D|2028-05-15 |  |
  site-briefing-2: 실패 400: {"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to upgrade or purchase credits."},"request_id":"req_011Ch83qPGNcDTmgsU5CSJx2"}
| site-briefing-2 | claude | 3 | 0 | 0 | 0% | — | 0 | CONTRACT+5D NOTICE+7D SITE_BRIEFING+4D |  |
  task-spec-1: 실패 400: {"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to upgrade or purchase credits."},"request_id":"req_011Ch83qRa23zXWddibNsjJQ"}
| task-spec-1 | claude | 8 | 0 | 0 | 0% | — | 0 | COMPLETION-14D COMPLETION-1M CONTRACT+7D KICKOFF+450D KICKOFF+4M KICKOFF+7M MONTHLY QUARTERLY |  |
  task-spec-2: 실패 400: {"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to upgrade or purchase credits."},"request_id":"req_011Ch83qTnU9WCdWrEsRksUT"}
| task-spec-2 | claude | 5 | 0 | 0 | 0% | — | 0 | CONSTRUCTION_START+30D CONSTRUCTION_START+6M KICKOFF+3M KICKOFF+5M QUARTERLY |  |
| **전체 13건** | claude | 64 | 29 | 28 | **44%** | **97%** | 1 | | |
claude: API 호출 8회, 입력 21082·출력 13759 토큰
