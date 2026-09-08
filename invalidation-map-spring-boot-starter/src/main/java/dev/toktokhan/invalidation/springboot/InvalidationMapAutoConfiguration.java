package dev.toktokhan.invalidation.springboot;

import jakarta.persistence.EntityManagerFactory;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * springdoc 과 JPA 가 클래스패스에 있고 {@code invalidation-map.enabled} 가 꺼져 있지 않을 때만
 * 동작합니다.
 *
 * <p>{@code @AutoConfiguration(after = ...)} 로 순서를 지정하지 않습니다. Boot 4 에서 자동 설정
 * 클래스가 모듈별로 재배치되어 클래스 참조가 버전에 묶이기 때문입니다. 대신 {@code ObjectProvider}
 * 로 받아 첫 사용 시점에 꺼냅니다.
 */
@AutoConfiguration
@ConditionalOnClass({OperationCustomizer.class, EntityManagerFactory.class,
    RequestMappingHandlerMapping.class})
@ConditionalOnProperty(prefix = "invalidation-map", name = "enabled", havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties(InvalidationMapProperties.class)
public class InvalidationMapAutoConfiguration {

    @Bean
    public InvalidationMapOperationCustomizer invalidationMapOperationCustomizer(
        ConfigurableListableBeanFactory beanFactory,
        ObjectProvider<RequestMappingHandlerMapping> handlerMappings,
        ObjectProvider<EntityManagerFactory> entityManagerFactories,
        InvalidationMapProperties properties) {
        return new InvalidationMapOperationCustomizer(
            beanFactory, handlerMappings, entityManagerFactories, properties);
    }

    /**
     * 부팅을 실패시키려면 부팅 중에 결과가 있어야 하므로, 이 경우에만 즉시 분석합니다.
     * 기본값은 거짓이라 대부분의 소비자는 지연 분석의 이점을 그대로 받습니다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "invalidation-map", name = "fail-on-unresolved",
        havingValue = "true")
    public ApplicationListener<ApplicationReadyEvent> invalidationMapUnresolvedCheck(
        InvalidationMapOperationCustomizer customizer) {
        return event -> customizer.failIfUnresolved();
    }
}
