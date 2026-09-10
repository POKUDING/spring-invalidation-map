package dev.toktokhan.invalidation.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 엔드포인트가 읽는 엔티티를 직접 선언합니다.
 *
 * <p>자동 분석이 닿지 못하는 조회 경로(MyBatis, 외부 캐시, 복잡한 네이티브 SQL)를 개발자가
 * 메웁니다. {@code Class} 참조이므로 컴파일 시 검증되고 리네임에 안전합니다.
 *
 * <p>기본 동작은 분석 결과에 더하는 것입니다. {@code override = true} 면 분석 결과를 대체합니다.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ReadsEntities {

    Class<?>[] value();

    boolean override() default false;
}
