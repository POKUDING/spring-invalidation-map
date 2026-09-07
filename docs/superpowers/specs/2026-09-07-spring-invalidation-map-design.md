# spring-invalidation-map 설계

- 작성일: 2026-09-07
- 상태: 설계 승인 대기
- 대상 독자: 이 라이브러리를 구현하거나 채택하는 개발자

## 1. 목적

Spring Boot 컨트롤러의 write 엔드포인트(POST/PUT/PATCH/DELETE)가 실행된 뒤 클라이언트가
어떤 GET 엔드포인트를 재조회해야 하는지를, 사람이 손으로 적지 않고 코드에서 자동으로 뽑아
OpenAPI 스펙에 노출합니다.

프론트엔드가 자동 생성한 API 클라이언트에서 캐시 무효화(react-query `invalidateQueries` 등)를
자동화할 수 있는 형태가 목표입니다.

이 라이브러리는 특정 프로젝트 전용이 아니라 **Spring Boot 3 + JPA + springdoc 환경 전반에서
재사용할 수 있는 범용 라이브러리**로 만듭니다.

## 2. 접근 방식

### 2.1 짝짓기가 아니라 엔드포인트별 엔티티 집합

"이 write 는 저 GET 들을 무효화한다"를 스펙에 직접 나열하지 않습니다. 대신 **엔드포인트마다
자기가 읽고 쓰는 엔티티 집합**을 노출하고, 교집합 계산은 소비자(프론트엔드 코드 생성기)가
합니다.

```
write 의 writes ∩ GET 의 reads ≠ ∅  →  무효화 대상
```

채택 근거는 디프입니다. GET 하나를 추가하면 엔티티 집합 방식은 그 GET 항목만 바뀝니다. 짝
방식은 그 GET 을 읽는 write 수십 개의 목록에 전부 끼어들어가, 실제 변화가 무관한 변경 수백 줄에
묻힙니다.

스펙 크기도 짝 방식이 더 크지만 결정적 근거는 아닙니다. 측정값은 9.2 에 있습니다.

### 2.2 런타임 분석 (1차), 빌드 시 분석 (이후)

Spring 컨텍스트가 뜬 뒤 분석합니다. 빌드 시 분석은 코어를 재사용해 나중에 추가합니다.

런타임을 먼저 하는 이유는 Spring 이 이미 정답을 갖고 있기 때문입니다. 빌드 시 분석은 Spring 의
해석 규칙을 다시 구현해야 합니다.

| 필요한 정보 | 런타임 | 빌드 시 |
| --- | --- | --- |
| 엔드포인트 신원 | `RequestMappingHandlerMapping` 이 계산해 둔 값 | `@RequestMapping` 계열 직접 파싱 |
| 리포지토리 → 엔티티 | `Repositories.getEntityInformation()` | 제네릭 시그니처 파싱 |
| 인터페이스 → 구현체 | 빈 팩토리가 실제로 주입한 빈 | 클래스패스 `implements` 스캔 |
| 메서드 본문 호출 사슬 | ASM 필요 | ASM 필요 (동일) |

소비자 입장에서도 의존성 한 줄로 끝나는 편이 채택 장벽이 낮습니다.

## 3. 모듈 구조

```
invalidation-map-core                   Spring 의존 없음. ASM 만 사용.
                                        입력: ProgramModel / 출력: InvalidationMap

invalidation-map-spring-boot-starter    ProgramModel 을 Spring 메타데이터로 구현.
                                        OperationCustomizer 로 x-entities 주입.
                                        Spring Boot auto-configuration.

(이후) invalidation-map-gradle-plugin    ProgramModel 을 클래스패스 스캔으로 구현.
(이후) invalidation-map-mybatis          MyBatis 용 EntityResolver.
```

의존 방향은 `starter → core` 단방향입니다. 코어는 `spring-*` 아티팩트를 의존하지 않습니다.
이 제약이 이후 빌드 시 모듈이 코어를 재사용할 수 있게 하는 유일한 근거입니다.

### 3.1 ProgramModel — 코어와 환경 사이의 이음새

코어가 환경에 묻는 것은 다섯 가지뿐입니다.

