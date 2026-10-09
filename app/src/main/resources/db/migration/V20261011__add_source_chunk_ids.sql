-- rag_chat_messages: 冗余存储来源引用的 chunk UUID 列表（JSON array），
-- 避免每次从 sourcesJson 解析；案例草稿创建时直接读取继承。
ALTER TABLE rag_chat_messages ADD COLUMN source_chunk_ids TEXT;

-- cases: 从来源消息继承的原始 KB chunk ID 列表（JSON array），
-- 用于回归评测的期望证据，替代案例自身向量化的 chunk ID。
ALTER TABLE cases ADD COLUMN source_chunk_ids TEXT;

-- case_regression_items: 标记期望证据的来源类型，
-- SOURCE=原始 KB chunk ID, MISSING=无来源证据（旧案例或来源缺失）, SELF=旧数据使用自身向量 ID。
ALTER TABLE case_regression_items ADD COLUMN evidence_source VARCHAR(20);
