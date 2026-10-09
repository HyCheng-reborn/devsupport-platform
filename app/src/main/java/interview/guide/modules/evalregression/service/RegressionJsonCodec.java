package interview.guide.modules.evalregression.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.modules.evalregression.model.TopKSnapshotEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.Named;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 回归模块 JSON 编解码器。
 * <p>
 * 三张表的列表 / 快照字段以序列化后的 JSON 字符串存储在 TEXT 列中，
 * 该组件负责 String ↔ List 的双向转换，供 Service 写库与 MapStruct 读库复用。
 * 解析失败时降级为空列表并记录告警，避免历史脏数据阻断整轮读取。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegressionJsonCodec {

  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
  private static final TypeReference<List<TopKSnapshotEntry>> SNAPSHOT_LIST = new TypeReference<>() {};

  private final ObjectMapper objectMapper;

  /**
   * 将任意值序列化为 JSON 字符串；null 返回 null，失败降级为 "[]"。
   * <p>
   * 标注 {@link Named} 是为了让 MapStruct 不把它当作 Object→String 的自动候选方法，
   * 否则所有 String / 枚举 → String 的目标字段都会被误加 JSON 引号。
   * 该方法仅供 Service 写库时显式调用。
   */
  @Named("jsonWrite")
  public String toJson(Object value) {
    if (value == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      log.warn("回归数据序列化为 JSON 失败，降级为空数组: {}", e.getMessage(), e);
      return "[]";
    }
  }

  /**
   * JSON 字符串 → List&lt;String&gt;，供 MapStruct 通过 qualifiedByName 调用。
   */
  @Named("jsonToStringList")
  public List<String> toStringList(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, STRING_LIST);
    } catch (Exception e) {
      log.warn("回归字符串列表解析失败，降级为空列表: {}", e.getMessage(), e);
      return List.of();
    }
  }

  /**
   * JSON 字符串 → List&lt;TopKSnapshotEntry&gt;，供 MapStruct 通过 qualifiedByName 调用。
   */
  @Named("jsonToSnapshotList")
  public List<TopKSnapshotEntry> toSnapshotList(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, SNAPSHOT_LIST);
    } catch (Exception e) {
      log.warn("回归 top-K 快照解析失败，降级为空列表: {}", e.getMessage(), e);
      return List.of();
    }
  }
}