```java
public interface ProgramModel {
    List<Endpoint> endpoints();
    byte[] classBytes(String internalName);
    Optional<String> entityFor(String repositoryType);
    Set<String> implementationsOf(String interfaceType);
    Set<String> entities();
}

public record Endpoint(
    String httpMethod,      // GET, POST, ...
    String path,            // /v1/runs/{runId}
    String declaringClass,  // 핸들러 구현 클래스의 internal name
    String methodName,
    String methodDescriptor
) {}
```

`operationId` 는 이음새에 넣지 않습니다. 스타터는 `OperationCustomizer` 가 넘겨주는
`HandlerMethod` 의 `(선언 클래스, 메서드명, 디스크립터)` 로 `InvalidationMap` 을 찾습니다. springdoc 이
동명 메서드에 `_1` 을 붙이는 규칙에 의존하지 않습니다.

`@Transactional` 경계는 코어가 바이트코드에서 직접 읽으므로 이음새에 넣지 않습니다.

이 이음새 덕분에 코어는 가짜 `ProgramModel` 로 테스트할 수 있습니다. Spring 컨텍스트 없이
픽스처 클래스만 넣고 기대 결과와 비교합니다.

## 4. 분석 알고리즘

### 4.1 호출 사슬 따라가기

각 엔드포인트의 핸들러 메서드에서 시작합니다.

- `INVOKEVIRTUAL` / `INVOKEINTERFACE` / `INVOKESTATIC` / `INVOKESPECIAL` 의 소유자가 설정된
  패키지 루트 안이면 그 메서드로 진입합니다.
- 인터페이스 호출은 `implementationsOf` 로 구현체를 찾아 확장합니다.
- 람다는 `INVOKEDYNAMIC` 의 부트스트랩 인자에 담긴 메서드 핸들을 따라 합성 메서드로 진입합니다.
- `(클래스, 메서드, 디스크립터)` 방문 표시로 순환을 막습니다.
- 노드 예산(기본 20,000)을 초과하면 그 엔드포인트를 미해결로 표시하고 중단합니다.

`ApplicationEventPublisher.publishEvent` 호출은 사슬이 끊기는 지점이므로 따로 이어붙입니다.
`publishEvent` 를 호출하는 메서드 안에서 `NEW` 로 생성된 `ApplicationEvent` 하위 타입을 모두
수집하고, `@EventListener` / `@TransactionalEventListener` 메서드의 파라미터 타입에 할당 가능한
것을 찾아 그 리스너 본문으로 진입합니다. 파라미터가 상위 타입일 수 있으므로 타입 계층을 따라
매칭합니다.

이것은 근사입니다. 스택을 추적하지 않고 메서드 단위로 수집하므로, 한 메서드가 이벤트 두 종류를
생성하고 그중 하나만 발행하면 발행하지 않은 쪽의 리스너도 함께 따라갑니다. 과잉 보고 방향이므로
4.4 원칙에 맞습니다.

### 4.2 읽기 · 쓰기 판정 우선순위

먼저 맞는 규칙이 이깁니다.

1. `@Modifying` 이 붙은 리포지토리 메서드 → 쓰기
2. Spring Data 메서드명 규약
   - 읽기: `find` `read` `get` `query` `search` `stream` `exists` `count`
   - 쓰기: `save` `delete` `remove` `insert` `update`
3. `EntityManager`
   - 쓰기: `persist` `merge` `remove`
   - 읽기: `find` `getReference`
   - `createQuery` / `createNativeQuery` → 인자 문자열을 해당 Resolver 로 넘김
4. QueryDSL — `selectFrom` 계열은 읽기, `update` / `delete` 절은 쓰기
5. 더티체킹 변경자 호출 → 쓰기 (4.3 참고)

### 4.3 더티체킹 변경자 탐지

`.save()` 를 명시적으로 호출하는 관례는 프로젝트마다 다릅니다. 많은 프로젝트가
`@Transactional` 안에서 엔티티 메서드만 호출하고 끝냅니다. 이 경우를 잡지 못하면
라이브러리가 그런 프로젝트에서 조용히 틀립니다. 범용성의 핵심 요건입니다.

규칙은 다음과 같습니다.

- `@Entity` 클래스의 **인스턴스 메서드** 중, 자기 클래스 필드에 `PUTFIELD` 하거나 다른 변경자를
  호출하는 것을 **변경자**로 식별합니다. (전이적으로 계산합니다.)
