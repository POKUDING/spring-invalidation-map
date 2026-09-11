package dev.toktokhan.invalidation.springboot.app;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * {@link NoteEventListener} 만 쓰는 엔티티입니다. {@link Note} 와 연관이 없습니다.
 *
 * <p>연관이 없어야 하는 이유가 있습니다. {@code Note.tags} 에는 {@code cascade = ALL} 이
 * 걸려 있어, {@code Note} 를 쓰는 엔드포인트는 cascade 확장만으로도 {@link NoteTag} 를
 * {@code writes} 에 갖습니다. 그래서 {@code NoteTag} 로는 리스너 경로를 검증할 수 없습니다 —
 * 리스너를 지워도 {@code NoteTag} 가 그대로 남습니다(실측으로 확인했습니다). 어떤 연관으로도
 * 닿지 않는 이 엔티티라야 "리스너를 지우면 사라진다"가 성립합니다.
 */
@Entity
public class NoteAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String message;

    protected NoteAudit() {
        // JPA
    }

    public NoteAudit(String message) {
        this.message = message;
    }

    public Long getId() {
        return id;
    }

    public String getMessage() {
        return message;
    }
}
