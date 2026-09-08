package dev.toktokhan.invalidation.springboot.app;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link NoteRepositoryCustom} 의 프래그먼트 구현체입니다. Spring Data 가 클래스패스에서
 * 이름으로 찾아 내부적으로 인스턴스화합니다. {@code @Component}/{@code @Repository} 를
 * 붙이지 않았습니다 — 개발자가 직접 빈으로 등록하지 않는 것이 이 패턴의 요점입니다.
 *
 * <p>클래스 이름은 {@code NoteRepositoryCustom} + {@code Impl} 입니다. Spring Data Commons 의
 * {@code DefaultImplementationLookupConfiguration.getImplementationClassName()} 이 프래그먼트
 * 인터페이스의 단순 이름에 접미어("Impl")를 붙여 찾기 때문입니다(확인: spring-data-commons
 * 4.0.5 의 {@code DefaultImplementationLookupConfiguration} 바이트코드).
 *
 * <p><b>이것만 정답은 아닙니다.</b> 리포지토리 인터페이스 이름({@code NoteJpaRepository}) +
 * {@code Impl} 로 짓는 레거시 단일 커스텀 구현 경로(
 * {@code RepositoryBeanDefinitionBuilder.registerCustomImplementation})도 독립적으로 살아
 * 있고, 실제로 컨텍스트를 기동시키며 빈으로도 등록됩니다 — pirl-spring 의
 * {@code SlotInstanceRepositoryImpl implements SlotInstanceRepositoryCustom} (리포지토리
 * 인터페이스는 {@code SlotInstanceRepository}) 가 정확히 이 레거시 규칙을 씁니다. 이 사실은
 * "브리프대로 지었으면 기동에 실패했을 것"이라는 최초 보고서의 추측을 리뷰가 실제
 * {@code @SpringBootTest} 로 반증하며 확인됐습니다 — 직접 실행하지 않고 "명백하다"고
 * 단정한 결과였습니다. 두 이름 규칙이 서로 다른 기여자(signatureContributor)를 만들어내는
 * 차이는 {@code SpringProgramModel} 의 {@code buildRepositoryIndex} javadoc 에 적어
 * 뒀습니다. 이 픽스처는 프래그먼트 인터페이스 이름 규칙을, {@code app} 패키지의
 * {@code SlotInstanceRepository} 계열 픽스처는 레거시 규칙을 각각 검증합니다.
 *
 * <p>네이티브 쿼리를 씁니다. 이 프래그먼트는 리포지토리 인터페이스가 아니므로
 * {@link dev.toktokhan.invalidation.springboot.SpringProgramModel#entityFor} 가 이 클래스
 * 자체를 인터페이스로 잡을 수 없고, 워커가 이 본문까지 내려와야 UPSERT 대상 엔티티를
 * 찾습니다.
 *
 * <p>{@code public} 입니다 — 다른 패키지의 {@code SpringProgramModelTest} 가 이 클래스
 * 리터럴로 {@code implementationsOf} 의 결과를 단정해야 하기 때문입니다. Spring Data 의
 * 프래그먼트 탐지 자체는 가시성을 요구하지 않습니다.
 */
public class NoteRepositoryCustomImpl implements NoteRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public void upsert(Long id, String title) {
        entityManager.createNativeQuery("INSERT INTO note (id, title) VALUES (:id, :title)")
            .setParameter("id", id)
            .setParameter("title", title)
            .executeUpdate();
    }
}
