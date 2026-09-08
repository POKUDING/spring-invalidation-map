package dev.toktokhan.invalidation.core;

/**
 * 분석 대상 엔드포인트 하나입니다.
 *
 * <p>{@code handler} 가 식별자입니다. {@code httpMethod} 와 {@code path} 는 로그와 진단
 * 출력에만 씁니다. 한 핸들러에 경로가 여러 개 붙은 매핑에서도 키가 갈라지지 않도록
 * {@link InvalidationMap} 은 {@code handler} 로만 키를 잡습니다.
 */
public record Endpoint(String httpMethod, String path, MethodRef handler) {

    @Override
    public String toString() {
        return httpMethod + " " + path;
    }
}
