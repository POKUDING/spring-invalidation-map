package dev.toktokhan.invalidation.springboot.app;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * Spring Data 의 흔한 관용구인 {@code @NoRepositoryBean} 베이스 리포지토리 인터페이스입니다.
 * {@link DeepRepository} 가 이를 상속해서 실제 빈으로 등록됩니다. {@link DeepFragment} 를
 * 여기서 직접 선언합니다 — {@link DeepRepository} 자신은 이를 선언하지 않고 상속만 합니다.
 */
@NoRepositoryBean
public interface DeepBaseRepository
    extends JpaRepository<DeepEntity, Long>, DeepFragment {
}
