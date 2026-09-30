-- 为 rag_chat_messages 表新增来源记录和完成状态
ALTER TABLE rag_chat_messages ADD COLUMN sources_json TEXT;
ALTER TABLE rag_chat_messages ADD COLUMN status VARCHAR(32) DEFAULT 'COMPLETED';
