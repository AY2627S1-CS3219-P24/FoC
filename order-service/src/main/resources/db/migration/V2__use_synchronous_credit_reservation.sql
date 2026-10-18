-- Finish the old message round trips before switching an existing database to HTTP.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM errands WHERE status = 'PENDING_CREDIT') THEN
        RAISE EXCEPTION 'Drain pending errand funding before upgrading to synchronous reservation';
    END IF;
END $$;
DROP TABLE credit_reservation_result_inbox;
DROP TABLE errand_submission_outbox;
