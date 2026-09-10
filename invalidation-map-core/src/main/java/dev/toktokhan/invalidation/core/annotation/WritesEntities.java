package dev.toktokhan.invalidation.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 이 엔드포인트가 쓰는 엔티티를 직접 선언합니다. 규칙은 {@link ReadsEntities} 와 같습니다. */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface WritesEntities {

    Class<?>[] value();

    boolean override() default false;
}
