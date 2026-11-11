-- operations-compliance/06 (F2 §3.5): a measured value over the permit limit opens a CORRECTIVE task.
-- measurement_id is the value that caused it. Its emission point + pollutant identify "the same exceedance":
-- while such a task is OPEN, further exceedances there are appended as task_event MEASUREMENT_ADDED instead.
-- The row stays when the value is soft-deleted, so the task is not cancelled on its own.
ALTER TABLE obligation_task ADD COLUMN measurement_id BIGINT REFERENCES measurement (id) ON DELETE SET NULL;
CREATE INDEX obligation_task_measurement_idx ON obligation_task (measurement_id) WHERE kind = 'CORRECTIVE';
