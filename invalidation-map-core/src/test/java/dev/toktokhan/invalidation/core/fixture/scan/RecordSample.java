package dev.toktokhan.invalidation.core.fixture.scan;

/**
 * record 컴포넌트 접근자 핸들이 {@code lambdaBodies} 에 섞이지 않는지 검증하는 픽스처입니다.
 *
 * <p>record 의 {@code toString}/{@code equals}/{@code hashCode} 는 INVOKEDYNAMIC 으로
 * {@code ObjectMethods.bootstrap} 을 호출하고, 부트스트랩 인자에 컴포넌트마다 필드 핸들
 * ({@code REF_getField})이 실립니다. 이 핸들은 메서드 핸들이 아니므로 {@code lambdaBodies} 에
 * 담기면 안 됩니다.
 */
public record RecordSample(String name, int count) {
}
