-- groundwork_live_updates.tell(): called by a table's change triggers after each
-- statement; tells every listening server which table changed, on the
-- channel groundwork_live_updates, when the transaction commits:
--   {"folder": "public", "table": "orders"}
-- Only the table: no ids, no column values. Who may see what changed is
-- the gates' to decide when a screen asks again. A statement that changed
-- no row tells nothing; Postgres sends one transaction's same message once.
-- It runs as whoever wrote the rows: pg_notify needs no grant.
-- Declared to groundwork-postgres (groundwork_live_updates.api/function-files), which makes the
-- folder groundwork-live-updates and the function before the tables' triggers.
CREATE OR REPLACE FUNCTION groundwork_live_updates.tell() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  rows_sql text;
  changed  boolean;
BEGIN
  -- an update has as many new rows as old ones
  rows_sql := CASE TG_OP WHEN 'DELETE' THEN 'SELECT FROM groundwork_postgres_old_rows' ELSE 'SELECT FROM groundwork_postgres_new_rows' END;
  EXECUTE format('SELECT EXISTS (%s)', rows_sql) INTO changed;
  IF changed THEN
    PERFORM pg_notify('groundwork_live_updates',
                      jsonb_build_object('folder', TG_TABLE_SCHEMA, 'table', TG_TABLE_NAME)::text);
  END IF;
  RETURN NULL;
END
$$
