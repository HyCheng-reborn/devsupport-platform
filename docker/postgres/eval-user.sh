#!/bin/bash
# P1-C 评测专用 eval_runner 用户与最小权限授予
# 由 docker-compose-eval.yml 挂载到 /docker-entrypoint-initdb.d/20-eval-user.sh
# Postgres 官方镜像在启动时会按文件名字典序 source 本目录下的 .sh
#
# 密码来源：容器环境变量 EVAL_RUNNER_PASSWORD（必填，缺失即退出）。
# 仓库中不出现任何默认密码，避免被误用。
# 通过 psql -v + :'var' 引号语法传参，避免密码含特殊字符时 SQL 注入。
set -euo pipefail

: "${EVAL_RUNNER_PASSWORD:?必须通过 docker-compose --env-file .env.eval 提供 EVAL_RUNNER_PASSWORD}"

DB_NAME="${POSTGRES_DB:-interview_guide_eval}"

# 只在角色不存在时创建；psql 变量替换在顶层 SQL 生效，不进 DO 块
psql -v ON_ERROR_STOP=1 \
     -v runner_pw="${EVAL_RUNNER_PASSWORD}" \
     -U postgres -d "${DB_NAME}" <<'SQL'
SELECT format('CREATE ROLE eval_runner WITH LOGIN PASSWORD %L', :'runner_pw')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'eval_runner')
\gexec
SQL

# GRANT：DB 名不能用 psql 变量占位，此处 DB 由本地 compose 控制，非用户输入
psql -v ON_ERROR_STOP=1 -U postgres -d "${DB_NAME}" <<SQL
GRANT CONNECT ON DATABASE ${DB_NAME} TO eval_runner;
GRANT USAGE ON SCHEMA public TO eval_runner;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE vector_store TO eval_runner;
GRANT SELECT ON TABLE eval_instance_identity TO eval_runner;
SQL
