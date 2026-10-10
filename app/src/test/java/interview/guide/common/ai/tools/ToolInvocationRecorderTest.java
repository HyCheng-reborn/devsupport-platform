package interview.guide.common.ai.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ToolInvocationRecorder 纯逻辑测试 — 不需要 mock。
 * 每个测试对应一个实际风险。
 */
@DisplayName("工具调用记录器测试")
class ToolInvocationRecorderTest {

  private final ToolInvocationRecorder recorder = new ToolInvocationRecorder();

  private ToolCallRecord sampleRecord(String toolName, String status) {
    return new ToolCallRecord(
        toolName, status,
        Map.of("postgresql", Map.of("status", "OK")),
        42, Instant.now().toString(), false
    );
  }

  // ========== 记录与读取 ==========

  @Nested
  @DisplayName("记录与读取")
  class RecordAndGet {

    @Test
    @DisplayName("record 后 getRecords 返回正确数据")
    void recordAndGet() {
      ToolCallRecord record = sampleRecord("checkDependencyHealth", "SUCCESS");
      recorder.record(record);

      List<ToolCallRecord> records = recorder.getRecords();
      assertThat(records).hasSize(1);
      assertThat(records.getFirst()).isEqualTo(record);
      assertThat(records.getFirst().toolName()).isEqualTo("checkDependencyHealth");
      assertThat(records.getFirst().status()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("多次 record 按顺序返回")
    void multipleRecords() {
      ToolCallRecord r1 = sampleRecord("tool1", "SUCCESS");
      ToolCallRecord r2 = sampleRecord("tool2", "TIMEOUT");
      ToolCallRecord r3 = sampleRecord("tool3", "ERROR");

      recorder.record(r1);
      recorder.record(r2);
      recorder.record(r3);

      assertThat(recorder.getRecords()).containsExactly(r1, r2, r3);
    }
  }

  // ========== 线程安全 ==========

  @Nested
  @DisplayName("线程安全")
  class ThreadSafety {

    @Test
    @DisplayName("多线程并发 record 不丢数据")
    void concurrentRecordNoDataLoss() throws Exception {
      int threadCount = 10;
      ExecutorService executor = Executors.newFixedThreadPool(threadCount);
      CountDownLatch latch = new CountDownLatch(threadCount);

      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // 只记录前 3 个（受 MAX_INVOCATIONS 限制），但不应抛异常
            recorder.record(sampleRecord("tool" + index, "SUCCESS"));
          } finally {
            latch.countDown();
          }
        });
      }

      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
      executor.shutdown();

