package dev.toktokhan.invalidation.springboot.app;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * pirl-spring 의 {@code SlotInstance} 와 같은 이름의 픽스처 엔티티입니다.
 * {@link SlotInstanceRepository} 계열이 pirl-spring 이 실제로 쓰는 리포지토리 인터페이스
 * 이름 규칙(레거시: 리포지토리 인터페이스 이름 + {@code Impl})을 재현합니다.
 */
@Entity
public class SlotInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String status;

    protected SlotInstance() {
        // JPA
    }

    public SlotInstance(String status) {
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }
}
