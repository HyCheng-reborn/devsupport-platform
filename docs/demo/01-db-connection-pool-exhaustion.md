# 故障场景：数据库连接池耗尽

## 故障现象

应用响应时间从平均 50ms 劣化到 5s 以上，部分请求返回 500 错误。日志中频繁出现以下告警：

```
WARN  com.zaxxer.hikari.pool.HikariPool - HikariPool-1 - Connection is not available, request timed out after 30000ms.
ERROR o.h.engine.jdbc.spi.SqlExceptionHelper - SQL Error: 0, SQLState: null
ERROR o.h.engine.jdbc.spi.SqlExceptionHelper - HikariPool-1 - Connection is not available, request timed out after 30000ms.
```

HikariCP 监控指标显示 active connections 持续等于 maximum pool size（20），idle connections 降为 0。

## 环境信息

- Service: order-service
- Environment: production
- Version: 2.4.1

## 根因分析

一个新增的报表查询缺少分页限制，在数据量超过 50 万行后演变为慢查询（执行时间 > 30s）。该查询长时间占用数据库连接不释放，导致连接池中的 20 个连接在高峰期全部被占满。后续请求无法获取连接，触发 30s 超时。

具体问题 SQL：

```sql
-- 缺少 LIMIT 和 WHERE 条件，全表扫描 + 文件排序
SELECT o.*, u.name, u.email, p.product_name, p.category
FROM orders o
JOIN users u ON o.user_id = u.id
JOIN order_items oi ON o.id = oi.order_id
JOIN products p ON oi.product_id = p.id
ORDER BY o.created_at DESC;
```

该查询在数据量小时（< 1 万行）表现正常（< 200ms），但随着订单表增长到 50 万行以上，执行时间飙升至 30s+，且未走索引（`EXPLAIN` 显示 `Using filesort` + `Using temporary`）。

## 排查步骤

1. **确认连接池状态**：通过 Actuator 端点 `/actuator/metrics/hikaricp.connections.active` 查看 active connections 趋势，确认持续满载。同时查看 `hikaricp.connections.pending` 发现大量等待请求排队。

2. **定位慢查询**：开启 MySQL 慢查询日志（`long_query_time = 1`），等待 5 分钟后分析 slow.log，发现上述报表查询执行时间为 32.7s，出现频率最高。

3. **分析执行计划**：对该 SQL 执行 `EXPLAIN ANALYZE`，发现：
   - `orders` 表全表扫描（缺少 `created_at` 索引）
   - `Using filesort`（`ORDER BY` 未命中索引）
   - `Using temporary`（JOIN 中间结果集过大）

4. **确认触发时机**：排查代码提交记录，发现该查询是 3 天前上线的报表导出功能引入，恰好在数据量超过阈值后暴露问题。

## 解决方案

**即时修复**：

1. 为查询添加分页限制和合理的 WHERE 条件：

```sql
SELECT o.*, u.name, u.email, p.product_name, p.category
FROM orders o
JOIN users u ON o.user_id = u.id
JOIN order_items oi ON o.id = oi.order_id
JOIN products p ON oi.product_id = p.id
WHERE o.created_at >= DATE_SUB(NOW(), INTERVAL 90 DAY)
ORDER BY o.created_at DESC
LIMIT 1000 OFFSET 0;
```

2. 添加复合索引：

```sql
ALTER TABLE orders ADD INDEX idx_created_at_desc (created_at DESC);
```

**预防措施**：

- 所有新增 SQL 必须经过 `EXPLAIN` 审查，禁止无 LIMIT 的全表查询上线
- 配置 HikariCP 连接泄漏检测：`leak-detection-threshold=60000`
- 设置报表查询独立数据源，与主业务连接池隔离
- 添加连接池活跃连接数告警阈值（> 80% pool size 时触发 P2 告警）

## 相关配置

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      leak-detection-threshold: 60000
      max-lifetime: 1800000
```
