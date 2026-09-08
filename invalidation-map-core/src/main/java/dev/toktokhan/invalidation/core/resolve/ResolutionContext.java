package dev.toktokhan.invalidation.core.resolve;

import dev.toktokhan.invalidation.core.index.ClassRepository;
import dev.toktokhan.invalidation.core.index.EntityIndex;
import dev.toktokhan.invalidation.core.index.RepositoryIndex;
import dev.toktokhan.invalidation.core.walk.WalkState;

/** 리졸버가 판정에 쓰는 주변 정보입니다. */
public interface ResolutionContext {

    ClassRepository classes();

    EntityIndex entities();

    RepositoryIndex repositories();

    /** 호출을 만난 순간의 워커 상태입니다. 트랜잭션 판정과 문자열 상수 조회에 씁니다. */
    WalkState state();
}
