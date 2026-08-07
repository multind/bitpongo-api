package com.multind.zhitoubao.notification;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DictRepository extends JpaRepository<DictEntity, Long> {
    List<DictEntity> findByTypeAndEnabledOrderBySequenceAsc(String type, Integer enabled);
    List<DictEntity> findByTypeAndSubTypeAndEnabledOrderBySequenceAsc(
            String type, String subType, Integer enabled);
}
