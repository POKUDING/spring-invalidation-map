package dev.toktokhan.invalidation.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 엔드포인트를 분석 대상에서 제외합니다. {@code x-entities} 가 붙지 않습니다.
 *
 * <p>엔티티를 정말로 건드리지 않는 엔드포인트(헬스체크, 파일 업로드 URL 발급 등)에 붙입니다.
 * 붙이지 않으면 "엔티티 접근을 찾지 못했습니다" 로 미해결 표시됩니다.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface InvalidationMapIgnore {
}
