package dev.toktokhan.invalidation.core.fixture.entity;

/**
 * {@link BaseRecord}({@code @MappedSuperclass})를 상속하지만 그 자신은 어떤 JPA
 * 어노테이션도 없는 평범한 클래스입니다. 이 클래스의 필드는 영속되지 않지만, 이 클래스가
 * 선언한 메서드는 상속받은 영속 필드를 바꿀 수 있습니다.
 */
public abstract class ArchivableRecord extends BaseRecord {

    /**
     * archive() 는 이 클래스가 선언하고, 그 안에서 부르는 markDeleted() 의 owner 는 이
     * 클래스 자신입니다(상속받은 메서드를 정적 수신 타입으로 기록).
     */
    public void archive() {
        markDeleted();
    }
}
