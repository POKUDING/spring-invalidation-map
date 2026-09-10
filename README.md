# spring-invalidation-map

[English](./README.md) | [한국어](./README.ko.md)

spring-invalidation-map is a Spring Boot library that computes, from bytecode, which JPA entities each
endpoint actually reads and writes, and publishes that as an `x-entities` extension on every OpenAPI
operation.

Suppose `POST /v1/runs` creates a run and, through a commit-time event, contributes to a badge. A run
search query reads runs. The two operations overlap on `Run`, so that query is now stale. The library
computes the relationship and puts it in the spec:

```yaml
paths:
  /v1/runs:
    post:
      operationId: createRun
      x-entities:
        reads: [com.example.run.Run]
        writes:
          - com.example.run.Run
          - com.example.badge.BadgeRunContribution
    get:
      operationId: searchRuns
      x-entities:
        reads:
          - com.example.run.Run
          - com.example.run.RunPartner
```

A consumer computes `writes ∩ reads ≠ ∅` and knows `createRun` invalidates `searchRuns`.

`x-entities` says an endpoint **may** touch those entities. It does not claim the rows changed, that a
cache holds them, or that a write is visible by the time the HTTP response returns. The library prefers
over-reporting to omission: one extra refetch costs a round trip, while a missed entity leaves a stale
value on screen. Where it cannot decide, it says so with `resolved: false` instead of returning an
empty answer.

## How it works

```text
Spring metadata ─┬─ RequestMappingHandlerMapping ─► endpoints
                 ├─ Repositories                 ─► repository → entity
                 └─ bean factory                 ─► interface → implementation
                                                          │
handler method body ─► ASM call-chain walk ───────────────┼─► entity accesses ─► x-entities
                                                          │
                       base-packages bounds the walk ─────┘
```

1. Spring already knows the endpoint identities, the repository-to-entity mapping, and which
   implementation is wired to an interface. The library reads those instead of recomputing them.
2. From each handler method body it walks the bytecode call chain with ASM, staying inside
   `base-packages`.
3. Repository calls, `EntityManager` calls, QueryDSL calls, entity mutators, and `publishEvent` paths
   become entity accesses with a read or write direction.
4. Reads expand transitively over associations, so a query returning a graph reports the whole
   graph. Writes expand over cascading associations only, because a `cascade`/`orphanRemoval`
   association means the database writes the child row too.
5. The result is attached to each OpenAPI operation on the first `/v3/api-docs` request, then cached.

Positions the walk cannot decide are recorded as `unresolved` with a reason, never dropped.

## Modules

| Module | Responsibility |
| --- | --- |
| [`invalidation-map-core`](./invalidation-map-core) | Bytecode analysis engine. No Spring dependency; ASM only |
| [`invalidation-map-spring-boot-starter`](./invalidation-map-spring-boot-starter) | Fills the analysis from Spring runtime metadata and injects `x-entities` through springdoc |

The core knows nothing about Spring — it asks its environment through a `ProgramModel` interface that
the starter implements. A frontend package is intentionally absent: the intersection is one line, and
query-key shapes, cache libraries, and transport belong to the application.

## Install

Java 17 or newer, Spring Boot 3.x or 4.x, springdoc-openapi 2.0.0 or newer.

```groovy
implementation 'io.github.pokuding:invalidation-map-spring-boot-starter:0.3.0'
```

No configuration is required. When springdoc-openapi and Spring Data JPA are on the classpath the
starter registers itself and `/v3/api-docs` carries `x-entities`. The annotations for declaring
entities by hand live in `invalidation-map-core`, which the starter exposes transitively.

To see `x-entities` in the Swagger UI, enable extension display. springdoc leaves this unset, so
Swagger UI's own default hides vendor extensions; the JSON carries them either way.

```yaml
springdoc:
  swagger-ui:
    show-extensions: true
```

## Reading x-entities

| Key | Meaning |
| --- | --- |
| `reads` | Entities this endpoint reads. Sorted; the key is omitted when empty |
| `writes` | Entities this endpoint writes. Same rule |
| `resolved` | Present as `false` only when the analysis could not decide |
| `unresolved` | Human-readable reasons for that failure |

Entity names are fully qualified. Simple names collide across packages, so they are not the default;
`entity-naming: SIMPLE` switches to them.

The spec carries entity sets rather than a precomputed list of "this write invalidates these queries".
The reason is diff size. Adding one query changes only that query's entry, while precomputed pairs
would insert it into the list of every write that reads it, burying the real change.

A react-query consumer looks like this.

```ts
type EntitySet = { reads?: string[]; writes?: string[]; resolved?: boolean };
const specByOperationId: Record<string, EntitySet> = loadFromOpenApiSpec();

function afterMutationSucceeds(mutationOperationId: string, queryClient: QueryClient) {
  const mutation = specByOperationId[mutationOperationId];

  // An unresolved write could have changed anything, so invalidate everything.
  if (mutation?.resolved === false) {
    queryClient.invalidateQueries();
    return;
  }

  const writes = new Set(mutation?.writes ?? []);
  if (writes.size === 0) return;

  for (const [queryKey, operationId] of registeredQueries()) {
    const query = specByOperationId[operationId];
    const intersects =
      query?.resolved === false || (query?.reads ?? []).some((e) => writes.has(e));
    if (intersects) {
      queryClient.invalidateQueries({ queryKey });
    }
  }
}
```

Handling `resolved === false` on **both** sides is the point. An unresolved write invalidates
everything; an unresolved query is invalidated after any write. Handling one side only reintroduces the
omission this library exists to prevent.

## When the analysis cannot decide

An endpoint the analysis could not resolve is reported explicitly rather than left empty.

