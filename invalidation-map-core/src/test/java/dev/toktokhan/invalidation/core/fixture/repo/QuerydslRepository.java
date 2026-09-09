package dev.toktokhan.invalidation.core.fixture.repo;

import com.querydsl.core.types.ConstructorExpression;
import com.querydsl.core.types.dsl.EntityPathBase;
import dev.toktokhan.invalidation.core.fixture.entity.Trip;
import dev.toktokhan.invalidation.core.resolve.QuerydslResolver;

public class QuerydslRepository {

    /** 엔티티 Q클래스를 흉내 낸 픽스처입니다. {@code EntityPathBase<Trip>} 를 상속합니다. */
    public static class QTrip extends EntityPathBase<Trip> {

        /**
         * QueryDSL 코드 생성기가 실제로 만들어 내는 관용구입니다. 실제 Q클래스는 이렇게
         * {@code public static final} 기본 인스턴스를 자기 자신 안에 두고, 소비 코드는
         * 이 인스턴스를 {@code new} 로 다시 만들지 않고 그대로(또는 static import 로) 씁니다.
         * {@link #selectFromStaticInstance()} 가 이 인스턴스만 참조합니다.
         */
        public static final QTrip trip = new QTrip();

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

    /**
     * QueryDSL 의 실제 관용구를 흉내 낸 픽스처입니다 — {@link #selectFrom()} 과 달리 이
     * 메서드 안에는 {@code NEW QTrip} 명령이 전혀 없고, {@code QTrip} 자신에 선언된
     * 메서드를 호출하지도 않습니다. {@code QTrip.trip} 정적 필드를 {@code GETSTATIC} 으로
     * 읽기만 합니다.
     *
     * <p>실측(Task 12, pirl-spring {@code ClassInfoRepositoryImpl.findAllVisibleAtForV1})에서
     * {@code jpaQueryFactory.selectFrom(classInfo).leftJoin(classInfo.classPhotoList)
     * .fetchJoin().where(...)} 처럼 정적 인스턴스만 참조하는 코드가 지금까지의 픽스처
     * ({@link #selectFrom()}, {@code new QTrip()} 관용구)로는 재현되지 않아 엔티티 접근을
     * 통째로 놓쳤습니다({@code resolved: false}). {@link QuerydslResolver} 가
     * {@code referencedFieldOwners()} 도 함께 보도록 고친 뒤에는 이 메서드로도 {@code Trip}
     * 을 찾습니다.
     */
    public Object selectFromStaticInstance() {
        return QTrip.trip;
    }

    public Object projection() {
        QTripDto dto = new QTripDto();
        return dto.toString();
    }

    /**
     * 같은 메서드 안에 Q클래스 참조와 엔티티 변경자 호출이 함께 있는 픽스처입니다.
     * QuerydslResolver 가 caller 안의 Q클래스 참조만으로 다른 호출까지 가로채면(게이트
     * 없이 caller 전체 스캔만 하면), {@code trip.rename(...)} 호출 지점까지 QueryDSL
     * 접근으로 잘못 판정해 DirtyCheckResolver 가 그 호출을 영원히 못 보게 됩니다.
     */
    public void renameIfStale(Trip trip, String newTitle) {
        QTrip qtrip = new QTrip();
        boolean stale = qtrip.toString().isEmpty();
        if (stale) {
            trip.rename(newTitle);
        }
    }
}
