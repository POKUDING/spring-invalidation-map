package dev.toktokhan.invalidation.springboot.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * {@link dev.toktokhan.invalidation.springboot.SpringProgramModel} 검증용 픽스처 애플리케이션입니다.
 *
 * <p>Task 10, 11 에서도 그대로 재사용합니다.
 */
@SpringBootApplication
public class TestApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestApplication.class, args);
    }
}
