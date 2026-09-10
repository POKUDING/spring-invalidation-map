package dev.toktokhan.invalidation.springboot.app;

import jakarta.persistence.Embeddable;

/**
 * {@link Note} 에 {@code @Embedded} 로 실리는 값 타입입니다. {@code entities()} 가 엔티티뿐
 * 아니라 임베더블도 걷는지 확인하는 데 씁니다.
 */
@Embeddable
public class NoteMetadata {

    private String createdBy;

    protected NoteMetadata() {
        // JPA
    }

    public NoteMetadata(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getCreatedBy() {
        return createdBy;
    }
}
