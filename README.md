# spring-invalidation-map

엔드포인트마다 실제로 읽고 쓰는 JPA 엔티티를 바이트코드에서 계산해 OpenAPI 스펙에 실어 보냅니다.

```yaml
paths:
  /v1/runs:
    post:                                  # 이 write 가 바꾸는 것
      operationId: createRun
      x-entities:
        reads: [com.example.run.Run]
        writes:
          - com.example.run.Run
          - com.example.badge.BadgeRunContribution
    get:                                   # 이 GET 이 읽는 것
      operationId: searchRuns
      x-entities:
        reads:
          - com.example.run.Run
          - com.example.run.RunPartner
```

`createRun` 의 `writes` 와 `searchRuns` 의 `reads` 가 `Run` 에서 겹칩니다. 그래서 `createRun`
성공 뒤에는 `searchRuns` 를 다시 가져와야 합니다 — 소비자는 이 교집합만 계산하면 됩니다.

프론트엔드는 write 요청이 끝나면 어떤 GET 을 다시 불러야 하는지 알아야 합니다. 그 목록은
보통 개발자가 손으로 관리하고, 시간이 지나면 코드와 어긋납니다. 이 라이브러리는 핸들러
메서드부터 호출 사슬을 따라가 엔드포인트별 엔티티 집합을 계산해 `x-entities` 로 노출합니다.
코드가 바뀌면 스펙도 함께 바뀝니다.

## 설치

```groovy
dependencies {
    implementation 'io.github.pokuding:invalidation-map-spring-boot-starter:0.2.0'
}
```

Spring Boot 3.x 와 4.x, JVM 17 과 21 에서 돕니다. springdoc-openapi 는 2.0.0 이상이 필요합니다
— 그룹별 스펙에도 커스터마이저를 적용하려면 `GlobalOperationCustomizer` 가 있어야 하고, 그
클래스가 없으면 자동 설정이 조용히 매치되지 않습니다.

설정은 필요하지 않습니다. springdoc-openapi 와 Spring Data JPA 가 클래스패스에 있으면 스타터가
자동 설정을 등록하고, `/v3/api-docs` 응답에 `x-entities` 를 얹습니다. 직접 선언용 어노테이션이
들어 있는 `invalidation-map-core` 는 스타터가 `api` 로 전이 노출하므로 따로 추가하지 않습니다.

배포 전이라면 composite build 로 물려 씁니다.

```groovy
// settings.gradle
includeBuild '/path/to/spring-invalidation-map'
```

## x-entities 읽기

| 키 | 내용 |
| --- | --- |
| `reads` | 이 엔드포인트가 읽는 엔티티. 정렬되어 있고, 비면 키를 생략합니다 |
| `writes` | 이 엔드포인트가 쓰는 엔티티. 같은 규칙입니다 |
| `resolved` | 판정에 실패했을 때만 `false` 로 나타납니다 |
| `unresolved` | 실패 사유를 사람이 읽는 문장으로 담습니다 |

엔티티 이름은 FQCN 입니다. 단순 이름은 패키지가 다른 동명 엔티티에서 충돌하므로 기본값이
아니고, `entity-naming: SIMPLE` 로 바꿀 수 있습니다.

스펙에 "이 write 는 저 GET 들을 무효화한다"를 미리 계산해 넣지 않고 엔티티 집합만 노출하는
것은 디프 때문입니다. GET 하나를 추가하면 엔티티 집합 방식은 그 GET 항목만 바뀌지만, 짝을
미리 넣는 방식은 그 GET 을 읽는 write 수십 개의 목록에 전부 끼어들어 진짜 변경이 묻힙니다.

react-query 를 쓰는 소비자라면 이렇게 됩니다.

```ts
type EntitySet = { reads?: string[]; writes?: string[]; resolved?: boolean };
const specByOperationId: Record<string, EntitySet> = loadFromOpenApiSpec();

function afterMutationSucceeds(mutationOperationId: string, queryClient: QueryClient) {
  const mutation = specByOperationId[mutationOperationId];

  // 판정에 실패한 write 는 무엇을 바꿨는지 알 수 없으므로 전부 무효화합니다.
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

## 판정하지 못할 때

분석이 엔티티 접근을 찾지 못하면 그 엔드포인트는 빈 값이 아니라 `resolved: false` 와 사유로
표시됩니다.

```yaml
  /v1/slots:
    post:
      x-entities:
        resolved: false
        unresolved:
          - "엔티티 접근을 찾지 못했습니다"
