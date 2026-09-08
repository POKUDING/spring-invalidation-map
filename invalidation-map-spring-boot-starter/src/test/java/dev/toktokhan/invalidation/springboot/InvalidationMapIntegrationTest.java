package dev.toktokhan.invalidation.springboot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.toktokhan.invalidation.springboot.app.Note;
import dev.toktokhan.invalidation.springboot.app.NoteMetadata;
import dev.toktokhan.invalidation.springboot.app.NoteTag;
import dev.toktokhan.invalidation.springboot.app.SlotInstance;
import dev.toktokhan.invalidation.springboot.app.TestApplication;
import dev.toktokhan.invalidation.springboot.app.extra.AlphaEntity;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * {@code /v3/api-docs} 응답에 {@code x-entities} 확장이 실제로 실리는지 확인하는 종단 테스트입니다.
 *
 * <p>HTTP 호출에 {@code TestRestTemplate} 이나 {@code @AutoConfigureMockMvc}(MockMvc) 를 쓰지
 * 않고 JDK 표준 {@link HttpClient} 를 직접 씁니다. 둘 다 Boot 4 에서 이 스타터가 테스트에서
 * 의존하지 않는 별도 모듈로 옮겨갔기 때문입니다(직접 확인: {@code spring-boot-test-4.0.6.jar}
 * 에는 {@code TestRestTemplate} 클래스가 아예 없고, {@code org.springframework.boot.resttestclient
 * .TestRestTemplate} 로 {@code spring-boot-resttestclient} 모듈에 재배치되었습니다.
 * {@code @AutoConfigureMockMvc} 도 같은 이유로 {@code spring-boot-webmvc-test} 모듈의
 * {@code org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc} 로
 * 재배치되었습니다. 둘 다 {@code spring-boot-starter-test} 가 전이적으로 끌어오지 않아 별도
 * 의존성 추가가 필요합니다). {@code @LocalServerPort} 와 {@code @SpringBootTest} 자체는
 * {@code spring-boot-test} 모듈에 그대로 남아 있어(3.3.5, 4.0.6 jar 대조 확인) 이 스타터가 이미
 * 가진 의존성만으로 두 세대 모두에서 컴파일·실행됩니다.
 *
 * <p>대부분의 단정은 {@code Note.class.getName()} 처럼 픽스처 클래스에서 직접 뽑은 문자열과
 * 비교합니다. 리터럴 문자열을 그대로 적으면 오타가 나도 통과하는 테스트가 생기기 때문입니다.
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = RANDOM_PORT)
class InvalidationMapIntegrationTest {

    private static final String NOTE_FQCN = Note.class.getName();
    private static final String NOTE_TAG_FQCN = NoteTag.class.getName();

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Test
    void apiDocs_getEndpoint_hasReadsExtension() throws Exception {
        JsonNode reads = apiDocs(port).at("/paths/~1notes/get/x-entities/reads");

        assertThat(toList(reads)).contains(NOTE_FQCN);
    }

    @Test
    void apiDocs_getEndpoint_readsIncludeAssociatedEntity() throws Exception {
        JsonNode reads = apiDocs(port).at("/paths/~1notes/get/x-entities/reads");

        assertThat(toList(reads)).contains(NOTE_TAG_FQCN);
    }

    @Test
    void apiDocs_postEndpoint_hasWritesExtension() throws Exception {
        JsonNode writes = apiDocs(port).at("/paths/~1notes/post/x-entities/writes");

        assertThat(toList(writes)).contains(NOTE_FQCN);
    }

    /**
     * {@code NoteTag} 는 {@code NoteEventListener} 가 {@code NoteCreatedEvent} 를 받아 저장합니다.
     * 리스너를 지우면(또는 이벤트 발행 자체를 지우면) 이 엔드포인트의 writes 에서 사라져야만
     * 이 테스트가 리스너 경로를 실제로 검증하는 것입니다 — {@code Note} 의 {@code @OneToMany}
     * 연관 확장으로 우연히 들어온 것이 아닙니다. 연관 확장은 {@code expandReadAssociations} 로
     * reads 에만 적용되고 writes 에는 적용되지 않으므로(핵심 애널라이저의 설계), 애초에
     * writes 에 NoteTag 가 들어올 수 있는 경로는 이 리스너뿐입니다.
     */
    @Test
    void apiDocs_postEndpoint_writesIncludeEventListenerEntity() throws Exception {
        JsonNode writes = apiDocs(port).at("/paths/~1notes/post/x-entities/writes");

        assertThat(toList(writes)).contains(NOTE_TAG_FQCN);
    }

