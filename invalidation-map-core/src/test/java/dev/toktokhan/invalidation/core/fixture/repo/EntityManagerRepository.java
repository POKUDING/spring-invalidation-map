package dev.toktokhan.invalidation.core.fixture.repo;

import dev.toktokhan.invalidation.core.fixture.entity.Trip;
import dev.toktokhan.invalidation.core.fixture.entity.TripLeg;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

public class EntityManagerRepository {

    @PersistenceContext
    private EntityManager em;

    private Trip cached;

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

    /**
     * 후보 중 하나는 READ, 다른 하나는 WRITE 인 픽스처입니다. 전체 방향이 WRITE 로
     * 승격되는지 검증하는 데 씁니다.
     */
    public void queryMixedDirections() {
        em.createQuery("select t from Trip t");
        em.createQuery("update TripLeg l set l.id = l.id");
    }

    /**
     * {@code em.persist(new Trip())} 관용구입니다. {@code persist} 의 디스크립터는
     * {@code (Ljava/lang/Object;)V} 라 호출 지점의 인자 타입으로는 엔티티를 알 수 없고,
     * 이 메서드가 {@code NEW} 로 만든 타입에서만 드러납니다.
     */
    public void persistNew() {
        em.persist(new Trip());
    }

    /**
     * 파라미터로 받은 엔티티를 저장합니다. 후보는 이 메서드 자신의 파라미터 타입에서
     * 나옵니다 — {@code NEW} 도 클래스 리터럴도 없습니다.
     */
    public void persistParameter(TripLeg leg) {
        em.persist(leg);
    }

    /** 필드에 들고 있는 엔티티를 저장합니다. 후보는 읽은 필드의 선언 타입에서 나옵니다. */
    public void persistField() {
        em.persist(cached);
    }

    /** {@code em.merge} 와 {@code em.remove} 도 쓰기입니다. */
    public void mergeAndRemove(Trip trip) {
        em.merge(trip);
        em.remove(trip);
    }

    /**
     * {@code em.find(Trip.class, id)} 관용구입니다. 첫 인자는 클래스 리터럴이므로
     * {@code LDC} 로 실린 클래스 상수에서 후보가 나옵니다.
     */
    public Object findById(Long id) {
        return em.find(Trip.class, id);
    }

    /** {@code em.getReference} 도 읽기입니다. */
    public Object reference(Long id) {
        return em.getReference(TripLeg.class, id);
    }

    /**
     * 엔티티 타입이 이 메서드의 어디에도(파라미터·NEW·클래스 리터럴·필드) 나타나지
     * 않습니다. 엔티티를 특정할 수 없으므로 조용히 버리지 않고 미해결로 드러나야 합니다.
     */
    public void persistOpaque(Object opaque) {
        em.persist(opaque);
    }

    /**
     * {@code createQuery} 를 부르지만 이 메서드의 문자열 상수 중 어느 것도 엔티티로
     * 풀리지 않습니다(Criteria API 를 쓰거나 쿼리를 다른 곳에서 받아 오는 경우). 판정할
     * 수 없는 자리이므로 미해결로 드러나야 합니다.
     */
    public Object queryUnresolvable(String jpql) {
        return em.createQuery(jpql).getSingleResult();
    }
}
