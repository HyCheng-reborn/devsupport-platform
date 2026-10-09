# 故障场景：Redis 内存溢出

## 故障现象

缓存服务大面积失效，应用日志频繁报出以下错误：

```
ERROR redis.clients.jedis.Connection - OOM command not allowed when used memory > 'maxmemory'.
ERROR o.s.d.r.core.RedisConnectionFailureTranslator - Redis command execution error: OOM command not allowed when used memory > 'maxmemory'.
WARN  c.e.h.CacheErrorHandlerImpl - Cache GET error for key=user:session:*, falling through to database
```

Redis 监控显示 `used_memory` 达到 `maxmemory` 上限（2GB），`evicted_keys` 速率为 0，`keyspace_hits` 骤降。

## 环境信息

- Service: user-service
- Environment: production
- Version: 3.1.0

## 根因分析

用户会话缓存（`user:session:*`）在写入时未设置过期时间，随着日活用户增长，session key 持续累积。经过 3 个月运行，session key 数量从初始的 5 万增长到 420 万，占用内存超过 2GB 上限。由于 `maxmemory-policy` 保持默认的 `noeviction`，Redis 拒绝所有写入命令，导致新 session 无法创建，旧 session 也无法读取，缓存层完全失效。

问题代码：

```java
// 缺少过期时间设置
public void saveSession(String userId, UserSession session) {
    String key = "user:session:" + userId;
    redisTemplate.opsForValue().set(key, session);  // 未调用 expire()
}
```

## 排查步骤

1. **确认内存状态**：执行 `redis-cli info memory` 查看关键指标：
   ```
   used_memory_human: 2.0G
   maxmemory_human: 2.0G
   maxmemory_policy: noeviction
   mem_fragmentation_ratio: 1.12
   ```
   确认内存已用满且淘汰策略为 noeviction。

2. **分析 key 分布**：执行 `redis-cli --bigkeys` 和 `redis-cli --hotkeys`，发现 `user:session:*` 类型的 key 占比超过 85%。进一步用 `redis-cli --scan --pattern "user:session:*" | wc -l` 统计数量为 420 万。

3. **检查 key 过期情况**：随机抽取 100 个 session key，执行 `TTL` 检查，全部返回 `-1`（永不过期），确认是写入时未设置 TTL 导致。

4. **追溯代码变更**：排查 session 写入逻辑，发现 `saveSession()` 方法在 3 个月前的重构中移除了 `expire()` 调用（重构前使用 `redisTemplate.opsForValue().set(key, session, 30, TimeUnit.MINUTES)`，重构后漏掉了 TTL 参数）。

## 解决方案

**即时修复**：

1. 修改 `maxmemory-policy` 为 `allkeys-lru`，允许 Redis 自动淘汰最近最少使用的 key：
   ```
   CONFIG SET maxmemory-policy allkeys-lru
   ```

2. 批量清理过期的 session key（使用 Lua 脚本安全删除）：
   ```bash
   redis-cli --scan --pattern "user:session:*" | head -n 100000 | xargs -L 100 redis-cli del
   ```

3. 修复代码，恢复 TTL 设置：
   ```java
   public void saveSession(String userId, UserSession session) {
       String key = "user:session:" + userId;
       redisTemplate.opsForValue().set(key, session, 30, TimeUnit.MINUTES);
   }
   ```

**预防措施**：

- 所有缓存写入必须设置 TTL，通过代码审查或自定义 Checkstyle 规则强制
- 配置 Redis 内存使用率告警（> 70% 触发 P2，> 85% 触发 P1）
- 定期执行 `redis-cli --bigkeys` 分析，监控异常 key 增长
- 生产环境 `maxmemory-policy` 禁止使用 `noeviction`

## 相关配置

```yaml
spring:
  data:
    redis:
      host: redis.internal
      port: 6379
      timeout: 3000ms
      lettuce:
        pool:
          max-active: 16
          max-idle: 8
          min-idle: 4

# Redis 服务端配置
# maxmemory 2gb
# maxmemory-policy allkeys-lru
```
