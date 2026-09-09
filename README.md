# spring-invalidation-map

Spring Boot 애플리케이션의 엔드포인트마다 실제로 읽고 쓰는 JPA 엔티티를 자동으로 계산해,
OpenAPI 스펙의 `x-entities` 확장으로 노출하는 라이브러리입니다.

## 무엇을 하는가

프론트엔드는 write 요청 하나가 끝나면 어떤 GET 쿼리를 다시 가져와야 하는지(캐시 무효화)
알아야 합니다. 이 정보는 보통 개발자가 손으로 관리하다가 시간이 지나면서 실제 코드와
어긋납니다.

이 라이브러리는 Spring 컨텍스트가 뜬 뒤 첫 `/v3/api-docs` 요청 시점에 핸들러 메서드부터
호출 사슬을 따라가며 각 엔드포인트가 실제로 접근하는 JPA 엔티티 집합을 계산하고, 그 결과를
OpenAPI 오퍼레이션에 `x-entities` 확장으로 얹습니다. 소비자(프론트엔드 코드 생성기 등)는
이 정보로 캐시 무효화 로직을 자동 생성할 수 있습니다.

## 왜 짝짓기가 아니라 엔티티 집합인가

"이 write 는 저 GET 들을 무효화한다"를 스펙에 직접 나열하지 않습니다. 대신 **엔드포인트마다
자기가 읽고 쓰는 엔티티 집합**만 노출하고, 교집합 계산은 소비자가 합니다.

```
write 의 writes ∩ GET 의 reads ≠ ∅  →  무효화 대상
```

채택 근거는 디프입니다. GET 엔드포인트를 하나 추가하면 엔티티 집합 방식은 그 GET 항목만
바뀝니다. 짝 방식(`x-invalidates`처럼 write 쪽에 무효화 대상 GET 목록을 미리 계산해 넣는
방식)은 그 GET 을 읽는 write 수십 개의 목록에 전부 끼어들어가, 실제로는 무관한 변경 수백
줄에 진짜 변경이 묻힙니다.

## 설치

```groovy
dependencies {
    implementation 'dev.toktokhan.invalidation:invalidation-map-spring-boot-starter:0.1.0-SNAPSHOT'
}
```

아직 공개 저장소에 배포되지 않았다면, 이 저장소를 로컬 composite build 로 물려서 씁니다.

```groovy
// settings.gradle
includeBuild '/path/to/spring-invalidation-map'
```

`invalidation-map-core`(`@ReadsEntities` 등 탈출구 어노테이션)는 `invalidation-map-spring-boot-starter`
가 `api` 로 전이 노출하므로 따로 추가할 필요가 없습니다.

의존성만 추가하면 끝입니다. springdoc-openapi 와 Spring Data JPA 가 이미 클래스패스에 있다는
전제 아래 자동 설정이 적용되고, 별도 설정 없이도 `/v3/api-docs` 응답에 `x-entities` 가
실립니다.

## 소비자 예시

`writes ∩ reads ≠ ∅` 로 react-query 의 `invalidateQueries` 를 만드는 예시입니다.

```ts
// OpenAPI 스펙을 코드 생성 시점(또는 런타임)에 읽어 엔드포인트별 x-entities 를 뽑아 둡니다.
type EntitySet = { reads: string[]; writes: string[] };
const specByOperationId: Record<string, EntitySet> = loadFromOpenApiSpec();

function afterMutationSucceeds(mutationOperationId: string, queryClient: QueryClient) {
  const writes = new Set(specByOperationId[mutationOperationId]?.writes ?? []);
  if (writes.size === 0) return;

  for (const [queryKey, operationId] of registeredQueries()) {
    const reads = specByOperationId[operationId]?.reads ?? [];
    const intersects = reads.some((entity) => writes.has(entity));
    if (intersects) {
      queryClient.invalidateQueries({ queryKey });
    }
  }
}
```

`resolved: false` 인 엔드포인트는 교집합 계산 없이 무조건 무효화 대상으로 다룹니다(아래
"출력 형식" 참고).

## 출력 형식

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
  /v1/slots:
    post:
      x-entities:
        resolved: false
        unresolved:
          - "엔티티 접근을 찾지 못했습니다"