```

`unresolved` 에 담기는 사유는 다섯 가지입니다.

| 사유 | 뜻 |
| --- | --- |
| `엔티티 접근을 찾지 못했습니다` | 이 엔드포인트에서 어떤 엔티티 접근도 찾지 못했습니다 |
| `엔티티를 특정하지 못했습니다: <호출>` | 접근하는 호출은 찾았지만 어느 엔티티인지 좁히지 못했습니다. `em.persist(x)` 에서 `x` 의 타입이 그 메서드 안에 나타나지 않는 경우입니다 |
| `이벤트 타입을 식별하지 못했습니다: <메서드>` | `publishEvent` 는 찾았지만 어떤 이벤트인지 좁히지 못했습니다. 리스너가 없는 이벤트를 발행할 때도 붙습니다 |
| `본문을 읽을 수 없습니다` / `클래스를 읽지 못했습니다` | 클래스 바이트를 구하지 못했습니다 |
| `호출 사슬이 노드 예산 N 을 넘었습니다` | `node-budget` 을 넘겼습니다 |

**`resolved: false` 는 보수적으로 다루십시오.** 그 GET 은 어떤 write 뒤에나 무효화 대상으로
보고, 그 write 는 전체를 무효화하는 것으로 취급하십시오(이유는 「한계」 첫 항목에 있습니다).
스타터는 첫 스펙 요청 시점에 미해결 목록을 애플리케이션 로그에도 한 번 남깁니다.

찾지 못하는 자리는 직접 선언해서 채웁니다.

```java
@ReadsEntities({Run.class, RunPartner.class})
@WritesEntities(Badge.class)
@GetMapping("/v1/runs/{runId}")
public RunResponse getRun(@PathVariable Long runId) { ... }

