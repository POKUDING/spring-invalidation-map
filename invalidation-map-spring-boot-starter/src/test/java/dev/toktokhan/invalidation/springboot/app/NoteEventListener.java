package dev.toktokhan.invalidation.springboot.app;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * {@code @TransactionalEventListener} 는 {@code @EventListener} 로 메타 어노테이션되어
 * 있으므로 {@code eventListeners()} 가 둘 다 한 번의 검사로 잡아야 합니다.
 *
 * <p>{@code NoteTag} 를 {@link NoteTagRepository} 로 직접 저장합니다. {@code notes.save(note)} 로
 * 연관을 통해 간접 저장했다면 이 리스너를 지워도 {@code Note} 저장은 다른 경로(예:
 * {@link NotePortAdapter})로 남아 있을 수 있어, {@code writes} 에서 {@code NoteTag} 가 사라지는지로
 * 리스너 경로 자체를 검증하기 어렵습니다. 리포지토리를 분리해 이 리스너를 지우면
 * {@code NoteTag} 쓰기 자체가 통째로 사라지게 만듭니다.
 */
@Component
public class NoteEventListener {

    private final NoteJpaRepository notes;
    private final NoteTagRepository tags;

    public NoteEventListener(NoteJpaRepository notes, NoteTagRepository tags) {
        this.notes = notes;
        this.tags = tags;
    }

    @TransactionalEventListener
    public void onNoteCreated(NoteCreatedEvent event) {
        notes.findById(event.noteId()).ifPresent(note -> {
            NoteTag tag = tags.save(new NoteTag(event.label()));
            note.getTags().add(tag);
        });
    }
}
