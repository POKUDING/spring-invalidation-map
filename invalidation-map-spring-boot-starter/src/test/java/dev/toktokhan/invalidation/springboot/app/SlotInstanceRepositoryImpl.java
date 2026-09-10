package dev.toktokhan.invalidation.springboot.app;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link SlotInstanceRepositoryCustom} 의 프래그먼트 구현체입니다. 클래스 이름을 프래그먼트
 * 인터페이스({@code SlotInstanceRepositoryCustom}) 가 아니라 <b>리포지토리 인터페이스</b>
 * ({@link SlotInstanceRepository}) 이름 + {@code Impl} 로 지었습니다 — pirl-spring 의
 * {@code SlotInstanceRepositoryImpl implements SlotInstanceRepositoryCustom} 이 실제로
 * 이 규칙을 씁니다.
 *
 * <p>이 레거시 규칙에서는 {@code RepositoryInformation.getFragments()} 의
 * {@code getSignatureContributor()} 가 프래그먼트 인터페이스가 아니라 이 구현체 클래스
 * 자체를 돌려줍니다({@code RepositoryBeanDefinitionBuilder.registerCustomImplementation}
 * 경로 — {@code registerRepositoryFragmentsImplementation} 과는 다른 경로입니다). 그래서
 * {@code SpringProgramModel.buildRepositoryIndex} 는 프래그먼트 루프만으로는
 * {@code SlotInstanceRepositoryCustom} 을 어떤 키로도 등록하지 못하고, 리포지토리
 * 인터페이스가 직접 선언한 인터페이스를 훑는 별도 경로로 이를 메웁니다.
 */
public class SlotInstanceRepositoryImpl implements SlotInstanceRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public void touch(Long id) {
        entityManager.createNativeQuery("UPDATE slot_instance SET status = status WHERE id = :id")
            .setParameter("id", id)
            .executeUpdate();
    }
}
