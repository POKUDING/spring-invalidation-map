package dev.toktokhan.invalidation.springboot.app;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * {@code @TransactionalEventListener} 는 {@code @EventListener} 로 메타 어노테이션되어
 * 있으므로 {@code eventListeners()} 가 둘 다 한 번의 검사로 잡아야 합니다.
 *
 * <p>리스너 경로를 검증하는 엔티티는 {@link NoteAudit} 입니다. {@link NoteTag} 로는 안 됩니다 —
 * {@code Note.tags} 에 {@code cascade = ALL} 이 걸려 있어, {@code Note} 를 쓰는 엔드포인트는
 * 이 리스너가 없어도 cascade 확장만으로 {@code NoteTag} 를 {@code writes} 에 갖습니다.
 * 실측으로 확인했습니다: 이 메서드에서 {@code tags.save(...)} 를 지워도 통합 테스트 14개가
 * 전부 통과했습니다. {@code NoteAudit} 은 어떤 연관으로도 {@code Note} 와 닿지 않으므로,
 * 이 리스너를 지우면 {@code writes} 에서 실제로 사라집니다.
 *
 * <p>세 엔티티를 리포지토리로 직접 저장합니다. {@code notes.save(note)} 로 연관을 통해 간접
 * 저장했다면 이 리스너를 지워도 {@code Note} 저장은 다른 경로(예: {@link NotePortAdapter})로
 * 남아 있을 수 있어, 리스너 경로 자체를 검증하기 어렵습니다.
 */
@Component
public class NoteEventListener {

    private final NoteJpaRepository notes;
    private final NoteTagRepository tags;
    private final NoteAuditRepository audits;

    public NoteEventListener(NoteJpaRepository notes, NoteTagRepository tags,
        NoteAuditRepository audits) {
        this.notes = notes;
        this.tags = tags;
        this.audits = audits;
    }

    @TransactionalEventListener
    public void onNoteCreated(NoteCreatedEvent event) {
        notes.findById(event.noteId()).ifPresent(note -> {
            NoteTag tag = tags.save(new NoteTag(event.label()));
            note.getTags().add(tag);
            audits.save(new NoteAudit("created " + note.getId()));
        });
    }
}
