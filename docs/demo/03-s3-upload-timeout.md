# 故障场景：S3 上传超时

## 故障现象

文件上传功能间歇性失败，用户上传附件后页面长时间 loading，最终返回"上传失败，请重试"。应用日志出现以下异常：

```
ERROR software.amazon.awssdk.core.internal.http.pipeline.stages.ApiCallTimeoutStage - Received exception when making HTTP request
software.amazon.awssdk.core.exception.ApiCallTimeoutException: Client failed to execute the http request in the allotted timeout duration of 30000ms
Caused by: java.net.SocketTimeoutException: connect timed out
    at java.base/sun.nio.ch.Net.connect(Native Method)
ERROR c.e.s.upload.FileUploadController - Upload failed for file=report_2024.xlsx, size=15MB
com.example.storage.StorageException: Failed to upload object to bucket 'app-uploads'
```

失败率约 30%，成功时延迟也在 8-15s（正常应 < 2s）。

## 环境信息

- Service: file-upload-service
- Environment: production
- Version: 1.8.3

## 根因分析

对象存储（S3 兼容存储）服务端在网络出口配置了连接速率限制（rate limiting），当并发上传请求超过 50 个/秒时，超出部分的连接会被延迟响应。业务侧在促销活动期间上传量激增（峰值 120 req/s），大量请求在 TCP 连接建立阶段超时。

同时，客户端 SDK 默认超时配置过短（connectTimeout=5s，apiCallTimeout=30s），且未配置重试策略，导致超时请求直接失败而不重试。

## 排查步骤

1. **确认失败模式**：分析上传失败日志的时间分布，发现失败集中在 10:00-12:00 和 14:00-16:00 的业务高峰期。统计失败率与并发上传量的关系，发现并发 > 50 req/s 时失败率急剧上升。

2. **检查网络连通性**：从应用服务器执行 `curl -w '@curl-format.txt' -o /dev/null -s https://s3.example.com/` 测试基础延迟，结果正常（< 50ms）。但在高峰期重复测试，发现 TCP 连接建立时间从 5ms 劣化到 3-5s，说明是服务端限流而非网络故障。

3. **验证存储端配置**：查看对象存储服务端日志，确认存在 `rate_limit_exceeded` 记录，限制为 50 connections/second per client。进一步确认该限制是运维在 2 周前为控制带宽成本而新增的安全策略。

4. **检查客户端配置**：审查 S3 SDK 初始化代码，发现超时和重试均为默认值：
   ```java
   S3Client.builder()
       .region(Region.US_EAST_1)
       .build();  // 未自定义超时和重试
   ```

## 解决方案

**即时修复**：

1. 调整 S3 SDK 超时和重试配置：
   ```java
   S3Client.builder()
       .region(Region.US_EAST_1)
       .overrideConfiguration(ClientOverrideConfiguration.builder()
           .apiCallTimeout(Duration.ofSeconds(60))
           .apiCallAttemptTimeout(Duration.ofSeconds(20))
           .retryPolicy(RetryPolicy.builder()
               .numRetries(3)
               .backoffStrategy(ExponentialBackoffStrategy.builder()
                   .baseDelay(Duration.ofMillis(500))
                   .maxBackoffTime(Duration.ofSeconds(5))
                   .build())
               .build())
           .build())
       .build();
   ```

2. 联系存储服务端运维，将连接速率限制从 50 req/s 提升至 200 req/s。

3. 在上传入口增加客户端限流（令牌桶），平滑上传峰值：
   ```java
   private final RateLimiter uploadRateLimiter = RateLimiter.create(40.0); // 40 req/s

   public UploadResult upload(MultipartFile file) {
       uploadRateLimiter.acquire();
       return s3Client.putObject(...);
   }
   ```

**预防措施**：

- S3 SDK 必须显式配置超时和重试策略，禁止使用默认值
- 上传接口增加客户端限流，避免突发流量触发服务端限流
- 大文件（> 10MB）使用分片上传（Multipart Upload），降低单次请求超时风险
- 配置上传成功率监控告警（< 99% 触发 P2）

## 相关配置

```yaml
app:
  storage:
    s3:
      endpoint: https://s3.example.com
      bucket: app-uploads
      connect-timeout: 5000ms
      api-call-timeout: 60000ms
      max-retries: 3
      multipart:
        threshold: 10MB
        part-size: 5MB
```
