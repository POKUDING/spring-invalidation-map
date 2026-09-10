package dev.toktokhan.invalidation.springboot;

/** 스펙에 엔티티를 어떤 이름으로 실을지 정합니다. */
public enum EntityNaming {

    /** 패키지를 포함한 이름입니다. 동명 엔티티 충돌이 없습니다. */
    FQCN,

    /** 단순 클래스명입니다. 짧지만 패키지가 다른 동명 엔티티에서 충돌합니다. */
    SIMPLE
}