- `<init>` 과 정적 메서드는 변경자 집합에서 제외합니다. 정적 팩터리는 새 객체를 만드는 것이고,
  영속화 여부는 `save()` 호출이 판정합니다.
- 핸들러에서 `@Transactional` 경계 안으로 도달한 변경자 호출을 그 엔티티의 쓰기로 판정합니다.
- `@Transactional(readOnly = true)` 경계 안에서는 억제합니다. Hibernate 가 플러시하지 않으므로
  쓰기가 아닙니다.

로컬에서 생성하고 저장하지 않은 객체의 변경자를 호출하면 쓰기로 잘못 판정됩니다. 과잉 보고
방향이므로 재조회 한 번이 늘어날 뿐이며, 오래된 값이 화면에 남는 결과는 만들지 않습니다.

### 4.4 오차 방향 원칙

이 설계 전체가 따르는 원칙입니다.

- **과잉 보고는 허용합니다.** 결과는 불필요한 재조회 한 번입니다.
- **누락은 허용하지 않습니다.** 결과는 화면에 남는 오래된 값입니다.
- 판정이 불확실하면 과잉 쪽으로 기울입니다.
- 판정이 불가능하면 누락으로 두지 않고 **미해결로 명시**합니다 (6.2 참고).

## 5. EntityResolver

호출 지점을 엔티티로 바꾸는 어댑터입니다. 코어는 등록된 Resolver 목록을 순회합니다.

```java
public interface EntityResolver {
    Optional<EntityAccess> resolve(CallSite callSite, ResolutionContext context);
}

public record EntityAccess(Set<String> entities, AccessKind kind) {}  // READ 또는 WRITE
```

1차 범위에 포함하는 구현체입니다.

| Resolver | 해석 근거 |
| --- | --- |
| `JpaRepositoryResolver` | Spring Data 리포지토리 타입 → 엔티티. `@Modifying` 및 메서드명 규약 반영 |
| `QuerydslResolver` | `QRun extends EntityPathBase<Run>` 의 제네릭 인자 |
| `JpqlResolver` | `@Query` 값에서 `FROM` / `UPDATE` / `DELETE` 대상 추출 |
| `NativeSqlResolver` | SQL 테이블명 → `@Table(name=)` 또는 Hibernate 네이밍 전략으로 역매핑 |
| `AnnotationResolver` | `@ReadsEntities` / `@WritesEntities` 선언 |

`QuerydslResolver` 는 DTO 프로젝션 Q클래스를 자동으로 제외합니다. 엔티티 Q클래스는
`EntityPathBase<T>` 를 상속하고 프로젝션 Q클래스는 `ConstructorExpression<T>` 를 상속하므로
상위 클래스로 구분됩니다.

`JpqlResolver` 는 `JOIN c.memberMap cm` 같은 연관 경로를 만나면 그 필드의 타입을 읽어
엔티티를 해석합니다. 해석에 실패한 연관 경로는 무시합니다. 리포지토리 제네릭으로 기본 엔티티는
이미 확보되므로 완전 누락은 아닙니다.

### 5.1 엔티티 연관 한 단계 확장

`Run` 을 읽으면 `@OneToMany RunPartner` 와 `@Embedded RunLocation` 도 함께 직렬화되어 응답에
실립니다. 따라서 `reads` 에는 `@OneToMany` / `@ManyToOne` / `@OneToOne` / `@Embedded` 를 따라
**한 단계** 확장을 적용합니다.

`writes` 에는 확장을 적용하지 않습니다. 쓰기 집합이 커지면 무효화 범위가 급격히 넓어집니다.

## 6. 출력 계약

### 6.1 x-entities

```yaml
paths:
  /v1/runs:
    post:
      operationId: createRun
      x-entities:
        reads:  [com.example.run.Run]
        writes:
          - com.example.run.Run
          - com.example.badge.BadgeRunContribution
          - com.example.badge.Badge
    get:
      operationId: searchRuns
      x-entities:
        reads:
          - com.example.run.Run
          - com.example.run.RunPartner
          - com.example.run.RunLocation
```

- 엔티티는 **FQCN** 으로 씁니다. 단순 이름은 패키지가 다른 동명 엔티티에서 충돌하며, 범용
  라이브러리가 그런 프로젝트에서 조용히 틀리면 안 됩니다. `entity-naming: SIMPLE` 설정으로
  단순 이름을 선택할 수 있습니다.
