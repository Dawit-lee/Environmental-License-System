Gemini `generateContent` responses for `FixtureLlmClient` (provider GEMINI).

- `consultation-opinion.pdf`: synthetic 협의의견서 (fake project, conditions 3-2 / 4-1 / 5-3), Korean text layer.
- `obligations-recorded.json`: **recorded** from the real API (model `gemini-3.6-flash`, prompt `obligation-v1`) on that
  PDF with `GeminiRecorder` (`RECORD_GEMINI=1`, key only from `GEMINI_API_KEY`). The response holds no key; the
  recorder asserts that. Re-record when the prompt, schema or model changes.
