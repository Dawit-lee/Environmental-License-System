OpenAI Responses API responses for `FixtureLlmClient` (provider OPENAI).

- Input PDF: the same synthetic 협의의견서 as Gemini, `/gemini/consultation-opinion.pdf` (conditions 3-2 / 4-1 / 5-3).
- `obligations-recorded.json`: **recorded** from the real API (model `gpt-6.1-terra`, prompt `obligation-v1`, `store: false`)
  on that PDF with `OpenAiRecorder` (`RECORD_OPENAI=1`, key only from `OPENAI_API_KEY`). The response holds no key; the
  recorder asserts that and replaces the opaque `encrypted_content` reasoning blob with `<scrubbed>`. Re-record when the
  prompt, schema or model changes.
