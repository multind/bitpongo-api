package com.multind.zhitoubao.auth;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeletedExternalIdentityRepository
        extends JpaRepository<DeletedExternalIdentityEntity, Long> {
    boolean existsByProviderAndSubject(String provider, String subject);
}