- 목록은 정렬해 출력합니다. 정렬하지 않으면 실행마다 순서가 바뀌어 스펙 디프가 흔들립니다.
- 빈 집합은 키를 생략합니다.
- 짝 목록(`x-invalidates`)은 노출하지 않습니다. 교집합 계산은 소비자가 합니다.

**비동기 쓰기를 구분하지 않습니다.** `@Async` 나 `@TransactionalEventListener(AFTER_COMMIT)` 를
지나서만 도달하는 엔티티도 `writes` 에 함께 넣습니다. 분석은 그 경로를 따라가지만 출력에서
구분하지 않습니다.

알려진 한계: 그런 엔티티는 HTTP 응답이 나간 뒤 다른 스레드에서 바뀝니다. 클라이언트가 응답
직후 재조회하면 아직 반영되지 않은 값을 읽을 수 있습니다. 소비자가 이 경우를 다루려면
지연 재조회나 재시도가 필요합니다.

### 6.2 미해결 표시

판정에 실패한 엔드포인트는 누락으로 두지 않고 명시합니다.

```yaml
  /v1/slots:
    post:
      x-entities:
        resolved: false
        unresolved:
          - "native SQL: SlotInstanceRepositoryImpl.upsert"
```

- `resolved` 는 `false` 일 때만 넣습니다. `true` 를 전부 넣으면 스펙만 커지고 정보가 없습니다.
- `unresolved` 는 사람이 읽을 수 있는 사유 목록입니다.
- 소비자는 `resolved: false` 를 보수적으로 다뤄야 합니다. 해당 GET 은 어떤 write 뒤에나
  무효화하고, 해당 write 는 전체를 무효화합니다.
- 미해결 엔드포인트 목록은 애플리케이션 로그에도 한 번 출력합니다.

이 장치가 있어야 자동 파생이 뚫린 자리를 개발자가 알 수 있습니다. MyBatis 로 조회 API 를
새로 만들었을 때 조용히 틀리는 대신 미해결로 드러납니다.

### 6.3 탈출구 어노테이션

자동 파생은 반드시 뚫립니다. 개발자가 직접 선언할 수 있어야 합니다.

```java
@ReadsEntities({Run.class, RunPartner.class})
@WritesEntities(Badge.class)
@InvalidationMapIgnore
```

- `Class<?>` 참조이므로 컴파일 시 검증되고 리네임에 안전합니다.
- 기본 동작은 **분석 결과에 추가**입니다. `override = true` 면 분석 결과를 대체합니다.
- `@InvalidationMapIgnore` 가 붙은 엔드포인트는 `x-entities` 를 붙이지 않습니다.
- 핸들러 인터페이스와 구현 클래스 어느 쪽에 붙어도 인식합니다.
  (`AnnotatedElementUtils` 의 find 의미론을 사용합니다.)

## 7. 설정

```yaml
invalidation-map:
  enabled: true                  # 기본값. springdoc 이 비활성이면 자동으로 동작하지 않음
  base-packages:                 # 미지정 시 @SpringBootApplication 의 패키지
    - com.example
  entity-naming: FQCN            # 또는 SIMPLE
  node-budget: 20000
  expand-read-associations: true
  fail-on-unresolved: false      # true 면 미해결이 하나라도 있을 때 부팅 실패
```

분석은 `enabled: true` 이고 springdoc 이 활성일 때만 실행합니다. Swagger 를 끄는 운영 환경에서는
부팅 비용이 발생하지 않습니다.

## 8. 테스트 전략

### 8.1 코어

가짜 `ProgramModel` 과 픽스처 클래스로 검증합니다. Spring 컨텍스트가 필요 없습니다.

케이스로 반드시 덮을 항목입니다.

- 리포지토리 직접 주입 / 포트-어댑터 경유
- `@Modifying` 이 메서드명 규약을 이기는지
- 더티체킹 변경자 탐지: 변경자, 비변경자, 전이적 변경자, 정적 팩터리 제외
- `@Transactional(readOnly = true)` 에서 변경자 쓰기가 억제되는지
- 이벤트 발행 → 리스너 (상위 타입 매칭 포함)
- 람다 안의 호출
- 순환 호출에서 종료하는지
- 노드 예산 초과 시 미해결로 표시하는지
- QueryDSL 엔티티 Q클래스는 잡고 프로젝션 Q클래스는 제외하는지
- 연관 한 단계 확장이 `reads` 에만 적용되는지

