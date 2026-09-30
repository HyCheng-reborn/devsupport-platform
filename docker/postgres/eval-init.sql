-- P1-C 评测专用 Postgres 初始化脚本
-- 由 docker-compose-eval.yml 挂载到 /docker-entrypoint-initdb.d/init.sql
-- 仅在容器首次初始化（volume 为空）时执行

-- 1. 向量扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. 实例标记表（用于 Java 端身份核验，区分评测容器与生产实例）
-- 固定 UUID 是仓库常量，不能单独证明物理容器身份；
-- 运行前仍须核对连接 URL、Compose 目标和标记返回值。
CREATE TABLE eval_instance_identity (
    marker_key   TEXT PRIMARY KEY,
    marker_value TEXT NOT NULL
);

INSERT INTO eval_instance_identity (marker_key, marker_value) VALUES
    ('instance_type', 'p1c-eval-isolated'),
    ('instance_uuid', 'f47ac10b-58cc-4372-a567-0e0283c5d9e7');

-- 3. 预建 vector_store 表（PgVectorStore 期望的 schema）
-- 评测代码使用 initializeSchema=false，不再尝试 CREATE EXTENSION / CREATE TABLE
CREATE TABLE IF NOT EXISTS vector_store (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    content text,
    metadata json,
    embedding vector(1024)
);

CREATE INDEX IF NOT EXISTS spring_ai_vector_index
    ON vector_store
    USING hnsw (embedding vector_cosine_ops);

-- 4. 专用评测用户（最小权限）
-- 密码与 Gradle systemProperty 默认值一致；生产环境应通过环境变量覆盖
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'eval_runner') THEN
        CREATE ROLE eval_runner WITH LOGIN PASSWORD 'eval_runner_2026';
    END IF;
END
$$;

-- eval_runner 权限：仅对评测所需表有 DML 权限
GRANT CONNECT ON DATABASE interview_guide_eval TO eval_runner;
GRANT USAGE ON SCHEMA public TO eval_runner;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE vector_store TO eval_runner;
GRANT SELECT ON TABLE eval_instance_identity TO eval_runner;

-- 显式声明：eval_runner 不具有超级用户或建库权限
-- （CREATE ROLE 默认 rolsuper=false, rolcreatedb=false，此处无需额外设置）
