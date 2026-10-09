-- 回归运行表：新增 evaluated_count 列，记录实际评测数（totalItems - skipped）
ALTER TABLE case_regression_runs ADD COLUMN evaluated_count INT NOT NULL DEFAULT 0;

-- 回填已有运行的 evaluated_count
UPDATE case_regression_runs
SET evaluated_count = total_items - skipped
WHERE total_items >= skipped;

-- 回填 case_regression_items 表中 evidence_source 为 NULL 的旧数据
-- 通过关联 cases 表的 source_chunk_ids 判断证据来源类型

-- 1. 关联案例有 source_chunk_ids（非空数组）的项标记为 SOURCE（原始 KB chunk ID）
UPDATE case_regression_items cri
SET evidence_source = 'SOURCE'
WHERE cri.evidence_source IS NULL
  AND EXISTS (
    SELECT 1 FROM cases c
    WHERE c.id = cri.case_id
      AND c.source_chunk_ids IS NOT NULL
      AND c.source_chunk_ids != '[]'
  );

-- 2. 关联案例无 source_chunk_ids 但有 expected_evidence 的项标记为 SELF（旧数据使用自身向量 ID）
UPDATE case_regression_items cri
SET evidence_source = 'SELF'
WHERE cri.evidence_source IS NULL
  AND NOT EXISTS (
    SELECT 1 FROM cases c
    WHERE c.id = cri.case_id
      AND c.source_chunk_ids IS NOT NULL
      AND c.source_chunk_ids != '[]'
  )
  AND cri.expected_evidence IS NOT NULL
  AND cri.expected_evidence != '[]';

-- 3. 无证据的项标记为 MISSING
UPDATE case_regression_items cri
SET evidence_source = 'MISSING'
WHERE cri.evidence_source IS NULL
  AND NOT EXISTS (
    SELECT 1 FROM cases c
    WHERE c.id = cri.case_id
      AND c.source_chunk_ids IS NOT NULL
      AND c.source_chunk_ids != '[]'
  )
  AND (cri.expected_evidence IS NULL OR cri.expected_evidence = '[]');
