package com.multind.zhitoubao.notification;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DictRepository extends JpaRepository<DictEntity, Long> {
    List<DictEntity> findByTypeAndEnabledOrderBySequenceAsc(String type, Integer enabled);
    List<DictEntity> findByTypeAndSubTypeAndEnabledOrderBySequenceAsc(
            String type, String subType, Integer enabled);
    Optional<DictEntity> findFirstByCode(String code);
}
