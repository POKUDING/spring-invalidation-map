package dev.toktokhan.invalidation.core.support;

import dev.toktokhan.invalidation.core.index.ClassRepository;
import dev.toktokhan.invalidation.core.index.EntityIndex;
import dev.toktokhan.invalidation.core.index.RepositoryIndex;
import dev.toktokhan.invalidation.core.resolve.ResolutionContext;
import dev.toktokhan.invalidation.core.scan.MethodFacts;
import dev.toktokhan.invalidation.core.walk.WalkState;

public record StubResolutionContext(
    ClassRepository classes,
    EntityIndex entities,
    RepositoryIndex repositories,
    WalkState state
) implements ResolutionContext {

    public static StubResolutionContext of(ClassRepository classes, EntityIndex entities,
        RepositoryIndex repositories, MethodFacts caller, boolean inTransaction, boolean readOnly) {
        return new StubResolutionContext(classes, entities, repositories,
            new WalkState(caller, inTransaction, readOnly));
    }
}
