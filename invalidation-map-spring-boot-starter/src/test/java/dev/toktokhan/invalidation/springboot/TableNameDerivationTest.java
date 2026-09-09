package dev.toktokhan.invalidation.springboot;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.index.ClassRepository;
import dev.toktokhan.invalidation.core.index.EntityIndex;
import dev.toktokhan.invalidation.springboot.app.HTTPCache2Entry;
import dev.toktokhan.invalidation.springboot.app.TestApplication;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 엔티티 기본 테이블명 파생이 <b>실제로 돌고 있는 Hibernate</b> 가 만든 테이블명을 덮는지
 * 확인합니다.
 *
 * <p>이 테스트는 어느 세대의 규칙도 코드에 적지 않습니다. H2 의
 * {@code INFORMATION_SCHEMA} 에서 Hibernate 가 실제로 만든 테이블 이름을 읽어, 그 이름을
 * {@link EntityIndex#entitiesForTable} 이 엔티티로 되돌릴 수 있는지만 봅니다. 그래서 같은
 * 소스가 두 클래스패스에서 서로 다른 테이블명을 만들어도 같은 논리로 검증됩니다.
 *
 * <p>{@link HTTPCache2Entry} 는 연속된 대문자와 숫자 경계를 함께 가진 이름이라 두 세대의
 * 물리 네이밍 전략이 실제로 다른 결과를 냅니다 — 실측: Boot 3.3.5 는
 * {@code HTTPCACHE2ENTRY}, Boot 4.0.6 은 {@code HTTPCACHE2_ENTRY} 를 만듭니다.
 */
@SpringBootTest(classes = TestApplication.class)
class TableNameDerivationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ConfigurableApplicationContext context;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void entitiesForTable_tableNameCreatedByRunningHibernate_resolvesEntity() throws Exception {
        String actualTable = tableCreatedFor(HTTPCache2Entry.class);

        assertThat(entityIndex().entitiesForTable(actualTable))
            .as("Hibernate 가 실제로 만든 테이블 %s", actualTable)
            .contains(MethodRefs.internalNameOf(HTTPCache2Entry.class));
    }

    @Test
    void entitiesForTable_everyEntityTableCreatedByRunningHibernate_resolvesSomeEntity() throws Exception {
        SpringProgramModel model = model();
        EntityIndex entities = new EntityIndex(new ClassRepository(model), model.entities());
        List<String> unmapped = new ArrayList<>();

        for (String entity : model.entities()) {
            String table = tableCreatedFor(MethodRefs.fqcnOf(entity));
            if (table == null) {
                // @Embeddable 은 자기 테이블이 없습니다.
                continue;
            }
            if (entities.entitiesForTable(table).isEmpty()) {
                unmapped.add(entity + " -> " + table);
            }
        }

        assertThat(unmapped)
            .as("Hibernate 가 만든 테이블인데 어떤 엔티티로도 되돌려지지 않는 항목")
            .isEmpty();
    }

    /**
     * 이 클래스가 실제로 매핑된 테이블 이름입니다. 밑줄을 뺀 뒤 대소문자를 무시해
     * 단순명과 비교하므로, 어느 세대의 밑줄 규칙이든 같은 방식으로 찾아냅니다. 자기
     * 테이블이 없는 타입({@code @Embeddable})은 null 입니다.
     */
    private String tableCreatedFor(Class<?> type) throws Exception {
        return tableCreatedFor(type.getName());
    }

    private String tableCreatedFor(String fqcn) throws Exception {
        String simpleName = fqcn.substring(fqcn.lastIndexOf('.') + 1);
        String needle = simpleName.replace("$", "").toLowerCase(Locale.ROOT);
        for (String table : createdTables()) {
            if (table.replace("_", "").toLowerCase(Locale.ROOT).equals(needle)) {
                return table;
            }
        }
        return null;
    }

    private List<String> createdTables() throws Exception {
        List<String> tables = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            ResultSet rows = statement.executeQuery(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'");
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
        }
        return tables;
    }

    private EntityIndex entityIndex() {
        SpringProgramModel model = model();
        return new EntityIndex(new ClassRepository(model), model.entities());
    }

    private SpringProgramModel model() {
        return new SpringProgramModel(context.getBeanFactory(), handlerMapping,
            entityManagerFactory, getClass().getClassLoader());
    }
}
