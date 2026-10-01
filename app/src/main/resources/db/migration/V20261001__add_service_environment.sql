-- DevSupport Phase 1 批次 A：为知识库新增服务/环境组织标签
-- 不设 DEFAULT，旧行保持 NULL（视为"未分类"）
ALTER TABLE knowledge_bases ADD COLUMN service VARCHAR(100);
ALTER TABLE knowledge_bases ADD COLUMN environment VARCHAR(50);
CREATE INDEX idx_kb_service ON knowledge_bases(service);
CREATE INDEX idx_kb_environment ON knowledge_bases(environment);
