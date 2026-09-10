package dev.toktokhan.invalidation.core.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.AccessKind;
import dev.toktokhan.invalidation.core.EntityAccess;
import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.fixture.entity.Trip;
import dev.toktokhan.invalidation.core.index.ClassRepository;
import dev.toktokhan.invalidation.core.index.EntityIndex;
import dev.toktokhan.invalidation.core.index.RepositoryIndex;
import dev.toktokhan.invalidation.core.support.FakeProgramModel;
import dev.toktokhan.invalidation.core.support.StubResolutionContext;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DirtyCheckResolverTest {

    private static final String TRIP = MethodRefs.internalNameOf(Trip.class);
    private static final MethodRef RENAME = new MethodRef(TRIP, "rename", "(Ljava/lang/String;)V");
    private static final MethodRef TITLE = new MethodRef(TRIP, "title", "()Ljava/lang/String;");

    private final FakeProgramModel program = FakeProgramModel.create().withEntity(Trip.class);
    private final ClassRepository classes = new ClassRepository(program);
    private final EntityIndex entities = new EntityIndex(classes, Set.of(TRIP));
    private final RepositoryIndex repositories = new RepositoryIndex(program, classes);
    private final DirtyCheckResolver resolver = new DirtyCheckResolver();

    @Test
    void resolve_mutatorInWritableTransaction_isWrite() {
        assertThat(resolve(RENAME, true, false))
            .contains(new EntityAccess(Set.of(TRIP), AccessKind.WRITE));
    }

    @Test
    void resolve_mutatorInReadOnlyTransaction_returnsEmpty() {
        assertThat(resolve(RENAME, true, true)).isEmpty();
    }

    @Test
    void resolve_mutatorOutsideTransaction_returnsEmpty() {
        assertThat(resolve(RENAME, false, false)).isEmpty();
    }

    @Test
    void resolve_nonMutatorInTransaction_returnsEmpty() {
        assertThat(resolve(TITLE, true, false)).isEmpty();
    }

    private Optional<EntityAccess> resolve(MethodRef callee, boolean inTransaction, boolean readOnly) {
        return resolver.resolve(callee, StubResolutionContext.of(
            classes, entities, repositories, null, inTransaction, readOnly));
    }
}
