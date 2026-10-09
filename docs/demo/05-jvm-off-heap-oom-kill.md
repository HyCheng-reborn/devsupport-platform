# 故障场景：JVM 堆外内存泄漏导致 OOM Kill

## 故障现象

Java 进程在运行 48-72 小时后突然退出，无 JVM crash dump。系统日志（`dmesg`）显示：

```
[Thu Oct  3 02:14:32 2024] Out of memory: Kill process 18234 (java) score 901 or sacrifice child
[Thu Oct  3 02:14:32 2024] Killed process 18234 (java) total-vm:8523412kB, anon-rss:6291456kB, file-rss:0kB, shmem-rss:0kB
[Thu Oct  3 02:14:33 2024] oom_reaper: reaped process 18234 (java), now anon-rss:0kB, file-rss:0kB, shmem-rss:0kB
```

JVM 配置堆大小为 2GB（`-Xmx2g`），但进程总虚拟内存达到 8.5GB，物理内存占用 6GB。重启后短期内内存正常，但 24 小时后 RSS 再次增长到 5GB 以上。

## 环境信息

- Service: data-pipeline-service
- Environment: production
- Version: 1.5.2

## 根因分析

服务使用 Netty 进行高性能网络通信，内部通过 `DirectByteBuffer` 分配堆外内存。代码中有一处 Netty `ByteBuf` 使用后未调用 `release()`，导致堆外内存持续泄漏。

问题代码：

```java
public class DataMessageHandler extends SimpleChannelInboundHandler<DataMessage> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DataMessage msg) {
        ByteBuf content = msg.content();
        // 读取数据后未 release ByteBuf
        byte[] data = new byte[content.readableBytes()];
        content.readBytes(data);
        processData(data);
        // 缺少 content.release() 或 ReferenceCountUtil.release(msg)
    }
}
```

每次处理消息泄漏约 2-8KB 堆外内存。在消息吞吐量为 5000 msg/s 的情况下，每天泄漏约 1-5GB 堆外内存。由于堆外内存不受 `-Xmx` 限制，JVM 不会触发 GC 回收，最终系统 OOM Killer 介入。

## 排查步骤

1. **确认内存分布**：进程被 Kill 前，通过 `/proc/18234/smaps_rollup` 查看内存分布：
   ```
   Total: 6291456 kB
   Rss:   6291456 kB
   Pss:   6291456 kB
   Shared_Clean: 0 kB
   Private_Dirty: 6291456 kB
   ```
   物理内存 6GB 远超 JVM 堆大小 2GB，说明大量内存分配在堆外。

2. **检查 JVM 内存参数**：确认启动参数 `-Xmx2g -Xms2g`，堆大小固定。未设置 `-XX:MaxDirectMemorySize`，默认等于 `-Xmx`（2GB），但实际堆外使用已远超此限制（Linux 层面不强制该限制）。

3. **分析堆外内存增长趋势**：通过 JMX 指标 `java.nio.type:DirectBuffer` 的 `memoryUsed` 监控，发现 DirectMemory 使用量呈线性增长，每 24 小时增长约 2GB，且 GC 后不回落，确认存在堆外内存泄漏。

4. **定位泄漏代码**：使用 `-XX:NativeMemoryTracking=summary` 启动 JVM，通过 `jcmd 18234 VM.native_memory summary` 发现 `Internal` 类别内存持续增长。进一步使用 Async Profiler 采样堆外内存分配调用栈，定位到 `DataMessageHandler.channelRead0()` 方法中的 `ByteBuf` 未释放。

## 解决方案

**即时修复**：

1. 修复 ByteBuf 泄漏，确保每次使用后释放：
   ```java
   @Override
   protected void channelRead0(ChannelHandlerContext ctx, DataMessage msg) {
       ByteBuf content = msg.content();
       try {
           byte[] data = new byte[content.readableBytes()];
           content.readBytes(data);
           processData(data);
       } finally {
           ReferenceCountUtil.release(msg);
       }
   }
   ```

2. 显式限制堆外内存上限，防止无限增长：
   ```
   -XX:MaxDirectMemorySize=1g
   ```

**预防措施**：

- Netty `ByteBuf` 使用遵循"谁分配谁释放"原则，Handler 中接收的 `ByteBuf` 必须在 `finally` 块中释放
- 配置 `-XX:NativeMemoryTracking=summary` 用于生产环境内存监控（性能开销约 5%）
- 添加 DirectMemory 使用量监控告警（> 70% MaxDirectMemorySize 触发 P2）
- 代码审查中重点关注 Netty Handler 的 `ReferenceCounted` 对象释放

## 相关配置

```bash
# JVM 启动参数
JAVA_OPTS="
  -Xmx2g
  -Xms2g
  -XX:MaxDirectMemorySize=1g
  -XX:NativeMemoryTracking=summary
  -XX:+HeapDumpOnOutOfMemoryError
  -XX:HeapDumpPath=/var/log/app/heapdump.hprof
  -XX:ErrorFile=/var/log/app/hs_err_pid%p.log
"
```

```yaml
# 应用内存监控配置（Prometheus JMX Exporter）
memory:
  rules:
    - pattern: 'java.nio<type=DirectBuffer><>MemoryUsed'
      name: jvm_direct_memory_used_bytes
      help: Direct buffer memory used
    - pattern: 'java.nio<type=DirectBuffer><>MemoryLimit'
      name: jvm_direct_memory_limit_bytes
      help: Direct buffer memory limit
```