@InvalidationMapIgnore
@PostMapping("/v1/presigned-url")
public PresignedUrlResponse issueUploadUrl() { ... }
```

`@ReadsEntities` 와 `@WritesEntities` 는 `Class<?>[]` 를 받아 컴파일 시 검증되고 리네임에
안전합니다. 기본은 분석 결과에 추가하는 것이고, `override = true` 를 주면 대체합니다.

`@InvalidationMapIgnore` 는 그 엔드포인트를 분석에서 아예 빼서 `x-entities` 를 붙이지 않습니다.
인증, 헬스체크, 프리사인드 URL 발급처럼 엔티티를 정말 건드리지 않는 곳에 씁니다. 붙이지
않아도 미해결로 표시되므로 안전한 결과이긴 하고, 그래서 강제하지는 않습니다.

두 어노테이션 모두 핸들러 인터페이스와 구현 클래스 어느 쪽에 붙여도 인식합니다.

## 설정

프로퍼티는 `invalidation-map` 프리픽스를 씁니다. 전부 선택 사항입니다.

| 프로퍼티 | 기본값 | 설명 |
| --- | --- | --- |
| `enabled` | `true` | 끄면 `x-entities` 를 붙이지 않고 분석도 하지 않습니다 |
| `base-packages` | (비어 있음) | 호출 사슬을 따라 내려갈 패키지. 비우면 `@SpringBootApplication` 의 패키지를 씁니다 |
| `entity-naming` | `FQCN` | `FQCN` 또는 `SIMPLE` |
| `node-budget` | `20000` | 엔드포인트 하나가 방문할 수 있는 최대 메서드 수. 넘으면 미해결로 표시됩니다 |
| `expand-read-associations` | `true` | 읽기 집합을 `@OneToMany`/`@ManyToOne`/`@OneToOne`/`@ManyToMany`/`@Embedded`/`@ElementCollection` 연관 한 단계만큼 넓힙니다 |
| `fail-on-unresolved` | `false` | 미해결 엔드포인트가 하나라도 있으면 부팅을 실패시킵니다. CI 에서 회귀를 잡는 용도입니다 |

스타터는 첫 `/v3/api-docs` 요청에 한 번만 계산하고 그 결과를 캐시합니다. 부팅 시간은 늘지
않고, Swagger 를 열지 않는 환경에서는 계산 자체를 하지 않습니다.

## 한계

판정이 불확실하면 이 라이브러리는 엔티티를 더 넣는 쪽으로 기울입니다. 과잉 보고의 대가는
재조회 한 번이지만, 누락의 대가는 사용자가 오래된 값을 계속 보는 것입니다. 아래 한계도 모두
이 방향을 따릅니다.

**비동기 쓰기.** `@Async` 나 `@TransactionalEventListener(phase = AFTER_COMMIT)` 를 지나서만
도달하는 엔티티도 `writes` 에 넣지만, 출력에서 동기와 구분하지 않습니다. 이런 엔티티는 HTTP
응답이 나간 뒤 다른 스레드에서 바뀌므로, 응답 직후 곧바로 재조회하면 아직 반영되지 않은 값을
읽을 수 있습니다. 소비자 쪽에서 약간의 지연이나 재시도가 필요합니다.

**MyBatis, 외부 캐시, 정적으로 해석할 수 없는 네이티브 SQL.** 이런 경로만으로 데이터를 다루는
엔드포인트는 `resolved: false` 로 표시됩니다. 조용히 틀린 값을 내는 대신 드러납니다. 직접
선언용 어노테이션으로 채우거나 보수적으로 다루십시오.

**Spring Boot 3.3.5 의 프래그먼트 조회.** spring-data-commons 3.3.5 는 프래그먼트 인터페이스
이름에 `Impl` 을 붙인 구현체를 `getFragments()` 로 돌려주지 않습니다(`FooRepositoryCustom` 에
대한 `FooRepositoryCustomImpl`). 리포지토리 인터페이스 이름을 쓰는 `FooRepositoryImpl` 은
3.3.5 에서도 정상입니다. 이 라이브러리는 `getFragments()` 결과와 별개로 빈 팩토리를 직접 스캔해
구현체를 찾으므로 두 경우 모두 같은 결과가 나옵니다. 소비자가 대응할 것은 없습니다.

## 동작 원리

Spring 이 이미 알고 있는 사실은 다시 계산하지 않고 그대로 받아 씁니다. 엔드포인트 신원은
`RequestMappingHandlerMapping` 에서, 리포지토리와 엔티티의 대응은 `Repositories` 에서,
인터페이스에 실제로 꽂힌 구현체는 빈 팩토리에서 받습니다.

그 경계 안쪽이 이 라이브러리의 몫입니다. 핸들러 메서드 본문부터 ASM 으로 바이트코드 호출
사슬을 따라가며 다음을 엔티티 접근으로 바꿉니다.

- **Spring Data 리포지토리 호출** — 메서드 이름과 `@Query`, `@Modifying` 으로 대상 엔티티와
  읽기·쓰기 방향을 판정합니다
- **`EntityManager` 호출** — `createQuery` 와 `createNativeQuery` 는 쿼리 문자열에서
  엔티티와 테이블 이름을 뽑습니다. `persist`/`merge`/`remove`/`find`/`getReference` 는
  호출을 담은 메서드에 나타난 엔티티 타입(`new`, 클래스 리터럴, 파라미터 타입, 읽은 필드의
  타입)에서 찾습니다. 바이트코드 스택을 추적하지 않으므로 한 메서드가 엔티티 두 개를
  건드리면 둘 다 보고합니다. `flush`/`clear`/`detach`/`contains`/`unwrap` 은 엔티티를
  지목하지 않으므로 표시하지 않습니다 — 그 자리의 엔티티는 그 객체를 얻어 온 조회·저장
  호출에서 이미 보고됩니다
- **테이블 이름 역매핑** — `@Table(name = ...)` 이 있으면 그 값만 쓰고, 없으면 여러 명명
  규칙의 결과를 모두 후보로 등록합니다. JPA 표준 기본값, Hibernate 6 계열(Boot 3.x 기본값),
  Hibernate 7 계열(Boot 4.x 기본값), 그리고 대문자마다 밑줄을 넣는 안전망입니다. 6 과 7 은
  숫자와 대문자가 맞닿는 자리에서 갈리므로(`HTTPCache2Entry` → `httpcache2entry` /
  `httpcache2_entry`) 한쪽만 등록하면 다른 세대에서 네이티브 SQL 의 엔티티를 놓칩니다
- **QueryDSL 호출** — Q클래스의 타입 인자에서 엔티티를 얻습니다
- **엔티티 변경자 호출** — 더티 체킹으로 쓰기가 되는 세터와 변경 메서드를 찾습니다
- **`@EventListener` 로 이어지는 경로** — 발행 메서드 안에서 이벤트 타입을 좁힐 수 있으면
  리스너 본문까지 따라갑니다. `new SomeEvent(...)` 로 만들어 발행하는 경우, 팩터리가
  돌려준 이벤트를 발행하는 경우, 파라미터나 필드로 받아 재발행하는 경우를 덮습니다

호출 사슬은 `base-packages` 안에서만 내려갑니다. 인터페이스 호출을 만나면 빈 팩토리와 Spring
Data 프래그먼트 색인에서 구현체를 찾아 그 본문까지 방문합니다. 판정할 수 없는 자리를 만나면
그 사유를 `unresolved` 에 남깁니다.
