-- Add ADOPTING and ABANDONED vector status values
ALTER TABLE knowledge_bases DROP CONSTRAINT IF EXISTS knowledge_bases_vector_status_check;
ALTER TABLE knowledge_bases ADD CONSTRAINT knowledge_bases_vector_status_check
  CHECK (vector_status IN ('PENDING','PROCESSING','COMPLETED','FAILED','CONFLICT','ADOPTING','ABANDONED'));