```

- `reads`, `writes` — 이 엔드포인트가 접근하는 엔티티를 **FQCN** 으로 담습니다. 단순 이름은
  패키지가 다른 동명 엔티티에서 충돌하므로 기본값이 아닙니다(`entity-naming: SIMPLE` 로
  단순 이름을 선택할 수 있습니다). 목록은 정렬되어 있고, 빈 집합은 키 자체를 생략합니다.
- `resolved` — `false` 일 때만 나타납니다. 판정에 실패한 엔드포인트를 조용히 빈 값으로
  두지 않고 명시적으로 드러내려는 것입니다.
- `unresolved` — 판정에 실패한 사유를 사람이 읽을 수 있는 문장으로 담습니다. 지금 나올 수
  있는 사유는 다섯 가지입니다.
  - `엔티티 접근을 찾지 못했습니다` — 이 엔드포인트에서 어떤 엔티티 접근도 찾지 못했습니다.
  - `엔티티를 특정하지 못했습니다: <호출> (호출한 메서드: <메서드>)` — 엔티티에 접근하는
    호출은 찾았지만 어느 엔티티인지 좁히지 못했습니다. 예: `em.persist(x)` 에서 `x` 의
    타입이 그 메서드 안에 전혀 나타나지 않는 경우.
  - `이벤트 타입을 식별하지 못했습니다: <메서드>` — `publishEvent` 호출은 찾았지만 어떤
    이벤트를 발행하는지 좁히지 못했습니다. 리스너가 아예 없는 이벤트를 발행할 때도 이
    사유가 붙습니다(과잉 표시).
  - `본문을 읽을 수 없습니다: <메서드>` / `클래스를 읽지 못했습니다: <클래스>` — 클래스
    바이트를 구하지 못했습니다.
  - `호출 사슬이 노드 예산 N 을 넘었습니다` — `node-budget` 을 넘겼습니다.
- **`resolved: false` 는 보수적으로 다루십시오.** 그 GET 은 어떤 write 뒤에나 무효화
  대상으로 보고, 그 write 는 전체 화면을 무효화하는 것으로 취급하는 편이 안전합니다.
  과잉 무효화는 재조회 한 번으로 끝나지만, 누락은 사용자가 오래된 값을 계속 보는
  문제로 이어집니다.
- 미해결 엔드포인트 목록은 애플리케이션 로그에도 한 번 출력됩니다(첫 스펙 요청 시점).

## 설정

`invalidation-map` 프리픽스로 바인딩됩니다. 전부 선택 사항이며 표의 값이 기본값입니다.

| 프로퍼티 | 기본값 | 설명 |
| --- | --- | --- |
| `invalidation-map.enabled` | `true` | 끄면 `x-entities` 를 붙이지 않고 분석도 하지 않습니다 |
| `invalidation-map.base-packages` | (비어 있음) | 호출 사슬을 따라 내려갈 패키지입니다. 비우면 `@SpringBootApplication` 의 패키지를 씁니다 |
| `invalidation-map.entity-naming` | `FQCN` | `FQCN` 또는 `SIMPLE`. 엔티티 이름 표기 방식입니다 |
| `invalidation-map.node-budget` | `20000` | 엔드포인트 하나가 호출 사슬을 따라 방문할 수 있는 최대 메서드 수입니다. 넘으면 그 엔드포인트가 미해결로 표시됩니다 |
| `invalidation-map.expand-read-associations` | `true` | 읽기 집합을 `@OneToMany`/`@ManyToOne`/`@OneToOne`/`@ManyToMany`/`@Embedded`/`@ElementCollection` 연관 한 단계만큼 넓힙니다 |
| `invalidation-map.fail-on-unresolved` | `false` | 참이면 미해결 엔드포인트가 하나라도 있을 때 부팅을 실패시킵니다. CI 에서 회귀를 잡는 용도입니다 |

분석은 `enabled: true` 이고 springdoc 이 클래스패스에 있을 때만 등록되며, 실제 계산은 첫
`/v3/api-docs` 요청이 올 때 한 번만 수행하고 캐시합니다. 부팅 시간에는 영향이 없고,
Swagger 를 아예 열지 않는 운영 환경에서는 계산 자체가 일어나지 않습니다.

## 탈출구 어노테이션

자동 분석은 반드시 뚫리는 지점이 있습니다. 개발자가 직접 선언할 수 있습니다.

```java
@ReadsEntities({Run.class, RunPartner.class})
@WritesEntities(Badge.class)
@GetMapping("/v1/runs/{runId}")
public RunResponse getRun(@PathVariable Long runId) { ... }

