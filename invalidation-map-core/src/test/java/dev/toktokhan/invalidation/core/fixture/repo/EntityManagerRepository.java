package dev.toktokhan.invalidation.core.fixture.repo;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

public class EntityManagerRepository {

    @PersistenceContext
    private EntityManager em;

    public void upsert(Long id, String title) {
        em.createNativeQuery(
                "INSERT INTO trip_log (id, title) VALUES (?, ?) ON CONFLICT DO NOTHING")
            .setParameter(1, id)
            .setParameter(2, title)
            .executeUpdate();
    }

    public void bulkRename(String title) {
        em.createQuery("update Trip t set t.title = :title")
            .setParameter("title", title)
            .executeUpdate();
    }

    public Object load(Long id) {
        return em.createQuery("select t from Trip t where t.id = :id")
            .setParameter("id", id)
            .getSingleResult();
    }

    /**
     * 한 메서드가 쿼리 문자열을 두 개 담고 있는 픽스처입니다. EntityManagerResolver 는
     * 호출 지점 인자를 스택에서 추적하지 않고 메서드의 문자열 상수 전체를 후보로
     * 쓰므로, 두 문자열이 각각 다른 엔티티로 풀리면 결과가 합쳐져야(과잉) 합니다.
     */
    public void queryTwoEntities() {
        em.createQuery("select t from Trip t");
        em.createQuery("select l from TripLeg l");
    }
}
