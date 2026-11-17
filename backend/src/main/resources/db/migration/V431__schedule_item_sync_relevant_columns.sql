-- M6: an UPDATE enqueues a Google sync only when a column the sync sends (or filters on) changed.
-- Before, every UPDATE (status, progress, sort order...) queued one row per project member.
DROP TRIGGER schedule_item_sync ON schedule_item;
CREATE TRIGGER schedule_item_sync AFTER INSERT OR DELETE ON schedule_item
    FOR EACH ROW EXECUTE FUNCTION schedule_item_sync();
CREATE TRIGGER schedule_item_sync_update AFTER UPDATE ON schedule_item
    FOR EACH ROW
    WHEN ((OLD.title, OLD.discipline_id, OLD.start_date, OLD.end_date, OLD.deleted_at, OLD.kind, OLD.owner_user_id,
           OLD.submission, OLD.project_id)
          IS DISTINCT FROM
          (NEW.title, NEW.discipline_id, NEW.start_date, NEW.end_date, NEW.deleted_at, NEW.kind, NEW.owner_user_id,
           NEW.submission, NEW.project_id))
    EXECUTE FUNCTION schedule_item_sync();