@InvalidationMapIgnore
@PostMapping("/v1/presigned-url")
public PresignedUrlResponse issueUploadUrl() { ... }
```

- `@ReadsEntities`, `@WritesEntities` — `Class<?>[]` 를 받으므로 컴파일 시 검증되고
  리네임에 안전합니다. 기본 동작은 분석 결과에 **추가**입니다. `override = true` 를 주면
  분석 결과를 대체합니다.
- `@InvalidationMapIgnore` — 이 엔드포인트를 분석 대상에서 아예 제외합니다.
  `x-entities` 자체가 붙지 않습니다. 인증, 헬스체크, 프리사인드 URL 발급처럼 엔티티를
  정말로 건드리지 않는 엔드포인트에 붙입니다. 붙이지 않으면 "엔티티 접근을 찾지
  못했습니다" 로 미해결 표시됩니다 — 미해결도 4.4 오차 방향 원칙에 따른 안전한
  결과이므로, 굳이 어노테이션을 강제하지는 않습니다.
- 핸들러 인터페이스와 구현 클래스 어느 쪽에 붙여도 인식합니다.

## 정확도와 한계

- **과잉 보고를 누락보다 우선합니다.** 판정이 불확실하면 항상 엔티티를 더 넣는 쪽으로
  기울입니다. 과잉 보고의 대가는 재조회 한 번이지만, 누락의 대가는 사용자가 오래된 값을
  계속 보는 것이기 때문입니다.
- **비동기 쓰기도 `writes` 에 함께 실립니다.** `@Async` 나
  `@TransactionalEventListener(phase = AFTER_COMMIT)` 를 지나서만 도달하는 엔티티도
  분석은 그 경로를 따라가 `writes` 에 넣지만, 출력에서 동기/비동기를 구분하지는 않습니다.
  이런 엔티티는 HTTP 응답이 나간 뒤 다른 스레드에서 바뀌므로, 응답 직후 곧바로 재조회하면
  아직 반영되지 않은 값을 읽을 수 있습니다. 소비자가 이 경우를 다루려면 약간의 지연이나
  재시도가 필요합니다.
- **MyBatis, 외부 캐시, 라이브러리가 정적으로 해석할 수 없는 복잡한 네이티브 SQL 은
  지원하지 않습니다.** 이런 경로만으로 데이터를 읽거나 쓰는 엔드포인트는 접근하는
  엔티티를 찾지 못해 `resolved: false` 로 표시됩니다. 조용히 틀린 값을 내는 대신
  명시적으로 미해결로 드러나므로, `@ReadsEntities`/`@WritesEntities` 로 직접 채우거나
  `resolved: false` 를 보수적으로 다루면 됩니다.
- **`EntityManager` 직접 사용도 다룹니다.** `createQuery`/`createNativeQuery` 는 쿼리
  문자열에서, `persist`/`merge`/`remove`/`find`/`getReference` 는 호출을 담은 메서드에
  나타난 엔티티 타입(`new`, 클래스 리터럴, 그 메서드의 파라미터 타입, 읽은 필드의 타입)
  에서 엔티티를 찾습니다. 바이트코드 스택을 추적하지 않으므로 한 메서드가 엔티티 두
  개를 건드리면 둘 다 보고됩니다(과잉). 후보가 하나도 없으면 "엔티티를 특정하지
  못했습니다" 로 미해결 표시됩니다. `flush`/`clear`/`detach`/`contains`/`unwrap` 처럼
  엔티티를 지목하지 않는 호출은 표시하지 않습니다 — 그 자리의 엔티티는 그 객체를 얻어
  온 조회·저장 호출에서 이미 보고됩니다.
- **이벤트 발행은 발행 메서드 안에서 이벤트 타입을 좁힐 수 있을 때 리스너까지 따라갑니다.**
  `new SomeEvent(...)` 로 만들어 발행하는 경우, 팩터리 메서드가 돌려준 이벤트를 발행하는
  경우, 파라미터나 필드로 받은 이벤트를 재발행하는 경우를 모두 덮습니다. 그 어느 경로로도
  좁히지 못하면 "이벤트 타입을 식별하지 못했습니다" 로 미해결 표시됩니다.
- **엔티티의 기본 테이블명은 여러 네이밍 규칙의 결과를 모두 후보로 등록합니다.** JPA 표준
  기본값, Hibernate 6 계열(Spring Boot 3.x 기본값), Hibernate 7 계열(Spring Boot 4.x
  기본값), 그리고 대문자마다 밑줄을 넣는 안전망입니다. Hibernate 6 과 7 은 숫자와 대문자가
  맞닿는 자리에서 결과가 갈리므로(`HTTPCache2Entry` → `httpcache2entry` /
  `httpcache2_entry`) 한쪽만 등록하면 다른 세대에서 네이티브 SQL 의 엔티티를 놓칩니다.
  `@Table(name = ...)` 이 있으면 그 값만 씁니다.
- **Spring Boot 3.x 와 4.x, JVM 17 과 21 을 모두 지원합니다.** 다만 Spring Boot 3.3.5 가
  쓰는 spring-data-commons 3.3.5 는 `RepositoryInformation.getFragments()` 가 "프래그먼트
  인터페이스 이름 + `Impl`"(예: `FooRepositoryCustom`/`FooRepositoryCustomImpl`) 관용구를
  놓치는 세대차가 있습니다(spring-data-commons 4.0.5 부터 해소됨. 원인은
  `RepositoryFactoryBeanSupport` 가 `customImplementation` 과 `repositoryFragments` 를
  분리해 두던 것을 4.x 가 통합한 데 있습니다). **"리포지토리 인터페이스 이름 + `Impl`"**
  (예: `FooRepository`/`FooRepositoryImpl`) 레거시 관용구는 3.3.5 에서도 정상 조회되므로
  이 세대차의 영향을 받지 않습니다 — 놓치는 쪽은 그 반대인 "프래그먼트 인터페이스 이름
  + `Impl`" 쪽입니다. 이 라이브러리는 리포지토리 인터페이스와 그 상위 인터페이스 전체를
  빈 팩토리에서 직접 스캔해 이 세대차를 내부적으로 흡수하므로, 소비자가 별도로 대응할
  필요는 없습니다.
- **springdoc-openapi 는 2.0.0 이상을 요구합니다.** 이 라이브러리는 springdoc 이 그룹별
  스펙(`springdoc.group-configs` 등)에도 커스터마이저를 공통 적용하도록 표시하는 마커
  인터페이스 `GlobalOperationCustomizer` 를 `@ConditionalOnClass` 로 확인합니다. 이
  클래스가 없는 springdoc 을 쓰면 예외나 로그 없이 자동 설정이 조용히 매치되지 않고
  `x-entities` 가 통째로 안 실립니다. Maven Central 에서 받은 실제 jar 로 확인한 결과
  `springdoc-openapi-starter-common` 2.0.0(v2 최초 릴리스)부터 이 태스크가 검증한 2.5.0,
  3.0.1 까지 전부 이 클래스를 갖고 있어, 위에서 이미 밝힌 "springdoc 2.x 와 3.x" 지원
  범위를 넘어서는 추가 제약은 없습니다.

## 동작 원리

엔드포인트 신원(`RequestMappingHandlerMapping`), 리포지토리와 엔티티의 대응
(`Repositories`), 인터페이스에 실제로 꽂힌 구현체(빈 팩토리)는 Spring 이 이미 알고 있는
사실이므로 다시 계산하지 않고 그대로 받아 씁니다. 이 라이브러리가 실제로 하는 일은 그
경계 안쪽, 즉 핸들러 메서드 본문부터 시작해 ASM 으로 바이트코드 호출 사슬만 따라가며
리포지토리 호출·`EntityManager` 호출·QueryDSL 호출·엔티티 변경자 호출을 만나는 순서대로
엔티티 접근으로 바꾸는 것입니다.
