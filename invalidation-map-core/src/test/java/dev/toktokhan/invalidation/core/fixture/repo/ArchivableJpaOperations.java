package dev.toktokhan.invalidation.core.fixture.repo;

import org.springframework.data.jpa.repository.Modifying;

/**
 * 상위 인터페이스에 선언된 쓰기 메서드입니다. 이름이 READ/WRITE 어느 접두어에도 맞지
 * 않아, {@code @Modifying} 판정이 {@code resolveMethod} 로 상위 타입까지 올라가야만
 * 통과하는 경로를 만듭니다.
 */
public interface ArchivableJpaOperations {

    @Modifying
    void wipe(Long id);
}
