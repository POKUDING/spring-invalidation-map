package dev.toktokhan.invalidation.springboot.app;

/**
 * {@code Note} 생성 이벤트입니다. {@code ApplicationEvent} 를 상속하지 않는 순수 POJO 입니다
 * — Spring 의 {@code @EventListener} 는 임의 타입을 이벤트로 받을 수 있습니다.
 */
public class NoteCreatedEvent {

    private final Long noteId;
    private final String label;

    public NoteCreatedEvent(Long noteId, String label) {
        this.noteId = noteId;
        this.label = label;
    }

    public Long noteId() {
        return noteId;
    }

    public String label() {
        return label;
    }
}
