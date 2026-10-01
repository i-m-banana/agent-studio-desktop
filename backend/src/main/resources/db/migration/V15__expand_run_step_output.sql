-- Preserve bounded tool JSON and final build diagnostics without changing existing run identities.
ALTER TABLE run_step MODIFY COLUMN output_text MEDIUMTEXT;
