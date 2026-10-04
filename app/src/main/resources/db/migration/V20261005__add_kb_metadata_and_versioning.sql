-- DevSupport 阶段 2：知识库元数据与版本生命周期
-- 只做向前兼容的 ADD：不触碰 file_hash 的全局唯一约束 idx_kb_hash（内容去重语义不变）。
-- 生命周期用 active 标记表达（与 vector_status 的索引状态分离）。
-- 回滚（本轮不执行）：DROP 以下新增索引与列即可完全还原；历史行仅获得默认值，无数据破坏。

ALTER TABLE knowledge_bases ADD COLUMN project       VARCHAR(100);
ALTER TABLE knowledge_bases ADD COLUMN doc_type       VARCHAR(50);
ALTER TABLE knowledge_bases ADD COLUMN source         VARCHAR(50);
ALTER TABLE knowledge_bases ADD COLUMN version_label  VARCHAR(50);
ALTER TABLE knowledge_bases ADD COLUMN document_key   VARCHAR(64);
ALTER TABLE knowledge_bases ADD COLUMN version_no     INTEGER  NOT NULL DEFAULT 1;
ALTER TABLE knowledge_bases ADD COLUMN active         BOOLEAN  NOT NULL DEFAULT TRUE;

CREATE INDEX idx_kb_project      ON knowledge_bases(project);
CREATE INDEX idx_kb_document_key ON knowledge_bases(document_key);
CREATE INDEX idx_kb_active       ON knowledge_bases(active);