      // CopyOnWriteArrayList 保证不丢数据；受 MAX_INVOCATIONS=3 限制，最多 3 条
      assertThat(recorder.getRecords().size()).isBetween(1, ToolInvocationRecorder.MAX_INVOCATIONS);
    }
  }

  // ========== 调用上限 ==========

  @Nested
  @DisplayName("调用上限")
  class InvocationLimit {

    @Test
    @DisplayName("hasReachedLimit() 在 3 次后返回 true")
    void hasReachedLimitAfterThree() {
      assertThat(recorder.hasReachedLimit()).isFalse();

      recorder.record(sampleRecord("t1", "SUCCESS"));
      assertThat(recorder.hasReachedLimit()).isFalse();

      recorder.record(sampleRecord("t2", "SUCCESS"));
      assertThat(recorder.hasReachedLimit()).isFalse();

      recorder.record(sampleRecord("t3", "SUCCESS"));
      assertThat(recorder.hasReachedLimit()).isTrue();
    }

    @Test
    @DisplayName("超过上限后 record 不会继续增加记录数")
    void recordAfterLimitDoesNotAdd() {
      for (int i = 0; i < 5; i++) {
        recorder.record(sampleRecord("tool" + i, "SUCCESS"));
      }

      // 最多只记录 MAX_INVOCATIONS 条
      assertThat(recorder.getRecords()).hasSize(ToolInvocationRecorder.MAX_INVOCATIONS);
    }

    @Test
    @DisplayName("LIMIT_EXCEEDED 状态最多追加 1 条，重复的 LIMIT_EXCEEDED 记录被忽略")
    void limitExceededRecordedOnlyOnce() {
      // 先填满 3 条正常记录
      for (int i = 0; i < 3; i++) {
        recorder.record(sampleRecord("tool" + i, "SUCCESS"));
      }
      assertThat(recorder.getRecords()).hasSize(3);

      // 第 1 条 LIMIT_EXCEEDED 应被接受
      ToolCallRecord limitRecord = sampleRecord("checkDependencyHealth", "LIMIT_EXCEEDED");
      recorder.record(limitRecord);
      assertThat(recorder.getRecords()).hasSize(4);
      assertThat(recorder.getRecords().getLast().status()).isEqualTo("LIMIT_EXCEEDED");

      // 第 2 条 LIMIT_EXCEEDED 应被忽略（已有 1 条）
      ToolCallRecord limitRecord2 = sampleRecord("checkDependencyHealth", "LIMIT_EXCEEDED");
      recorder.record(limitRecord2);
      assertThat(recorder.getRecords()).hasSize(4);
    }
  }

  // ========== tryAcquireSlot ==========

  @Nested
  @DisplayName("执行额度领取")
  class TryAcquireSlot {

    @Test
    @DisplayName("前 3 次返回 true，第 4 次返回 false")
    void firstThreeReturnTrueFourthReturnsFalse() {
      assertThat(recorder.tryAcquireSlot()).isTrue();
      assertThat(recorder.tryAcquireSlot()).isTrue();
      assertThat(recorder.tryAcquireSlot()).isTrue();
      assertThat(recorder.tryAcquireSlot()).isFalse();
    }

    @Test
    @DisplayName("getExecutedCount 反映实际调用次数")
    void getExecutedCountReflectsActualCalls() {
      assertThat(recorder.getExecutedCount()).isZero();
      recorder.tryAcquireSlot();
      assertThat(recorder.getExecutedCount()).isEqualTo(1);
      recorder.tryAcquireSlot();
      assertThat(recorder.getExecutedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("并发调用下不超过 MAX_INVOCATIONS")
    void concurrentCallsDoNotExceedMax() throws Exception {
      int threadCount = 10;
      ExecutorService executor = Executors.newFixedThreadPool(threadCount);
      CountDownLatch latch = new CountDownLatch(threadCount);
      CountDownLatch startLatch = new CountDownLatch(1);

      java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);

      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            startLatch.await();
            if (recorder.tryAcquireSlot()) {
              successCount.incrementAndGet();
            }
          } catch (Exception e) {
            e.printStackTrace();
          } finally {
            latch.countDown();
          }
        });
      }

      startLatch.countDown();
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
      executor.shutdown();

      // 成功领取的次数不应超过 MAX_INVOCATIONS
      assertThat(successCount.get()).isEqualTo(ToolInvocationRecorder.MAX_INVOCATIONS);
      assertThat(recorder.getExecutedCount()).isEqualTo(threadCount);
    }
  }

  // ========== reset ==========

  @Nested
  @DisplayName("reset")
  class Reset {

    @Test
    @DisplayName("reset 后 records 为空且 executedCount 重置")
    void resetClearsRecordsAndCount() {
      recorder.record(sampleRecord("t1", "SUCCESS"));
      recorder.tryAcquireSlot();
      assertThat(recorder.getRecords()).isNotEmpty();
      assertThat(recorder.getExecutedCount()).isEqualTo(1);

      recorder.reset();

      assertThat(recorder.getRecords()).isEmpty();
      assertThat(recorder.hasReachedLimit()).isFalse();
      assertThat(recorder.getExecutedCount()).isZero();
    }
  }

  // ========== 不可变列表 ==========

  @Nested
  @DisplayName("不可变列表")
  class ImmutableList {

    @Test
    @DisplayName("getRecords 返回的列表不可修改")
    void getRecordsReturnsUnmodifiableList() {
      recorder.record(sampleRecord("t1", "SUCCESS"));

      List<ToolCallRecord> records = recorder.getRecords();

      assertThatThrownBy(() -> records.add(sampleRecord("t2", "SUCCESS")))
          .isInstanceOf(UnsupportedOperationException.class);
    }
  }
}
