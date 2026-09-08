package dev.toktokhan.invalidation.springboot.app;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * {@link Note} 에 붙는 태그입니다. {@code @Embedded} 대상은 아니지만, {@code entities()} 가
 * 리포지토리로 직접 다루지 않는 엔티티도 메타모델에서 모두 걷는지 확인하는 데 씁니다.
 */
@Entity
public class NoteTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String label;

    protected NoteTag() {
        // JPA
    }

    public NoteTag(String label) {
        this.label = label;
    }

    public Long getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }
}
