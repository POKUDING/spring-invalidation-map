package dev.toktokhan.invalidation.springboot;

import dev.toktokhan.invalidation.core.AnalyzerOptions;
import dev.toktokhan.invalidation.core.Endpoint;
import dev.toktokhan.invalidation.core.EndpointEntities;
import dev.toktokhan.invalidation.core.InvalidationMap;
import dev.toktokhan.invalidation.core.InvalidationMapAnalyzer;
import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.MethodRefs;
import io.swagger.v3.oas.models.Operation;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springdoc.core.customizers.GlobalOperationCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 분석 결과를 OpenAPI 오퍼레이션의 {@code x-entities-*} 확장으로 붙입니다.
 *
 * <p>분석은 첫 스펙 요청 때 한 번만 합니다. 부팅 시간이 늘지 않고, Swagger 를 열지 않는
 * 환경에서는 아예 돌지 않습니다.
 *
 * <p>{@code OperationCustomizer} 가 아니라 {@code GlobalOperationCustomizer} 를 구현합니다
 * (둘은 같은 {@code customize} 메서드 하나뿐인 마커 인터페이스 관계입니다 — {@code
 * GlobalOperationCustomizer extends OperationCustomizer}). springdoc 은 이 둘을 다르게
 * 취급합니다: {@code springdoc.group-configs} 로 API 를 여러 그룹(예: user/admin)으로 나누면,
 * 평범한 {@code OperationCustomizer} 빈은 그룹이 없는 기본 {@code /v3/api-docs} 에만 적용되고
 * {@code /v3/api-docs/{group}} 에는 적용되지 않습니다. springdoc 내부의 {@code
 * SpringDocCustomizers} 가 {@code operationCustomizers} 와 {@code globalOperationCustomizers}
 * 를 서로 다른 필드로 나눠 들고 있고, {@code MultipleOpenApiWebMvcResource}(그룹별 스펙을
 * 만드는 클래스)는 그중 {@code globalOperationCustomizers} 만 모든 그룹에 공통으로 적용합니다
 * (springdoc-openapi-starter-common 2.5.0/3.0.1 바이트코드로 직접 확인).
 *
 * <p>실측: pirl-spring(Boot 3.3.5, {@code springdoc.group-configs} 로 user/admin/internal
 * 세 그룹 운용)에 실제로 붙여 확인했습니다(Task 12). 이 클래스 자신의 분석 완료 로그
 * 기준 엔드포인트는 219개입니다(설계 문서가 계획 단계에서 쓴 211개와는 다른 수치입니다
 * — 그 값은 이 실측이 아니라 별도 측정입니다). 그룹이 없는 {@code /v3/api-docs} 에는
 * 170개 경로 전부에 확장이 실렸지만, {@code /v3/api-docs/user} 와
 * {@code /v3/api-docs/admin} 에는 하나도 실리지 않았습니다 — 이 라이브러리의 픽스처는
 * 그룹을 쓰지 않아 Task 10, 11 어느 리뷰에서도 이 경로가 드러나지 않았습니다. 그룹을
 * 나누는 실제 프로젝트에서는 이 빠짐이 매 요청마다 재발했을 것이므로(픽스처가 아니라 실제
 * 배포 대상에서), 조용한 누락을 금지하는 4.4절 원칙에 정면으로 걸립니다.
 * {@code InvalidationMapIntegrationTest} 의 중첩 클래스 {@code GroupedApiDocs} 가 그룹이
 * 있을 때도 확장이 실리는지 재발 방지로 고정합니다.
 */
public final class InvalidationMapOperationCustomizer implements GlobalOperationCustomizer {

    private static final Log log = LogFactory.getLog(InvalidationMapOperationCustomizer.class);
    /**
     * 확장 키를 목록마다 하나씩 나눕니다.
     *
     * <p>한 키({@code x-entities}) 아래 객체로 묶으면 Swagger UI 에서 읽기 어렵습니다. UI 는
     * 확장 값을 {@code JSON.stringify(value)} 로 한 줄에 찍는데(swagger-ui 5.13.0 의
     * {@code operation_extension_row}, 들여쓰기 인자가 없습니다), 그러면
     * {@code {"reads":[...],"writes":[...]}} 한 덩어리가 됩니다. 키를 나누면 표에
     * {@code x-entities-reads} / {@code x-entities-writes} 라벨이 붙은 행으로 갈라져
     * 무엇이 무엇인지 바로 보입니다. 실측으로 확인했습니다.
     *
     * <p>값의 줄바꿈은 이렇게 해도 해결되지 않습니다. 같은 실측에서 FQCN 목록은 나눠도
     * 창 너비 900px 을 넘었습니다(1277px → 1219px). 그건 표시 쪽 문제라
     * {@code entity-naming: SIMPLE} 이나 Swagger UI 의 CSS 로 다뤄야 합니다.
     */
    private static final String READS = "x-entities-reads";
    private static final String WRITES = "x-entities-writes";

