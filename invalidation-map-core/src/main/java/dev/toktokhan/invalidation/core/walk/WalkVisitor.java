package dev.toktokhan.invalidation.core.walk;

import dev.toktokhan.invalidation.core.MethodRef;

/** 워커가 호출 지점을 하나 만날 때마다 부릅니다. */
public interface WalkVisitor {

    void onCall(MethodRef callee, WalkState state);
}
