package dev.toktokhan.invalidation.core.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.AccessKind;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.fixture.entity.Account;
import dev.toktokhan.invalidation.core.fixture.entity.Member;
import dev.toktokhan.invalidation.core.fixture.entity.Trip;
import dev.toktokhan.invalidation.core.fixture.entity.TripLeg;
import dev.toktokhan.invalidation.core.index.ClassRepository;
import dev.toktokhan.invalidation.core.index.EntityIndex;
import dev.toktokhan.invalidation.core.support.FakeProgramModel;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SqlTableExtractorTest {

    private static final String TRIP = MethodRefs.internalNameOf(Trip.class);
    private static final String LEG = MethodRefs.internalNameOf(TripLeg.class);
    private static final String ACCOUNT = MethodRefs.internalNameOf(Account.class);
    private static final String MEMBER = MethodRefs.internalNameOf(Member.class);

    private final EntityIndex entities =
        new EntityIndex(new ClassRepository(FakeProgramModel.create()), Set.of(TRIP, LEG));

    // Account 는 @Table(name = "member") 를 명시하고 Member 는 기본값 "member" 를 추정해
    // 같은 테이블명 후보에서 충돌합니다.
    private final EntityIndex collidingEntities = new EntityIndex(
        new ClassRepository(FakeProgramModel.create()), Set.of(ACCOUNT, MEMBER));

    @Test
    void entities_insertInto_resolvesExplicitTableName() {
        // Trip 은 @Table(name = "trip_log") 입니다.
        assertThat(SqlTableExtractor.entities(
            "INSERT INTO trip_log (title) VALUES (?) ON CONFLICT DO NOTHING", entities))
            .containsExactly(TRIP);
    }

    @Test
    void entities_selectFrom_resolvesSnakeCaseDefaultName() {
        // TripLeg 은 @Table 이 없으므로 snake_case 기본값으로 풀립니다.
        assertThat(SqlTableExtractor.entities("select * from trip_leg", entities))
            .containsExactly(LEG);
    }

    @Test
    void entities_joinedTables_resolvesAll() {
        assertThat(SqlTableExtractor.entities(
            "select * from trip_log t join trip_leg l on l.trip_id = t.id", entities))
            .containsExactlyInAnyOrder(TRIP, LEG);
    }

    @Test
    void entities_unknownTable_isIgnored() {
        assertThat(SqlTableExtractor.entities("select * from audit_log", entities)).isEmpty();
    }

    @Test
    void entities_impliedJoinCommaSeparatedFrom_resolvesBothTables() {
        // 쉼표로 나열한 암묵적 FROM 목록입니다. 토큰화 단계에서 쉼표가 사라지므로, from
        // 바로 다음 토큰만 보는 구현은 둘째 테이블(trip_leg)을 놓칩니다.
        assertThat(SqlTableExtractor.entities("select * from trip_log a, trip_leg b", entities))
            .containsExactlyInAnyOrder(TRIP, LEG);
    }

    @Test
    void entities_collidingTableName_resolvesBothEntities() {
        // entitiesForTable 이 과잉 방향으로 양쪽을 다 돌려주므로, 여기서도 한쪽만 취하지
        // 않고 둘 다 결과에 담겨야 합니다.
        assertThat(SqlTableExtractor.entities("select * from member", collidingEntities))
            .containsExactlyInAnyOrder(ACCOUNT, MEMBER);
    }

    @Test
    void entities_multiTableUpdate_resolvesBothEntities() {
        // MySQL 의 다중 테이블 UPDATE 문법입니다. UPDATE 뒤 바로 다음 토큰만 보는 구현은
        // 둘째 테이블(trip_leg)을 놓쳐 쓰기 무효화가 누락됩니다.
        assertThat(SqlTableExtractor.entities(
            "UPDATE trip_log a, trip_leg b SET a.title = b.name WHERE a.id = b.trip_id", entities))
            .containsExactlyInAnyOrder(TRIP, LEG);
    }

    @Test
    void kindOf_insert_isWrite() {
        assertThat(SqlTableExtractor.kindOf("INSERT INTO t VALUES (1)")).isEqualTo(AccessKind.WRITE);
    }

    @Test
    void kindOf_select_isRead() {
        assertThat(SqlTableExtractor.kindOf(" select 1 ")).isEqualTo(AccessKind.READ);
    }
}
