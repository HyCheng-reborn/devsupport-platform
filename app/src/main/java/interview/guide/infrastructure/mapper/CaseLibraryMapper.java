package interview.guide.infrastructure.mapper;

import interview.guide.modules.caselibrary.model.CaseDTO;
import interview.guide.modules.caselibrary.model.CaseEntity;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 案例实体到DTO的映射器
 */
@Mapper(
  componentModel = MappingConstants.ComponentModel.SPRING,
  unmappedTargetPolicy = ReportingPolicy.IGNORE
)
public interface CaseLibraryMapper {

  /**
   * 将案例实体转换为DTO
   */
  CaseDTO toDTO(CaseEntity entity);

  /**
   * 将案例实体列表转换为DTO列表
   */
  List<CaseDTO> toDTOList(List<CaseEntity> entities);
}
