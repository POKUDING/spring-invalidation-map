package dev.toktokhan.invalidation.core.fixture.web;

import dev.toktokhan.invalidation.core.annotation.InvalidationMapIgnore;
import dev.toktokhan.invalidation.core.annotation.WritesEntities;
import dev.toktokhan.invalidation.core.fixture.entity.Trip;
import dev.toktokhan.invalidation.core.fixture.entity.TripLeg;
import dev.toktokhan.invalidation.core.fixture.repo.TripJpaRepository;
import dev.toktokhan.invalidation.core.fixture.service.TripService;
import org.springframework.transaction.annotation.Transactional;

public class TripController implements TripEndpoints {

    private TripJpaRepository repository;
    private TripService service;

    /** 리포지토리 읽기입니다. 연관 한 단계 확장으로 TripLeg 와 Coordinate 가 붙어야 합니다. */
    public Object read(String title) {
        return repository.findByTitle(title);
    }

    /** 쓰기 트랜잭션 안의 변경자 호출입니다. */
    @Transactional
    public void rename(Trip trip, String title) {
        trip.rename(title);
    }

    /** 이벤트를 지나 리스너까지 도달해야 합니다. */
    public void publish() {
        service.publish();
    }

    /** 엔티티에 닿지 않습니다. 미해결로 표시되어야 합니다. */
    public String ping() {
        return "pong";
    }

    /** 엔티티에 닿지 않지만 의도한 것입니다. 맵에서 제외되어야 합니다. */
    @InvalidationMapIgnore
    public String health() {
        return "ok";
    }

    /**
     * 분석이 못 찾는 것을 개발자가 선언합니다. 분석 결과에 추가됩니다.
     *
     * <p>어노테이션은 이 메서드가 아니라 {@link TripEndpoints} 인터페이스에 붙어 있습니다.
     * 문서 어노테이션을 인터페이스에 붙이는 이 저장소의 컨트롤러 관례를 재현해, 분석기가
     * 핸들러 자신뿐 아니라 상위 타입에서도 어노테이션을 찾는지 검증합니다.
     */
    @Override
    public Object readWithHint(String title) {
        return repository.findByTitle(title);
    }

    /**
     * 분석 결과를 대체합니다.
     *
     * <p>분석이 실제로 찾아내는 엔티티는 Trip 이지만 선언은 TripLeg 로 둡니다. 선언과 분석
     * 결과가 같으면 override 의 "대체" 와 기본값인 "추가" 를 구분할 수 없기 때문입니다.
     */
    @WritesEntities(value = TripLeg.class, override = true)
    @Transactional
    public void writeWithOverride(Trip trip, String title) {
        trip.reset(title);
    }

    /**
     * 반환 타입을 {@code Trip} 으로 좁혀 재정의합니다(공변 반환). 어노테이션은
     * {@link TripEndpoints} 에 있는데 디스크립터의 반환 타입 부분이 달라지므로, 어노테이션
     * 탐색이 반환 타입을 무시하고 이름·파라미터만 비교해야 이 어노테이션을 찾습니다.
     */
    @Override
    public Trip readWithCovariantReturn(String title) {
        return repository.findByTitle(title).orElse(null);
    }
}