### 8.2 스타터

작은 픽스처 애플리케이션에 `@SpringBootTest` 를 붙여 `/v3/api-docs` 를 받아 `x-entities` 를
검증합니다. 어노테이션 탈출구와 `resolved: false` 출력을 함께 확인합니다.

### 8.3 실제 프로젝트 검증

pirl-spring 을 첫 소비자로 물려 검증합니다. `mavenLocal()` 또는 composite build 로 연결합니다.

검증 기준으로 쓸 pirl-spring 실측값입니다 (2026-09-07 기준).

| 항목 | 값 |
| --- | --- |
| 엔드포인트 | GET 93, write 118 (총 211) |
| 엔티티 | 53 |
| 컨트롤러 | 45 |
| Spring Data 리포지토리 → 엔티티 매핑 | 49 |
| QueryDSL Q클래스 참조 | 27종 |
| `@Query` (JPQL) | 54건, 그중 조인 5건, nativeQuery 0건 |
| 네이티브 SQL | 1건 (`SlotInstanceRepositoryImpl.upsert`, 쓰기) |
| `publishEvent` 호출 | 21곳 |
| MyBatis `@Mapper` / `SqlSession` / XML 매퍼 | 0 |
| JdbcTemplate / Redis / `@Cacheable` | 0 |

기대 결과입니다.

- `GET /v1/runs/{runId}` 의 `reads` 는 `Run`, `RunPartner`, `RunLocation` 을 포함합니다.
- `POST /v1/runs` 의 `writes` 는 `Run`, `BadgeRunContribution`, `CrewMonthlyRunRecord` 를 포함하고,
  이벤트를 지나 도달하는 배지 엔티티도 포함합니다.
- 미해결 엔드포인트는 `SlotInstanceRepositoryImpl.upsert` 를 거치는 write 를 제외하면 없어야
  합니다. 네이티브 SQL 역매핑이 성공하면 그것도 해결됩니다.

## 9. 검토했으나 채택하지 않은 방안

다음 회차에서 다시 논의될 때 근거를 잃지 않기 위해 기록합니다.

### 9.1 사람이 붙이는 어노테이션을 주 수단으로 쓰는 방안

엔드포인트 211개 규모에서 유지되지 않습니다. GET 을 하나 추가하면 그것을 읽는 write 전부를
훑어야 합니다. 어노테이션은 자동 파생이 뚫린 자리를 메우는 탈출구로만 남깁니다 (6.3).

### 9.2 짝(`x-invalidates`)을 스펙에 미리 계산해 넣는 방안

pirl-spring 에서 측정한 결과입니다.

| 산정 기준 | 교집합 짝 | write 당 GET 수 | 스펙 증가 (operationId 만) | 스펙 증가 (method+path+opId) |
| --- | --- | --- | --- | --- |
| 상한 (DI 그래프 집합) | 4,577 | 중앙값 49, 최대 71 | 84 KB | 402 KB |
| 하한 근사 (같은 도메인 제한) | 715 | 중앙값 5, 최대 13 | 13 KB | 63 KB |
| 엔티티 집합 방식 | — | — | 12 KB | — |

크기 자체는 결정적이지 않습니다. 이 스펙에는 DTO 214개의 스키마가 이미 실려 있습니다.
채택하지 않은 이유는 디프입니다 (2.1). 필요해지면 스펙과 분리된 별도 파일로 내보냅니다.

### 9.3 생성자 주입 그래프만으로 파생하는 방안

바이트코드 분석 없이 Spring 의 빈 의존 관계만 타는 방안입니다. 새 의존성이 필요 없고 코드가
작지만, DI 는 클래스 단위이므로 메서드 단위 구분이 원리적으로 불가능합니다.

pirl-spring 실측 결과 컨트롤러가 닿는 엔티티 수는 중앙값 4, 평균 5.5, 최대 21이었습니다.
`UserController` 는 전체 엔티티 53개 중 21개(40%)에 닿습니다. 이 컨트롤러의 write 는 도메인
모델의 40%를 무효화하게 되어, 소비자가 전체 캐시를 비우는 것과 실질적 차이가 없습니다.

도달 깊이를 제한해도 중간 지점이 없었습니다.