```yaml
  /v1/slots:
    post:
      x-entities:
        resolved: false
        unresolved:
          - "엔티티 접근을 찾지 못했습니다"
```

Reasons are written in Korean. There are five.

| Reason | Meaning |
| --- | --- |
| `엔티티 접근을 찾지 못했습니다` | No entity access was found for this endpoint |
| `엔티티를 특정하지 못했습니다: <call>` | An accessing call was found but the entity could not be narrowed — `em.persist(x)` where `x`'s type never appears in that method |
| `이벤트 타입을 식별하지 못했습니다: <method>` | `publishEvent` was found but the event type could not be narrowed. Also reported when the event has no listener |
| `본문을 읽을 수 없습니다` / `클래스를 읽지 못했습니다` | Class bytes were unavailable |
| `호출 사슬이 노드 예산 N 을 넘었습니다` | `node-budget` was exceeded |

The starter logs the unresolved list once, on the first spec request.

Positions the analysis misses can be declared by hand.

```java
@ReadsEntities({Run.class, RunPartner.class})
@WritesEntities(Badge.class)
@GetMapping("/v1/runs/{runId}")
public RunResponse getRun(@PathVariable Long runId) { ... }

@InvalidationMapIgnore
@PostMapping("/v1/presigned-url")
public PresignedUrlResponse issueUploadUrl() { ... }
```

`@ReadsEntities` and `@WritesEntities` take `Class<?>[]`, so they are checked at compile time and
survive renames. They add to the analysis result; `override = true` replaces it.

`@InvalidationMapIgnore` removes the endpoint from analysis entirely, so no `x-entities` is attached.
Use it where an endpoint genuinely touches no entity — authentication, health checks, presigned URL
issuance. Without it such an endpoint is reported unresolved, which is already a safe outcome, so the
annotation is not required.

Both are recognised on the handler interface as well as the implementing class.

## Configuration

All optional, with the table's values as defaults.

| Property | Default | Description |
| --- | --- | --- |
| `invalidation-map.enabled` | `true` | When off, nothing is attached and no analysis runs |
| `invalidation-map.base-packages` | (empty) | Bounds the call-chain walk and endpoint selection. Empty means the `@SpringBootApplication` package |
| `invalidation-map.entity-naming` | `FQCN` | `FQCN` or `SIMPLE` |
| `invalidation-map.node-budget` | `20000` | Maximum methods one endpoint may visit. Exceeding it marks that endpoint unresolved |
| `invalidation-map.expand-read-associations` | `true` | Expands reads over `@OneToMany`/`@ManyToOne`/`@OneToOne`/`@ManyToMany`/`@Embedded`/`@ElementCollection` associations |
| `invalidation-map.fail-on-unresolved` | `false` | Fails startup when any endpoint is unresolved. For catching regressions in CI |

Analysis runs once, on the first `/v3/api-docs` request, and is cached. Startup time is unaffected, and
an environment that never opens Swagger never pays for it.

Association expansion is transitive, not one hop. In a chain like `ClassInfo → ClassInfoLabel → Label`
the grandchild ships in the response, so stopping at one hop leaves a stale value on screen after
anything writes `Label`. The cost was measured: in a project with 57 entities the widest endpoint set
is 4 entities (7%), against 3 for a single hop. A denser domain could grow that, so the starter logs
the widest set after each analysis and raises it to `warn` past half of all entities.

Writes expand over **cascading associations only**. `cascade = ALL`/`PERSIST`/`MERGE`/`REMOVE` and
`orphanRemoval = true` mean the database writes the child row when the parent is saved; an association
without them does not, so it is left out. This expansion reports what the mapping says rather than a
guess, so `expand-read-associations` does not switch it off — switching it off would drop entities that
really are written.

Endpoints outside `base-packages` are excluded, because their bodies are outside the walk and would be
reported unresolved forever — springdoc's own resources and Spring's `BasicErrorController` are the
usual cases, and third-party code cannot carry `@InvalidationMapIgnore`. The excluded list is logged, so
a `base-packages` setting that is too narrow is visible rather than silently dropping real endpoints.

## Guarantee boundary

The library reports entity access for code reachable from a handler method through bytecode the JVM can
read, inside `base-packages`.

Outside that boundary: MyBatis, `JdbcTemplate`, the JPA Criteria API, external caches, and native SQL
that cannot be resolved statically. An endpoint that touches data only through those paths is reported
`resolved: false` rather than silently wrong.

**Synchronous and asynchronous writes are not distinguished.** An entity reached only through `@Async`
or `@TransactionalEventListener(phase = AFTER_COMMIT)` still appears in `writes`, but the output does
not mark it as deferred. Such an entity changes on another thread after the HTTP response is sent, so a
refetch issued immediately may read a value that has not been updated yet. Those screens need a small
delay or a retry on the consumer side.

The library widens or reports unresolved wherever it cannot prove a narrow answer.

Spring Boot 3.3.5 ships spring-data-commons 3.3.5, whose `RepositoryInformation.getFragments()` omits
implementations found by the fragment-interface naming idiom (`FooRepositoryCustomImpl` for
`FooRepositoryCustom`). The repository-interface idiom (`FooRepositoryImpl`) is reported correctly on
3.3.5. The starter scans the bean factory directly in addition to `getFragments()`, so both idioms
produce the same result and consumers need no workaround.

## Development

```bash
./gradlew check                 # core and starter on Boot 4.x, plus boot3Test on Boot 3.3.5
./gradlew publishToMavenLocal   # signs when a key is configured
```

`boot3Test` runs the same starter tests against a Spring Boot 3.3.5 classpath, so the claim that one
artifact supports both generations is tested rather than asserted.

## License

Apache-2.0