    /**
     * 판정하지 못한 자리의 사유입니다. 이 키가 있으면 그 엔드포인트는 신뢰할 수 없습니다.
     *
     * <p>{@code resolved} 플래그는 두지 않습니다. 이유가 둘입니다. 첫째,
     * {@code EndpointEntities.resolved()} 가 {@code unresolved.isEmpty()} 그 자체라 이 키의
     * 존재 여부와 같은 말입니다. 둘째, Swagger UI 는 최상위 확장 값이 falsy 면 값을 버리고
     * {@code null} 을 찍습니다({@code const u = xVal ? ... : null}). 즉
     * {@code x-entities-resolved: false} 는 화면에 {@code null} 로 나옵니다 — 실측으로
     * 확인했습니다.
     */
    private static final String UNRESOLVED = "x-entities-unresolved";

    private final ConfigurableListableBeanFactory beanFactory;
    private final ObjectProvider<RequestMappingHandlerMapping> handlerMappings;
    private final ObjectProvider<EntityManagerFactory> entityManagerFactories;
    private final InvalidationMapProperties properties;
    private final AtomicReference<InvalidationMap> cached = new AtomicReference<>();

    public InvalidationMapOperationCustomizer(ConfigurableListableBeanFactory beanFactory,
        ObjectProvider<RequestMappingHandlerMapping> handlerMappings,
        ObjectProvider<EntityManagerFactory> entityManagerFactories,
        InvalidationMapProperties properties) {
        this.beanFactory = beanFactory;
        this.handlerMappings = handlerMappings;
        this.entityManagerFactories = entityManagerFactories;
        this.properties = properties;
    }

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        Class<?> userClass = ClassUtils.getUserClass(handlerMethod.getBeanType());
        MethodRef handler = MethodRefs.of(userClass, handlerMethod.getMethod());
        map().forHandler(handler).ifPresent(entities -> apply(operation, entities));
        return operation;
    }

    /** 미해결 엔드포인트가 있으면 예외를 던집니다. fail-on-unresolved 일 때만 부릅니다. */
    public void failIfUnresolved() {
        List<String> offenders = map().byHandler().entrySet().stream()
            .filter(entry -> !entry.getValue().resolved())
            .map(entry -> entry.getKey() + " -> " + entry.getValue().unresolved())
            .sorted()
            .toList();
        if (!offenders.isEmpty()) {
            throw new IllegalStateException(
                "엔티티 접근을 판정하지 못한 엔드포인트가 있습니다 (" + offenders.size() + "개). "
                    + "의도한 것이면 @InvalidationMapIgnore 를 붙이십시오.\n"
                    + String.join("\n", offenders));
        }
    }

    private void apply(Operation operation, EndpointEntities entities) {
        // 빈 목록은 키 자체를 넣지 않습니다. 빈 배열을 실으면 스펙만 커지고, 소비자는 어차피
        // 키가 없는 경우를 처리해야 합니다.
        if (!entities.reads().isEmpty()) {
            operation.addExtension(READS, names(entities.reads()));
        }
        if (!entities.writes().isEmpty()) {
            operation.addExtension(WRITES, names(entities.writes()));
        }
        if (!entities.resolved()) {
            operation.addExtension(UNRESOLVED, entities.unresolved());
        }
    }

    private List<String> names(Set<String> internalNames) {
        Function<String, String> mapper = properties.getEntityNaming() == EntityNaming.SIMPLE
            ? MethodRefs::simpleNameOf
            : MethodRefs::fqcnOf;
        return internalNames.stream().map(mapper).sorted().toList();
    }

    private InvalidationMap map() {
        InvalidationMap existing = cached.get();
        if (existing != null) {
            return existing;
        }
        cached.compareAndSet(null, analyze());
        return cached.get();
    }

    private InvalidationMap analyze() {
        // 후보 매핑을 하나도 고르지 않고 전부 넘깁니다. 이유는
        // SpringProgramModel.buildEndpoints() 의 javadoc 에 있습니다 — order 로 고르면
        // 액추에이터의 ControllerEndpointHandlerMapping(order -100, 핸들러 0개)이 잡혀
        // 분석 대상이 사라집니다.
        List<RequestMappingHandlerMapping> mappings = handlerMappings.orderedStream().toList();
        Optional<EntityManagerFactory> entityManagerFactory =
            entityManagerFactories.orderedStream().findFirst();
        if (mappings.isEmpty() || entityManagerFactory.isEmpty()) {
            log.info("invalidation-map: RequestMappingHandlerMapping 또는 EntityManagerFactory 가 "
                + "없어 분석하지 않습니다");
            return InvalidationMap.empty();
        }

        List<String> basePackages = basePackages();
        SpringProgramModel program = new SpringProgramModel(beanFactory, mappings,
            entityManagerFactory.get(), beanFactory.getBeanClassLoader(), basePackages);
        AnalyzerOptions options = new AnalyzerOptions(basePackages,
            properties.getNodeBudget(), properties.isExpandReadAssociations());

        long startedAt = System.currentTimeMillis();
        InvalidationMap result = new InvalidationMapAnalyzer().analyze(program, options);
        logSummary(result, System.currentTimeMillis() - startedAt);
        logEntitySetSizes(result, program.entities().size());
        logExcluded(program.excludedEndpoints());
        return result;
    }

    /**
     * 엔티티 집합이 얼마나 커졌는지 남깁니다.
     *
     * <p>{@code reads} 는 연관을 따라 전이적으로 닫히고, {@code writes} 는 cascade 가 걸린
     * 연관을 따라 닫힙니다. 닫힘 크기는 도메인의 연관 밀도에 달려 있습니다 — 실측한
     * pirl-spring 은 엔티티 57개에서 최대 4개(7%)였지만, 연관이 촘촘한 도메인에서는 한
     * 엔드포인트의 집합이 전체 엔티티에 가까워질 수 있습니다. 그렇게 되면 어떤 쓰기든 거의
     * 모든 조회와 교집합이 생겨 사실상 전체 무효화가 됩니다. 옵션으로 미리 막지 않고 크기를
     * 남기는 이유는, 그런 프로젝트가 실제로 있는지 먼저 알아야 정밀화 방향을 정할 수 있기
     * 때문입니다.
     *
     * <p>가장 큰 집합이 전체 엔티티의 절반을 넘으면 {@code warn} 으로 올립니다. 그 지점부터는
     * 무효화 대상을 좁혀 준다는 이 라이브러리의 목적이 사실상 사라집니다.
     */
    private void logEntitySetSizes(InvalidationMap result, int entityCount) {
        if (result.byHandler().isEmpty() || entityCount == 0) {
            return;
        }
        Map.Entry<MethodRef, EndpointEntities> widestReads = null;
        Map.Entry<MethodRef, EndpointEntities> widestWrites = null;
        for (Map.Entry<MethodRef, EndpointEntities> entry : result.byHandler().entrySet()) {
            if (widestReads == null
                || entry.getValue().reads().size() > widestReads.getValue().reads().size()) {
                widestReads = entry;
            }
            if (widestWrites == null
                || entry.getValue().writes().size() > widestWrites.getValue().writes().size()) {
                widestWrites = entry;
            }
        }
        int maxReads = widestReads.getValue().reads().size();
        int maxWrites = widestWrites.getValue().writes().size();
        String message = "invalidation-map: 엔티티 " + entityCount + "개 중 한 엔드포인트가 잡는"
            + " 최대치는 reads " + maxReads + "개(" + widestReads.getKey() + "), writes "
            + maxWrites + "개(" + widestWrites.getKey() + ")입니다";
        if (Math.max(maxReads, maxWrites) * 2 > entityCount) {
            log.warn(message + ". 전체 엔티티의 절반을 넘습니다 — 연관 확장이 무효화 범위를 "
                + "지나치게 넓히고 있는지 확인하십시오");
        } else {
            log.info(message);
        }
    }

    /**
     * base package 밖이라 분석에서 빠진 엔드포인트를 남깁니다.
     *
     * <p>springdoc 이나 Spring 의 기본 컨트롤러가 여기 걸리는 것은 정상입니다. 그러나
     * {@code base-packages} 를 잘못 좁히면 실제 엔드포인트도 여기로 빠지고, 그러면 그
     * 엔드포인트에는 확장이 붙지 않아 소비자가 무효화를 하지 않습니다.
     * 조용히 빠지지 않도록 개수와 목록을 남깁니다.
     */
    private void logExcluded(List<Endpoint> excluded) {
        if (excluded.isEmpty()) {
            return;
        }
        log.info("invalidation-map: base package 밖이라 분석에서 제외한 엔드포인트 "
            + excluded.size() + "개입니다. 우리 코드가 여기 있으면 base-packages 설정을 "
            + "확인하십시오");
        excluded.forEach(endpoint -> log.info("  " + endpoint + " -> " + endpoint.handler().owner()));
    }

    /** 프로퍼티가 없으면 @SpringBootApplication 의 패키지를 씁니다. */
    private List<String> basePackages() {
        List<String> configured = properties.getBasePackages();
        List<String> source = configured.isEmpty() && AutoConfigurationPackages.has(beanFactory)
            ? AutoConfigurationPackages.get(beanFactory)
            : configured;
        return source.stream().map(name -> name.replace('.', '/')).toList();
    }

    private void logSummary(InvalidationMap result, long elapsedMillis) {
        List<String> unresolved = result.byHandler().entrySet().stream()
            .filter(entry -> !entry.getValue().resolved())
            .map(entry -> "  " + entry.getKey() + " -> " + entry.getValue().unresolved())
            .sorted()
            .toList();
        log.info("invalidation-map: 엔드포인트 " + result.byHandler().size() + "개 분석 완료 ("
            + elapsedMillis + "ms), 미해결 " + unresolved.size() + "개");
        if (!unresolved.isEmpty()) {
            log.info("invalidation-map: 미해결 엔드포인트입니다. 의도한 것이면 "
                + "@InvalidationMapIgnore 를 붙이십시오.\n" + String.join("\n", unresolved));
        }
    }
}
