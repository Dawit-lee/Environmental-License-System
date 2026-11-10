-- LLM provider of an extraction run (review-extraction/05). Earlier extraction runs were all Claude.
ALTER TABLE ai_run ADD COLUMN provider varchar(20);
UPDATE ai_run SET provider = 'CLAUDE' WHERE kind = 'OBLIGATION' AND preparer <> 'MANUAL';