| 깊이 제한 | 중앙값 | 최대 | UserController | RunController |
| --- | --- | --- | --- | --- |
| 3 | 2 | 8 | 4 | 1 |
| 4 | 3 | 11 | 10 | 6 |
| 무제한 | 4 | 21 | 21 | 10 |

깊이 3에서 `RunController` 가 1개로 떨어지는 것은 과잉이 아니라 누락입니다. 호출 사슬이
컨트롤러 → 서비스 → 리포지토리 포트 → 구현체 → `JpaRepository` 로 네 홉이기 때문입니다.
누락은 4.4 원칙에 반합니다.

### 9.4 Annotation Processor(APT) + Trees API

APT 의 표준 API(`Element`, `Elements`, `Types`)는 선언만 봅니다. 메서드 본문을 볼 수 없습니다.
이 분석이 필요한 것은 정확히 메서드 본문입니다.

본문을 읽으려면 `com.sun.source.util.Trees`(`jdk.compiler` 모듈)를 사용해야 하고, Java 17 에서는
`--add-exports` 컴파일러 인자가 필요합니다. 그 뒤에도 호출문마다 심볼을 직접 해석하고 람다와
제네릭을 AST 수준에서 처리해야 합니다. 바이트코드에는 호출 대상이 이미 해석되어 있으므로,
같은 결과를 얻는 데 ASM 보다 일이 많고 JDK 업그레이드에 취약합니다.

APT 의 고유한 장점은 컴파일을 실패시킬 수 있다는 점입니다. 이 라이브러리의 산출물은 엔티티
집합 맵이므로 그 장점을 사용할 곳이 없습니다.

### 9.5 Hibernate 런타임 관측

`HandlerInterceptor` 로 요청을 식별하고 Hibernate 이벤트 리스너로 실제 읽고 쓴 엔티티를 모으는
방안입니다. 분석 코드가 거의 없고 정확도가 완벽합니다.

채택하지 않은 이유는 트래픽 의존성입니다. 호출되지 않은 엔드포인트는 맵에 없고, 타지 않은 조건
분기의 엔티티는 빠집니다. 누락 방향의 오차이므로 4.4 원칙에 반합니다.

## 10. 1차 범위에서 제외하는 것

- **MyBatis 어댑터.** `EntityResolver` 인터페이스는 그대로 두므로 이후 별도 모듈로 추가합니다.
  pirl-spring 에 MyBatis 실사용이 없어 1차에서는 검증할 소비자가 없습니다.
- **Gradle 플러그인(빌드 시 분석).** 코어의 입출력을 그대로 재사용해 이후 추가합니다.
- **프론트엔드 소비자.** `x-entities` 를 읽어 `invalidateQueries` 를 생성하는 코드 생성기는
  별도 프로젝트입니다.
- **배포 경로 선정.** 1차에서는 `mavenLocal()` 과 composite build 로 pirl-spring 에 물려
  검증합니다. 동작을 확인한 뒤 GitHub Packages / JitPack / Maven Central 중에서 고릅니다.
  동작하지 않는 것을 배포하는 인프라를 먼저 세우지 않습니다.
- **JPA 외 데이터 소스.** Redis, 외부 API 응답, `@Cacheable` 캐시는 엔티티 모델로 표현하지
  않습니다. 이런 경로만 사용하는 엔드포인트는 미해결로 표시됩니다.

## 11. 호환 범위

- Java 17 이상
- Spring Boot 3.x
- springdoc-openapi 2.x
- Spring Data JPA
- QueryDSL 5.x (선택. 없으면 해당 Resolver 를 등록하지 않습니다.)

ASM 버전은 읽을 클래스 파일 버전을 지원하는 것으로 맞춥니다.

## 12. 프로젝트 좌표

- 레포: `/Users/poku/projects/toktokhan/spring/spring-invalidation-map`
- group: `dev.toktokhan.invalidation`
- 모듈: `invalidation-map-core`, `invalidation-map-spring-boot-starter`

이름에 `hints` 를 쓰지 않습니다. JPA 에 `jakarta.persistence.QueryHint` 와 `@QueryHints` 가 이미
있어, JPA 맥락에서 "entity hints" 는 쿼리 힌트로 읽힙니다. 같은 이유로 `tags` 도 쓰지 않습니다.
OpenAPI 의 `tags` 는 오퍼레이션 묶음을 뜻합니다.
