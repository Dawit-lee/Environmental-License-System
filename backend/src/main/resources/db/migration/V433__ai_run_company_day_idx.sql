-- M4: the per-company daily AI run count.
CREATE INDEX ai_run_company_started_idx ON ai_run (company_id, started_at);
