package dev.toktokhan.invalidation.core.fixture.entity;

import jakarta.persistence.MappedSuperclass;

@MappedSuperclass
public abstract class BaseRecord {

    private boolean deleted;

    /** 상위 타입에 선언된 변경자입니다. */
    public void markDeleted() {
        this.deleted = true;
    }

    public boolean isDeleted() {
        return deleted;
    }
}
