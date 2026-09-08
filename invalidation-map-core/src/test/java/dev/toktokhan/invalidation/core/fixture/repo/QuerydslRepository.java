package dev.toktokhan.invalidation.core.fixture.repo;

import com.querydsl.core.types.ConstructorExpression;
import com.querydsl.core.types.dsl.EntityPathBase;
import dev.toktokhan.invalidation.core.fixture.entity.Trip;

public class QuerydslRepository {

    /** 엔티티 Q클래스를 흉내 낸 픽스처입니다. {@code EntityPathBase<Trip>} 를 상속합니다. */
    public static class QTrip extends EntityPathBase<Trip> {

        public QTrip() {
            super(Trip.class, "trip");
        }
    }

    /**
     * DTO 프로젝션 Q클래스를 흉내 낸 픽스처입니다. {@code ConstructorExpression} 을
     * 상속합니다.
     *
     * <p>타입 인자를 일부러 엔티티({@code Trip})로 두었습니다. 타입 인자가 문자열 같은
     * 비(非)엔티티였다면, {@code EntityPathBase} 상속 여부를 실제로 확인하지 않고 그저
     * "타입 인자가 엔티티인가"만 필터링하는 구현도 우연히 이 픽스처를 걸러내 테스트를
     * 통과시킵니다. 타입 인자 자체를 엔티티로 두어야 상위 타입 식별이 실제로 동작하는지
     * 구분됩니다.
     */
    public static class QTripDto extends ConstructorExpression<Trip> {

        public QTripDto() {
            super(Trip.class, new Class<?>[0]);
        }
    }

    public Object selectFrom() {
        QTrip trip = new QTrip();
        return trip.toString();
    }

    public Object projection() {
        QTripDto dto = new QTripDto();
        return dto.toString();
    }
}
