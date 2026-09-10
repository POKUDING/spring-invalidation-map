package dev.toktokhan.invalidation.core.fixture.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * 연속된 대문자를 포함하는 엔티티 단순명입니다. @Table 이 없으므로 기본 테이블명 규칙이
 * 적용됩니다. Spring Boot 기본값(SpringPhysicalNamingStrategy)은 앞 글자가 소문자일 때만
 * 밑줄을 넣으므로 연속된 대문자 사이에는 밑줄이 없습니다({@code httpserver}).
 */
@Entity
public class HTTPServer {

    @Id
    private Long id;
}
