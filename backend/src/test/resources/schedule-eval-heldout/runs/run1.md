| 문서 | 추출기 | 정답 | 추출 | 맞음 | 재현율 | 정밀도 | 함정 | 놓침 | 오검출 |
|---|---|---|---|---|---|---|---|---|---|
| bid-notice-1 | rule | 5 | 6 | 5 | 100% | 83% | 1 |  | 2028-03-06 |
| bid-notice-2 | rule | 4 | 6 | 4 | 100% | 67% | 2 |  | 2018-01-01 2028-04-03 |
| bid-notice-3 | rule | 5 | 7 | 5 | 100% | 71% | 2 |  | 2014-05-23 2028-11-01 |
| letter-1 | rule | 3 | 6 | 3 | 100% | 50% | 3 |  | 2015-04-10 2028-06-12 2028-07-03 |
| letter-2 | rule | 4 | 5 | 3 | 75% | 60% | 2 | 2028-09-21 | 2028-08-11 2028-09-04 |
| letter-3 | rule | 3 | 5 | 3 | 100% | 60% | 2 |  | 2028-12-18 2029-01-08 |
| minutes-1 | rule | 5 | 7 | 5 | 100% | 71% | 2 |  | 2027-11-07 2028-05-18 |
| minutes-2 | rule | 5 | 8 | 5 | 100% | 63% | 3 |  | 2027-12-20 2028-10-12 2028-10-13 |
| schedule-chart-1 | rule | 11 | 11 | 11 | 100% | 100% | 0 |  |  |
| site-briefing-1 | rule | 3 | 5 | 3 | 100% | 60% | 2 |  | 2009-11-30 2028-05-02 |
| site-briefing-2 | rule | 3 | 3 | 1 | 33% | 33% | 2 | CONTRACT+5D NOTICE+7D | 2016-08-01 2028-04-03 |
| task-spec-1 | rule | 8 | 8 | 7 | 88% | 88% | 1 | CONTRACT+7D | 2008-03-28 |
| task-spec-2 | rule | 5 | 6 | 5 | 100% | 83% | 1 |  | 2026-09-14 |
| **전체 13건** | rule | 64 | 83 | 60 | **94%** | **72%** | 23 | | |
Standard Commons Logging discovery in action with spring-jcl: please remove commons-logging.jar from classpath in order to avoid potential conflicts
| bid-notice-1 | claude | 5 | 3 | 3 | 60% | 100% | 0 | 2028-03-24 NOTICE+7D |  |
| bid-notice-2 | claude | 4 | 3 | 3 | 75% | 100% | 0 | CONTRACT+10D |  |
| bid-notice-3 | claude | 5 | 4 | 4 | 80% | 100% | 0 | 2028-12-05 |  |
| letter-1 | claude | 3 | 3 | 3 | 100% | 100% | 0 |  |  |
| letter-2 | claude | 4 | 3 | 3 | 75% | 100% | 0 | 2028-09-21 |  |
| letter-3 | claude | 3 | 2 | 2 | 67% | 100% | 0 | QUARTERLY |  |
| minutes-1 | claude | 5 | 5 | 4 | 80% | 80% | 1 | 2028-06-09 | 2028-05-18 |
| minutes-2 | claude | 5 | 3 | 3 | 60% | 100% | 0 | KICKOFF+8M WEEKLY |  |
| schedule-chart-1 | claude | 11 | 5 | 5 | 45% | 100% | 0 | 2028-07-14 2028-07-17 2028-09-01 2028-10-31 2028-11-15 2028-12-20 |  |
| site-briefing-1 | claude | 3 | 3 | 3 | 100% | 100% | 0 |  |  |
| site-briefing-2 | claude | 3 | 3 | 3 | 100% | 100% | 0 |  |  |
| task-spec-1 | claude | 8 | 8 | 8 | 100% | 100% | 0 |  |  |
| task-spec-2 | claude | 5 | 5 | 5 | 100% | 100% | 0 |  |  |
| **전체 13건** | claude | 64 | 50 | 49 | **77%** | **98%** | 1 | | |
claude: API 호출 13회, 입력 34091·출력 22236 토큰
