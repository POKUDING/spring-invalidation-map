package dev.toktokhan.invalidation.springboot.app;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link NoteRepositoryCustom} 의 프래그먼트 구현체입니다. Spring Data 가 클래스패스에서
 * 이름으로 찾아 내부적으로 인스턴스화합니다. {@code @Component}/{@code @Repository} 를
 * 붙이지 않았습니다 — 개발자가 직접 빈으로 등록하지 않는 것이 이 패턴의 요점입니다.
 *
 * <p>클래스 이름은 {@code NoteRepositoryCustom} + {@code Impl} 이어야 합니다. Spring Data
 * Commons 의 {@code DefaultImplementationLookupConfiguration.getImplementationClassName()}
 * 이 프래그먼트 인터페이스의 단순 이름에 접미어("Impl")를 붙여 찾기 때문입니다(확인:
 * spring-data-commons 4.0.5 의 {@code DefaultImplementationLookupConfiguration} 바이트코드).
 * 리포지토리 인터페이스 이름({@code NoteJpaRepository})은 여기 관여하지 않습니다 — 그 이름
 * 기준의 조회는 별도의(레거시) 단일 커스텀 구현 경로이고, 프래그먼트 경로와는 다릅니다.
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
