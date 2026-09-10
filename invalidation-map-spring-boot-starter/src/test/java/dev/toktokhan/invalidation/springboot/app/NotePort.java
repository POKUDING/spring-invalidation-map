package dev.toktokhan.invalidation.springboot.app;

/**
 * 서비스가 영속성 계층을 직접 알지 않도록 두는 포트입니다.
 * {@code implementationsOf} 가 빈 팩토리에서 어댑터를 찾는지 확인하는 데 씁니다.
 */
public interface NotePort {

    Note create(String title);
}
