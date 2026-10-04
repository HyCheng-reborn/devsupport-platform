-- Version conflict governance: add conflict flag and normalized version label
ALTER TABLE knowledge_bases ADD COLUMN conflict BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE knowledge_bases ADD COLUMN normalized_version_label VARCHAR(50);

-- Update vector_status check constraint to include CONFLICT
ALTER TABLE knowledge_bases DROP CONSTRAINT IF EXISTS knowledge_bases_vector_status_check;
ALTER TABLE knowledge_bases ADD CONSTRAINT knowledge_bases_vector_status_check
  CHECK (vector_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'CONFLICT'));

-- Indexes for conflict queries
CREATE INDEX idx_kb_conflict ON knowledge_bases (conflict);
CREATE INDEX idx_kb_norm_label ON knowledge_bases (document_key, normalized_version_label);

-- Partial unique index: only one active non-conflict row per (document_key, normalized_version_label)
-- This is the concurrency safety net: near-simultaneous uploads with same key+label will fail here
CREATE UNIQUE INDEX uq_kb_active_version
  ON knowledge_bases (document_key, normalized_version_label)
  WHERE active = TRUE AND conflict = FALSE AND document_key IS NOT NULL;
