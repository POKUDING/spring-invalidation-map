package dev.toktokhan.invalidation.springboot;

import dev.toktokhan.invalidation.core.AnalyzerOptions;
import dev.toktokhan.invalidation.core.EndpointEntities;
import dev.toktokhan.invalidation.core.InvalidationMap;
import dev.toktokhan.invalidation.core.InvalidationMapAnalyzer;
import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.MethodRefs;
import io.swagger.v3.oas.models.Operation;
import jakarta.persistence.EntityManagerFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 분석 결과를 OpenAPI 오퍼레이션의 {@code x-entities} 확장으로 붙입니다.
 *
 * <p>분석은 첫 스펙 요청 때 한 번만 합니다. 부팅 시간이 늘지 않고, Swagger 를 열지 않는
 * 환경에서는 아예 돌지 않습니다.
 */
public final class InvalidationMapOperationCustomizer implements OperationCustomizer {

    private static final Log log = LogFactory.getLog(InvalidationMapOperationCustomizer.class);
    private static final String EXTENSION = "x-entities";

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
        Map<String, Object> extension = new LinkedHashMap<>();
        if (!entities.reads().isEmpty()) {
            extension.put("reads", names(entities.reads()));
        }
        if (!entities.writes().isEmpty()) {
            extension.put("writes", names(entities.writes()));
        }
        if (!entities.resolved()) {
            // resolved 는 false 일 때만 넣습니다. true 를 전부 넣으면 스펙만 커집니다.
            extension.put("resolved", false);
            extension.put("unresolved", entities.unresolved());
        }
        if (!extension.isEmpty()) {
            operation.addExtension(EXTENSION, extension);
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
        Optional<RequestMappingHandlerMapping> handlerMapping =
            handlerMappings.orderedStream().findFirst();
        Optional<EntityManagerFactory> entityManagerFactory =
            entityManagerFactories.orderedStream().findFirst();
        if (handlerMapping.isEmpty() || entityManagerFactory.isEmpty()) {
            log.info("invalidation-map: RequestMappingHandlerMapping 또는 EntityManagerFactory 가 "
                + "없어 분석하지 않습니다");
            return InvalidationMap.empty();
        }

        SpringProgramModel program = new SpringProgramModel(beanFactory, handlerMapping.get(),
            entityManagerFactory.get(), beanFactory.getBeanClassLoader());
        AnalyzerOptions options = new AnalyzerOptions(basePackages(),
            properties.getNodeBudget(), properties.isExpandReadAssociations());

        long startedAt = System.currentTimeMillis();
        InvalidationMap result = new InvalidationMapAnalyzer().analyze(program, options);
        logSummary(result, System.currentTimeMillis() - startedAt);
        return result;
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