    @Test
    void apiDocs_putEndpoint_dirtyCheckMutatorIsWrite() throws Exception {
        JsonNode writes = apiDocs(port).at("/paths/~1notes~1{id}/put/x-entities/writes");

        assertThat(toList(writes)).contains(NOTE_FQCN);
    }

    @Test
    void apiDocs_fragmentNativeSql_resolvesEntity() throws Exception {
        JsonNode writes = apiDocs(port).at("/paths/~1notes~1{id}~1upsert/put/x-entities/writes");

        assertThat(toList(writes)).contains(NOTE_FQCN);
    }

    /**
     * {@code /notes/health} 는 {@code @InvalidationMapIgnore} 가 붙어 있어 분석 대상에서
     * 완전히 빠집니다. 대조군인 {@code /notes/ping} (아래 참고)은 어노테이션 없이도 접근을
     * 찾지 못하면 {@code x-entities} 가 붙되 {@code resolved: false} 로 붙습니다 — 그러니
     * "확장이 없다"는 이 단정이 지키는 것은 정말로 무시 어노테이션이지, 접근을 못 찾은
     * 결과가 아닙니다.
     */
    @Test
    void apiDocs_ignoredEndpoint_hasNoExtension() throws Exception {
        JsonNode extension = apiDocs(port).at("/paths/~1notes~1health/get/x-entities");

        assertThat(extension.isMissingNode()).isTrue();
    }

    /**
     * {@code resolved} 키는 해결된 엔드포인트에는 없어야 하고, 미해결 엔드포인트에는
     * {@code false} 로 실제로 실려야 합니다. 후자를 확인하지 않으면 {@code resolved} 키를
     * 아예 쓰지 않는 구현도 이 테스트를 통과합니다.
     */
    @Test
    void apiDocs_resolvedTrue_isOmitted() throws Exception {
        JsonNode docs = apiDocs(port);

        JsonNode resolvedExtension = docs.at("/paths/~1notes/get/x-entities");
        assertThat(resolvedExtension.has("resolved")).isFalse();

        JsonNode unresolvedExtension = docs.at("/paths/~1notes~1ping/get/x-entities");
        assertThat(unresolvedExtension.isMissingNode()).isFalse();
        assertThat(unresolvedExtension.get("resolved").asBoolean()).isFalse();
        assertThat(toList(unresolvedExtension.get("unresolved"))).isNotEmpty();
    }

    /**
     * F1(Critical) 재발 방지 테스트입니다. 레거시 프래그먼트 이름 규칙({@link SlotInstance}
     * 계열, pirl-spring 의 {@code SlotInstanceRepositoryImpl})의 네이티브 SQL 쓰기와, 이미
     * 해결된 다른 읽기({@link AlphaEntity})가 같은 엔드포인트에 함께 있을 때, 프래그먼트
     * 쓰기가 조용히 사라지지 않고 {@code writes} 에 실제로 실리는지 확인합니다.
     *
     * <p>리뷰 라운드 1 에서는 {@code SpringProgramModel.implementationsOf} 가 프래그먼트
     * 구현체를 프래그먼트 계약 이름으로만 색인해, 호출부 정적 타입이 리포지토리 인터페이스인
     * 이 경우를 찾지 못했습니다. 그 결과 {@code writes} 키도 {@code unresolved} 언급도 없이
     * {@code SlotInstance} 쓰기가 완전히 사라지고, {@code AlphaEntity} 읽기만 있어 엔드포인트
     * 전체가 "완전히 해결됨"으로 보고됐습니다 — {@code fail-on-unresolved} 가드도 잡지
     * 못하는 조용한 누락이었습니다.
     */
    @Test
    void apiDocs_legacyFragmentWriteWithResolvedRead_isNotSilentlyDropped() throws Exception {
        JsonNode extension = apiDocs(port).at("/paths/~1legacy-fragment-mix~1{id}/put/x-entities");

        assertThat(extension.isMissingNode()).isFalse();
        assertThat(toList(extension.path("writes"))).contains(SlotInstance.class.getName());
        assertThat(toList(extension.path("reads"))).contains(AlphaEntity.class.getName());
    }

