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

    @Test
    void kindOf_updateAfterCommonTableExpression_isWrite() {
        // 첫 토큰만 보는 구현은 head 가 WITH 라 READ 로 판정합니다. 쓰기를 읽기로 보고하면
        // 엔티티가 writes 에 안 들어가 소비자가 무효화하지 않습니다 — 누락과 같은 방향입니다.
        assertThat(SqlTableExtractor.kindOf(
            "WITH ranked AS (SELECT id FROM trip_log) "
                + "UPDATE trip_log SET title = 'x' WHERE id IN (SELECT id FROM ranked)"))
            .isEqualTo(AccessKind.WRITE);
    }

    @Test
    void kindOf_insertAfterCommonTableExpression_isWrite() {
        assertThat(SqlTableExtractor.kindOf(
            "WITH staged AS (SELECT id FROM staging_log) INSERT INTO trip_log SELECT id FROM staged"))
            .isEqualTo(AccessKind.WRITE);
    }

    @Test
    void kindOf_selectAfterCommonTableExpression_staysRead() {
        // CTE 를 지나 찾은 첫 문장이 SELECT 면 읽기입니다. CTE 안의 토큰을 무작정 훑어
        // 쓰기 키워드를 찾는 구현은 이 단정을 깨거나, 반대로 읽기를 쓰기로 승격해
        // reads 에서 엔티티를 잃습니다.
        assertThat(SqlTableExtractor.kindOf(
            "WITH ranked AS (SELECT id FROM trip_log WHERE deleted_at IS NULL) "
                + "SELECT * FROM ranked"))
            .isEqualTo(AccessKind.READ);
    }

    @Test
    void kindOf_updateAfterBlockComment_isWrite() {
        assertThat(SqlTableExtractor.kindOf("/* batch upsert */ UPDATE trip_log SET title = 'x'"))
            .isEqualTo(AccessKind.WRITE);
    }

    @Test
    void kindOf_updateAfterLineComment_isWrite() {
        assertThat(SqlTableExtractor.kindOf("-- soft delete\nUPDATE trip_log SET deleted_at = now()"))
            .isEqualTo(AccessKind.WRITE);
    }

    @Test
    void kindOf_selectWithWriteKeywordInsideStringLiteral_staysRead() {
        // 'DELETE' 는 데이터이지 문장이 아닙니다. 읽기를 쓰기로 승격하면 그 엔티티가
        // reads 에서 사라져, 이 엔드포인트를 무효화 대상으로 찾지 못하게 됩니다.
        assertThat(SqlTableExtractor.kindOf(
            "SELECT * FROM trip_log WHERE action = 'DELETE'")).isEqualTo(AccessKind.READ);
    }

    @Test
    void kindOf_selectWithWriteKeywordInsideComment_staysRead() {
        assertThat(SqlTableExtractor.kindOf(
            "SELECT * FROM trip_log /* not an UPDATE */")).isEqualTo(AccessKind.READ);
    }

    @Test
    void entities_tableNameOnlyInsideComment_isNotCollected() {
        // 주석 안의 "from trip_log" 는 실제 접근이 아닙니다. 주석을 걷어내지 않는 구현은
        // Trip 을 후보로 잡습니다(과잉이라 치명적이지는 않지만 정확도가 떨어집니다).
        assertThat(SqlTableExtractor.entities(
            "SELECT 1 /* copied from trip_log */", entities)).isEmpty();
    }

    @Test
    void entities_tableNameOnlyInsideStringLiteral_isNotCollected() {
        assertThat(SqlTableExtractor.entities(
            "SELECT 'from trip_log' AS note", entities)).isEmpty();
    }

    @Test
    void entities_updateAfterCommonTableExpression_resolvesTargetTable() {
        assertThat(SqlTableExtractor.entities(
            "WITH ranked AS (SELECT id FROM trip_leg) UPDATE trip_log SET title = 'x'", entities))
            .containsExactlyInAnyOrder(TRIP, LEG);
    }
}
