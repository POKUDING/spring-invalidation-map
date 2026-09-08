package dev.toktokhan.invalidation.springboot.app;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * {@link DeepFragment} 가 {@link DeepBaseRepository} 를 통해서만 간접 상속되는 구조(
 * {@code @NoRepositoryBean} 베이스 리포지토리 관용구)를 검증하는 픽스처 엔티티입니다.
 */
@Entity
public class DeepEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    protected DeepEntity() {
        // JPA
    }

    public Long getId() {
        return id;
    }
}
