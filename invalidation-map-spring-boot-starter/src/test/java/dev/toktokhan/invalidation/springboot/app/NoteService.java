package dev.toktokhan.invalidation.springboot.app;

import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NoteService {

    private static final String DEFAULT_TAG = "created";

    private final NotePort notePort;
    private final NoteJpaRepository notes;
    private final ApplicationEventPublisher events;

    public NoteService(NotePort notePort, NoteJpaRepository notes, ApplicationEventPublisher events) {
        this.notePort = notePort;
        this.notes = notes;
        this.events = events;
    }

    @Transactional
    public Note create(String title) {
        Note note = notePort.create(title);
        events.publishEvent(new NoteCreatedEvent(note.getId(), DEFAULT_TAG));
        return note;
    }

    @Transactional
    public Note rename(Long id, String title) {
        Note note = notes.findById(id).orElseThrow();
        note.rename(title);
        return note;
    }

    public List<Note> findAll() {
        return notes.findAll();
    }

    /**
     * {@link NoteRepositoryCustom} 프래그먼트를 거쳐 네이티브 UPSERT 를 실행합니다.
     *
     * <p>{@code notes}({@link NoteJpaRepository} 타입)로 그대로 호출합니다. 예전에는 호출
     * 지점의 정적 타입을 {@link NoteRepositoryCustom} 으로 좁히는 지역 변수를 거쳤는데,
     * 이는 실제 소비자 코드가 쓰지 않는 형태였고 {@code SpringProgramModel.implementationsOf}
     * 가 리포지토리 인터페이스 이름으로는 프래그먼트 구현체를 찾지 못하는 결함을 우회했을
     * 뿐입니다(리뷰로 지적됨). {@code SpringProgramModel.buildRepositoryIndex} 가 프래그먼트
     * 구현체를 {@code entityFor} 와 같은 이름 집합(리포지토리 인터페이스 포함)으로 색인하도록
     * 고쳐, 이제 {@code notes.upsert(...)} 그대로도 워커가 {@link NoteRepositoryCustomImpl}
     * 본문까지 내려갑니다.
     *
     * <p>{@code upsert} 는 Spring Data 관용 접두어(save, delete, update 등)와도 겹치지 않아
     * 리포지토리 인덱스가 방향을 판정하지 못하므로, 워커가 프래그먼트 구현체 본문까지
     * 내려가 네이티브 SQL 에서 대상 테이블을 읽어야만 {@code Note} 쓰기를 찾습니다.
     */
    public void upsert(Long id, String title) {
        notes.upsert(id, title);
    }
}