    @Test
    void apiDocs_multiPathHandler_sharesSameExtension() throws Exception {
        JsonNode docs = apiDocs(port);
        JsonNode multiA = docs.at("/paths/~1notes~1multi-a/get/x-entities");
        JsonNode multiB = docs.at("/paths/~1notes~1multi-b/get/x-entities");

        assertThat(multiA.isMissingNode()).isFalse();
        assertThat(toList(multiA.path("reads"))).contains(NOTE_FQCN);
        assertThat(multiA).isEqualTo(multiB);
    }

    private static JsonNode apiDocs(int port) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs"))
            .GET()
            .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        return MAPPER.readTree(response.body());
    }

    private static List<String> toList(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        arrayNode.forEach(node -> values.add(node.asText()));
        return values;
    }

    /**
     * {@code invalidation-map.entity-naming: SIMPLE} 을 켠 별도 컨텍스트입니다. 프로퍼티가
     * 바깥 클래스와 달라 스프링이 별도 애플리케이션 컨텍스트를 띄웁니다.
     */
    @Nested
    @SpringBootTest(classes = TestApplication.class, webEnvironment = RANDOM_PORT,
        properties = "invalidation-map.entity-naming=SIMPLE")
    class EntityNamingSimple {

        @LocalServerPort
        private int port;

        @Test
        void apiDocs_entityNamingSimple_usesSimpleNames() throws Exception {
            JsonNode reads = apiDocs(port).at("/paths/~1notes/get/x-entities/reads");

            // Note 는 @OneToMany(NoteTag), @Embedded(NoteMetadata) 연관을 한 단계 확장한
            // 결과까지 포함합니다. 세 이름 모두 같은 패키지라 알파벳 순서가 그대로입니다.
            assertThat(toList(reads)).containsExactly(
                Note.class.getSimpleName(),
                NoteMetadata.class.getSimpleName(),
                NoteTag.class.getSimpleName());
            assertThat(toList(reads)).doesNotContain(NOTE_FQCN, NOTE_TAG_FQCN);
        }

        /**
         * {@code AlphaEntity} 와 {@code SlotInstance} 는 서로 다른 패키지에 있습니다
         * ({@link AlphaEntity} javadoc 참고). 단순 클래스명 알파벳 순서
         * ({@code AlphaEntity < SlotInstance}) 는 내부 이름(패키지 경로 포함) 순서와
         * 정반대입니다. {@code InvalidationMapOperationCustomizer.names()} 의
         * {@code .sorted()} 를 지우면 이 목록이 원래 내부 이름 순서
         * ({@code [SlotInstance, AlphaEntity]})로 나와 아래 단정이 실패합니다.
         *
         * <p>같은 패키지에 있는 엔티티끼리는(예: {@code Note}, {@code NoteTag}) 내부 이름
         * 순서와 단순명 순서가 항상 같습니다 — 공통 패키지 접두어가 비교에서 상쇄되기
         * 때문입니다. 그래서 그 조합으로는 {@code .sorted()} 가 있으나 없으나 결과가 같아
         * 정렬 자체를 검증하지 못합니다. {@code SlotInstance} 는 연관 필드가 없어 읽기 집합이
         * 더 넓어지지도 않습니다.
         */
        @Test
        void apiDocs_extensionListsAreSorted_isStable() throws Exception {
            JsonNode reads = apiDocs(port).at("/paths/~1sort-check/get/x-entities/reads");

            assertThat(toList(reads)).containsExactly(
                AlphaEntity.class.getSimpleName(), SlotInstance.class.getSimpleName());
        }
    }
}
