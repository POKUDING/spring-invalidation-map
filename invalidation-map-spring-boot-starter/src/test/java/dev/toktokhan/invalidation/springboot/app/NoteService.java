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
     * <p>{@code notes} 를 {@link NoteRepositoryCustom} 타입 지역 변수에 대입한 뒤 그 변수로
     * 호출합니다. {@code SpringProgramModel.implementationsOf} 는 프래그먼트 구현체를
     * 리포지토리 인터페이스가 아니라 프래그먼트 인터페이스 이름으로만 색인합니다(직접 확인:
     * {@code implementationsOf(NoteJpaRepository)} 는 빈 집합, {@code
     * implementationsOf(NoteRepositoryCustom)} 은 {@code [NoteJpaRepository,
     * NoteRepositoryCustomImpl]}). 호출 지점의 바이트코드 {@code invokeinterface} owner 는
     * 호출식의 정적 타입 그대로 남으므로(직접 확인: {@code javap} 로 {@code notes.upsert(...)}
     * 를 부르면 owner 가 {@code NoteJpaRepository} 로 찍힘), {@code notes.upsert(...)} 로
     * 그대로 부르면 워커가 {@link NoteRepositoryCustomImpl} 로 내려가지 못해 {@code Note}
     * 쓰기를 찾지 못합니다.
     *
     * <p>{@link NoteRepositoryCustom} 을 필드로 직접 주입받지 않은 이유는 Spring Data 가
     * {@code NoteRepositoryCustomImpl} 자체도 별도 빈({@code noteRepositoryCustomImpl})으로
     * 등록해서입니다(직접 확인: {@code NoteRepositoryCustom} 타입으로 주입을 시도하면
     * {@code noteRepositoryCustomImpl} 과 {@code noteJpaRepository} 두 후보가 걸려
     * {@code NoUniqueBeanDefinitionException} 이 납니다). {@code notes}({@link
     * NoteJpaRepository} 타입, 후보가 하나뿐이라 모호하지 않음)를 그대로 주입받고, 호출
     * 지점에서만 지역 변수로 타입을 좁혀 정적 타입만 바꿉니다.
     *
     * <p>{@code upsert} 는 Spring Data 관용 접두어(save, delete, update 등)와도 겹치지 않아
     * 리포지토리 인덱스가 방향을 판정하지 못하므로, 워커가 프래그먼트 구현체 본문까지
     * 내려가 네이티브 SQL 에서 대상 테이블을 읽어야만 {@code Note} 쓰기를 찾습니다.
     */
    public void upsert(Long id, String title) {
        NoteRepositoryCustom fragment = notes;
        fragment.upsert(id, title);
    }
}
