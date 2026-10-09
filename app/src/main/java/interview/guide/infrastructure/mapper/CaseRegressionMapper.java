package interview.guide.infrastructure.mapper;

import interview.guide.modules.evalregression.model.CaseRegressionItemEntity;
import interview.guide.modules.evalregression.model.CaseRegressionResultEntity;
import interview.guide.modules.evalregression.model.CaseRegressionRunEntity;
import interview.guide.modules.evalregression.model.RegressionItemDTO;
import interview.guide.modules.evalregression.model.RegressionResultDTO;
import interview.guide.modules.evalregression.model.RegressionRunDetailDTO;
import interview.guide.modules.evalregression.model.RegressionRunSummaryDTO;
import interview.guide.modules.evalregression.service.RegressionJsonCodec;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 案例回归评测 Entity → DTO 映射器。
 * <p>
 * 列表 / 快照字段以 JSON 字符串存储，通过 {@link RegressionJsonCodec} 的具名方法反序列化；
 * caseTitle / caseStatus / results 等跨表字段由调用方作为额外源参数传入。
 */
@Mapper(
  componentModel = MappingConstants.ComponentModel.SPRING,
  uses = RegressionJsonCodec.class,
  unmappedTargetPolicy = ReportingPolicy.IGNORE
)
public interface CaseRegressionMapper {

  @Mapping(target = "caseTitle", source = "caseTitle")
  @Mapping(target = "caseStatus", source = "caseStatus")
  @Mapping(target = "expectedEvidence", source = "entity.expectedEvidence", qualifiedByName = "jsonToStringList")
  @Mapping(target = "keyPoints", source = "entity.keyPoints", qualifiedByName = "jsonToStringList")
  @Mapping(target = "evidenceSource", source = "entity.evidenceSource")
  RegressionItemDTO toItemDTO(CaseRegressionItemEntity entity, String caseTitle, String caseStatus);

  RegressionRunSummaryDTO toRunSummaryDTO(CaseRegressionRunEntity entity);

  @Mapping(target = "results", source = "results")
  RegressionRunDetailDTO toRunDetailDTO(CaseRegressionRunEntity entity, List<RegressionResultDTO> results);

  @Mapping(target = "caseTitle", source = "caseTitle")
  @Mapping(target = "retrievedEvidenceIds", source = "entity.retrievedEvidenceIds", qualifiedByName = "jsonToStringList")
  @Mapping(target = "matchedKeyPoints", source = "entity.matchedKeyPoints", qualifiedByName = "jsonToStringList")
  @Mapping(target = "missingKeyPoints", source = "entity.missingKeyPoints", qualifiedByName = "jsonToStringList")
  @Mapping(target = "topKSnapshot", source = "entity.topkSnapshot", qualifiedByName = "jsonToSnapshotList")
  RegressionResultDTO toResultDTO(CaseRegressionResultEntity entity, String caseTitle);
}
