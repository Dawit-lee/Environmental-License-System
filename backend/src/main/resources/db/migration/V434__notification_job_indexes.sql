-- L6: the daily notification job reads deadlines by due date and unsent notifications.
CREATE INDEX IF NOT EXISTS deadline_event_due_idx ON deadline_event (due_date);
CREATE INDEX IF NOT EXISTS notification_unsent_idx ON notification (user_id) WHERE emailed_at IS NULL;
