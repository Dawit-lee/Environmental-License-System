Messages API responses for `FixtureLlmClient`. **Hand-built**, not recorded: no `ANTHROPIC_API_KEY` was
available when R2 was written. Shape follows the documented Messages API response (structured output → one
`text` block holding the JSON; Sonnet 5.5 adaptive thinking → an empty `thinking` block first). Replace with
recorded responses (key/ids are not secrets, but scrub anything company-specific) when a real call is made.

The text matches the synthetic PDF built in `ExtractionApiTest` (Helvetica, English text).

`schedule-bid-notice.json` (design-schedule/07): a fictional 가온시 bid notice, matching the two-page PDF built in
`ScheduleExtractionApiTest`. 8 rows: 4 events (one RECURRING), 3 schedule items, 1 row without evidence (dropped).
