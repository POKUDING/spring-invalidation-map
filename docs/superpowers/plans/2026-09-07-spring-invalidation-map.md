# spring-invalidation-map Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Spring Boot 엔드포인트마다 읽고 쓰는 JPA 엔티티 집합을 자동으로 계산해 OpenAPI 스펙의 `x-entities` 확장으로 노출하는 범용 라이브러리를 만듭니다.

**Architecture:** `invalidation-map-core` 는 Spring 을 의존하지 않고 ASM 으로 바이트코드의 호출 사슬만 분석합니다. 환경 사실(엔드포인트 목록, 리포지토리→엔티티, 인터페이스→구현체, 엔티티 목록)은 `ProgramModel` 인터페이스로 받습니다. `invalidation-map-spring-boot-starter` 가 `ProgramModel` 을 Spring 메타데이터로 구현하고 springdoc `OperationCustomizer` 로 결과를 주입합니다.

**Tech Stack:** Gradle 8.10.2 (멀티프로젝트), ASM 9.7.1, JUnit 5, AssertJ. 소비자 환경은 **Spring Boot 4.x 를 주 대상으로, 3.x 도 함께 지원**하고 **JVM 17 과 21 모두**에서 동작합니다 (검증 조합: Boot 4.0.6 + springdoc 3.0.1, Boot 3.3.5 + springdoc 2.5.0).

**Spec:** `docs/superpowers/specs/2026-09-07-spring-invalidation-map-design.md`

## Global Constraints

- **컴파일 타깃은 Java 17 (`options.release = 17`) 로 고정합니다.** 툴체인을 쓰지 않습니다. 이유는 세 가지입니다. ① Spring Boot 4 의 기준선이 Java 17 입니다 (Spring Data 4.0.5 의 클래스 파일이 major 61 인 것으로 확인). ② Java 17 바이트코드는 JVM 17 과 21 에서 모두 돕니다. ③ 이 장비의 기본 JVM 이 JDK 21 이라 툴체인으로 17 을 요구하면 JDK 17 설치나 자동 다운로드가 필요해집니다.
- **`release = 17` 이므로 Java 18 이상에서 추가된 API 를 쓰면 컴파일이 실패합니다.** 특히 `List.getFirst()` / `SequencedCollection` (Java 21), `Stream.toList()` 는 17 에 있으나 `getFirst` 는 없습니다. `list.get(0)` 을 씁니다.
- **Spring Boot 4.x 를 주 대상으로 하고 3.x 도 지원합니다.** 두 계열에서 우리가 쓰는 API 가 동일함을 확인했습니다.
  - `org.springdoc.core.customizers.OperationCustomizer.customize(Operation, HandlerMethod)` — springdoc 2.5.0 과 3.0.1 시그니처 동일
  - `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — Boot 4.0.6 도 사용
  - `Repositories.iterator()`, `Repositories.getRepositoryInformationFor(Class)`, `RepositoryMetadata.getRepositoryInterface()`, `RepositoryMetadata.getFragments()`, `RepositoryFragment.getSignatureContributor()`, `RepositoryFragment.getImplementation()` — Spring Data 3.3.5 와 4.0.5 모두 존재
  - `org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping` — Framework 6.1.14 와 7.0.7 모두 같은 패키지
- **Boot 4 에만 있는 API 를 쓰지 않습니다.** `RepositoryFragment.getImplementationClass()` 는 4.x 에만 있으므로, 양쪽에 다 있는 `getImplementation()` 에서 `getClass()` 를 얻습니다.
- **클래스 파일을 읽다 실패해도 애플리케이션을 깨뜨리지 않습니다.** 이 라이브러리는 런타임에 도므로, ASM 이 모르는 클래스 파일 버전을 만나 예외를 던지면 `/v3/api-docs` 가 통째로 실패합니다. 클래스 단위 읽기를 예외 처리로 감싸고, 실패는 `unresolved` 사유로 남기고 계속 진행합니다. ASM 9.7.1 은 Java 22(major 66) 까지 읽으므로 Java 21(major 65) 로 컴파일한 소비자는 문제가 없습니다. 소비자가 더 새 JDK 로 옮기면 ASM 버전을 올려야 하고, 그때까지는 미해결로 드러납니다.
- `invalidation-map-core` 의 **main 소스셋은 `org.ow2.asm:asm` 외의 의존성을 갖지 않습니다.** Spring, Jakarta, QueryDSL 아티팩트를 `implementation` 이나 `api` 로 추가하지 않습니다. 테스트 소스셋에는 추가해도 됩니다.
- 의존 방향은 `starter → core` 단방향입니다. 코어가 스타터를 참조하지 않습니다.
- **코어 내부의 클래스 이름은 전부 ASM internal name** 입니다 (`com/example/run/Run`). FQCN 변환은 스타터의 출력 단계에서만 합니다.
- **커밋 분리: 구현 커밋을 먼저, 테스트 커밋을 나중에 만듭니다.** 한 커밋에 구현과 테스트를 섞지 않습니다. 각 태스크의 커밋 단계는 `git add` 로 구현 파일만 담아 커밋하고, 이어서 테스트 파일만 담아 커밋합니다.
- 오차 방향 원칙: **과잉 보고는 허용하고 누락은 허용하지 않습니다.** 판정이 불확실하면 과잉 쪽으로 기울이고, 판정이 불가능하면 누락으로 두지 않고 `unresolved` 에 사유를 남깁니다.
- 출력하는 모든 집합은 정렬합니다. 정렬하지 않으면 실행마다 순서가 바뀌어 스펙 디프가 흔들립니다.
- **`Set.copyOf` 와 `Map.copyOf` 를 쓰지 않습니다.** JDK 불변 컬렉션의 순회 순서는 JVM 기동마다 달라지는 값에 좌우되어, 순서가 결과에 드러나는 자리에서 실행마다 다른 답을 냅니다. 대신 `Collections.unmodifiableSet(new LinkedHashSet<>(...))` 와 `Collections.unmodifiableMap(new LinkedHashMap<>(...))` 를 씁니다. `List.copyOf` 는 `List` 가 순서를 보존하므로 그대로 씁니다. 이 규칙에 따라 각 태스크의 코드 블록에는 `java.util.Collections`, `java.util.LinkedHashSet`, `java.util.LinkedHashMap` import 가 필요합니다 — 코드 블록의 import 목록에 없으면 추가하십시오.
- 문서(README, 주석, 커밋 본문)는 '~습니다'체로 씁니다.
- 비유를 쓰지 않습니다. 실제로 일어나는 동작을 그대로 씁니다.
- 커밋 메시지 마지막 줄: `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`

---

## 설계 문서와 달라진 점

계획을 쓰면서 설계 문서보다 나은 구조를 찾은 부분입니다. 설계 문서를 고치지 않고 여기에 사유를 남깁니다.

1. **`JpqlResolver` 와 `NativeSqlResolver` 를 `EntityResolver` 구현체가 아니라 도우미로 바꿉니다.**
   설계 문서 5절은 둘을 `EntityResolver` 목록에 넣었습니다. 그런데 JPQL 문자열은 `@Query` 어노테이션(리포지토리 메서드) 또는 `EntityManager.createQuery` 인자에서만 나옵니다. 즉 독립된 호출 지점이 아니라 다른 Resolver 안에서 쓰이는 문자열 해석기입니다. `JpqlEntityExtractor` 와 `SqlTableExtractor` 라는 정적 도우미로 두고, `JpaRepositoryResolver` 와 `EntityManagerResolver` 가 호출합니다.

2. **`AnnotationResolver` 를 Resolver 체인에서 빼고 분석기 단계로 올립니다.**
   `@ReadsEntities` / `@WritesEntities` 는 호출 지점이 아니라 **엔드포인트 핸들러 메서드**에 붙습니다. 호출 지점 Resolver 체인에 두면 자리가 맞지 않습니다. `InvalidationMapAnalyzer` 가 엔드포인트별로 직접 읽습니다.

3. **`DirtyCheckResolver` 를 Resolver 체인에 추가합니다.**
   설계 문서 4.2절 5번(더티체킹 변경자)은 Resolver 목록에 없었습니다. 실제로는 "엔티티 변경자 메서드 호출"이라는 호출 지점 판정이므로 Resolver 로 두는 것이 맞습니다.

3-1. 결과 Resolver 체인은 설계 문서 4.2절의 우선순위 그대로 네 개입니다.
   `JpaRepositoryResolver` → `EntityManagerResolver` → `QuerydslResolver` → `DirtyCheckResolver`.

4. **`InvalidationMap` 을 `MethodRef` 로 키를 잡습니다.**
   설계 문서 3.1절이 이미 "스타터는 `HandlerMethod` 의 (선언 클래스, 메서드명, 디스크립터) 로 찾습니다"라고 정했으므로, 맵의 키를 그 세 값으로 만든 `MethodRef` 로 둡니다. `Endpoint` 의 `httpMethod` 와 `path` 는 진단 출력용으로만 남깁니다. 이렇게 하면 한 핸들러에 경로가 여러 개 붙은 매핑에서 키가 갈라지는 문제가 없습니다.

5. **인터페이스 → 구현체 해석에 Spring Data 프래그먼트 경로를 추가합니다.**
   pirl-spring 에서 리포지토리 구현 패턴이 두 가지임을 확인했습니다.
   - `@Repository` 가 붙은 어댑터 빈 30개 → 빈 팩토리로 찾을 수 있습니다.
   - Spring Data 프래그먼트 1개(`SlotInstanceRepositoryCustom` + `SlotInstanceRepositoryImpl`) → **빈이 아닙니다.** Spring Data 가 내부에서 만들므로 빈 팩토리로는 찾을 수 없고, `RepositoryInformation.getFragments()` 의 `RepositoryFragment.getImplementation()` 으로만 찾을 수 있습니다.

   설계 문서 3.1절의 `implementationsOf` 는 이 두 경로를 모두 써야 합니다. 프래그먼트 경로가 없으면 `SlotInstanceRepositoryImpl` 의 네이티브 SQL 에 도달하지 못하고, 그것이 조용한 누락이 되어 4.4 원칙에 반합니다.

6. **`ProgramModel` 에 `eventListeners()` 를 추가합니다 — 이음새가 다섯이 아니라 여섯 개입니다.**
   설계 문서 3.1절은 이음새를 다섯 가지로 정했습니다. 그런데 `publishEvent` 호출 지점에서
   "이 이벤트 타입을 듣는 메서드가 무엇인가"에 답하려면 리스너를 열거해야 하고, 다섯 메서드
   중에는 클래스를 열거하는 것이 없습니다. `Set<MethodRef> eventListeners()` 를 추가합니다.

   런타임 구현은 빈 타입을 훑어 `@EventListener` / `@TransactionalEventListener` 가 붙은
   메서드를 모으면 됩니다. Spring 이 이미 아는 사실입니다. 이 메서드가 없으면 이벤트를 지나
   도달하는 엔티티가 통째로 누락되어 4.4 원칙에 반합니다.

7. **QueryDSL 리졸버를 조건부로 등록하지 않고 항상 등록합니다.**
   설계 문서 11절은 "QueryDSL 이 없으면 해당 Resolver 를 등록하지 않습니다"라고 적었습니다.
   실제로는 조건부 등록이 필요 없습니다. `QuerydslResolver` 는 QueryDSL 클래스를 로드하지 않고
   `EntityPathBase` 라는 internal name 문자열과 바이트코드만 비교하므로, QueryDSL 이 없는
   프로젝트에서는 아무것도 매칭되지 않는 무동작이 됩니다. 조건 분기를 없애는 편이 단순합니다.

8. **분석 시점을 첫 스펙 요청으로 늦춥니다.**
   설계 문서 7절은 "springdoc 이 활성일 때만 실행"이라고만 정했습니다. 더 나은 방법이 있습니다. springdoc 은 `/v3/api-docs` 를 처음 요청받을 때 스펙을 만듭니다. 그 시점에 지연 분석하면 부팅 시간이 전혀 늘지 않고, Swagger 를 열지 않는 운영 환경에서는 분석 자체가 돌지 않습니다.
   예외는 `fail-on-unresolved: true` 입니다. 부팅을 실패시키려면 부팅 중에 분석해야 하므로, 이 경우에만 `ApplicationReadyEvent` 에서 즉시 분석합니다.

---

## File Structure

### invalidation-map-core

main 소스셋. ASM 만 의존합니다.

```
src/main/java/dev/toktokhan/invalidation/core/
├─ MethodRef.java                 (owner, name, descriptor) — 모든 메서드 식별의 단위
├─ MethodRefs.java                리플렉션 -> MethodRef, internal name <-> FQCN 변환
├─ Endpoint.java                  (httpMethod, path, handler:MethodRef) — 진단용 표시 정보 포함
├─ ProgramModel.java              코어가 환경에 묻는 다섯 가지
├─ AccessKind.java                READ / WRITE
├─ EntityAccess.java              (entities:Set<String>, kind:AccessKind)
├─ EndpointEntities.java          (reads, writes, unresolved) — 한 엔드포인트의 결과
├─ InvalidationMap.java           MethodRef -> EndpointEntities
├─ AnalyzerOptions.java           (basePackages, nodeBudget, expandReadAssociations)
├─ InvalidationMapAnalyzer.java   조립 지점. 엔드포인트를 순회해 InvalidationMap 을 만든다
├─ annotation/
│   ├─ ReadsEntities.java
│   ├─ WritesEntities.java
│   └─ InvalidationMapIgnore.java
├─ scan/                          바이트코드를 값으로 바꾸는 층. 코어에서 ASM 을 쓰는 유일한 층
│   ├─ ClassFactsReader.java      ASM ClassVisitor
│   ├─ ClassFacts.java
│   ├─ MethodFacts.java
│   ├─ FieldFacts.java
│   ├─ AnnotationValues.java
│   └─ SignatureTypeArguments.java 제네릭 시그니처의 타입 인자 추출
├─ index/                         한 번 계산해 두고 조회하는 층
│   ├─ ClassRepository.java       ClassFacts 캐시 + 하위 타입 판정
│   ├─ EntityIndex.java           엔티티, 변경자, 연관, 테이블명
│   ├─ RepositoryIndex.java       리포지토리 -> 엔티티, 메서드 -> AccessKind
│   └─ ListenerIndex.java         이벤트 타입 -> 리스너 메서드
├─ walk/                          호출 사슬을 따라가는 층
│   ├─ CallGraphWalker.java
│   ├─ WalkVisitor.java
│   ├─ WalkState.java
│   └─ WalkResult.java
└─ resolve/                       호출 지점을 엔티티 접근으로 바꾸는 층
    ├─ EntityResolver.java
    ├─ ResolutionContext.java
    ├─ JpaRepositoryResolver.java
    ├─ EntityManagerResolver.java
    ├─ QuerydslResolver.java
    ├─ DirtyCheckResolver.java
    ├─ JpqlEntityExtractor.java   JPQL 문자열 -> 엔티티 (정적 도우미)
    └─ SqlTableExtractor.java     SQL 문자열 -> 테이블명 (정적 도우미)
```

test 소스셋. 픽스처는 실제로 컴파일되는 자바 클래스이며, 테스트가 그 `.class` 바이트를 읽습니다.

```
src/test/java/dev/toktokhan/invalidation/core/
├─ support/
│   ├─ FakeProgramModel.java      테스트 클래스패스에서 바이트를 읽는 ProgramModel
│   └─ Bytes.java                 Class<?> -> byte[] 도우미
└─ fixture/                       분석 대상 픽스처. 패키지를 용도별로 나눈다
    ├─ entity/                    @Entity 픽스처 (변경자, 연관, @Table)
    ├─ repo/                      리포지토리 픽스처 (@Modifying, @Query, 포트/어댑터)
    ├─ service/                   호출 사슬 픽스처 (람다, 순환, @Transactional)
    ├─ event/                     이벤트 발행/수신 픽스처
    └─ web/                       핸들러 픽스처 (@ReadsEntities 등)
```

### invalidation-map-spring-boot-starter

```
src/main/java/dev/toktokhan/invalidation/springboot/
├─ InvalidationMapProperties.java
├─ EntityNaming.java                        FQCN / SIMPLE
├─ SpringProgramModel.java                  ProgramModel 을 Spring 메타데이터로 구현
├─ InvalidationMapOperationCustomizer.java  x-entities 주입 + 지연 분석
└─ InvalidationMapAutoConfiguration.java

src/main/resources/META-INF/spring/
└─ org.springframework.boot.autoconfigure.AutoConfiguration.imports

src/test/java/dev/toktokhan/invalidation/springboot/
├─ InvalidationMapIntegrationTest.java      @SpringBootTest + /v3/api-docs 검증
└─ app/                                     픽스처 애플리케이션 (엔티티/리포지토리/컨트롤러)
```

---
## Task 1: Gradle 스캐폴드와 바이트코드 읽기 층

**Files:**
- Create: `settings.gradle`, `build.gradle`, `invalidation-map-core/build.gradle`, `.gitignore`
- Create: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties` (pirl-spring 에서 복사)
- Create: `invalidation-map-core/src/main/java/dev/toktokhan/invalidation/core/MethodRef.java`
- Create: `invalidation-map-core/src/main/java/dev/toktokhan/invalidation/core/scan/AnnotationValues.java`
- Create: `invalidation-map-core/src/main/java/dev/toktokhan/invalidation/core/scan/FieldFacts.java`
- Create: `invalidation-map-core/src/main/java/dev/toktokhan/invalidation/core/scan/MethodFacts.java`
- Create: `invalidation-map-core/src/main/java/dev/toktokhan/invalidation/core/scan/ClassFacts.java`
- Create: `invalidation-map-core/src/main/java/dev/toktokhan/invalidation/core/scan/ClassFactsReader.java`
- Test: `invalidation-map-core/src/test/java/dev/toktokhan/invalidation/core/support/Bytes.java`
- Test: `invalidation-map-core/src/test/java/dev/toktokhan/invalidation/core/fixture/scan/ScanSample.java`
- Test: `invalidation-map-core/src/test/java/dev/toktokhan/invalidation/core/scan/ClassFactsReaderTest.java`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces:
  - `record MethodRef(String owner, String name, String descriptor)` — owner 는 ASM internal name
  - `record AnnotationValues(Map<String, Object> values)` — `Optional<String> string(String)`, `boolean bool(String, boolean)`, `List<String> strings(String)`
  - `record FieldFacts(String name, String descriptor, String signature, Map<String, AnnotationValues> annotations)`
  - `record MethodFacts(MethodRef ref, int access, List<MethodRef> calls, List<String> newTypes, List<String> stringConstants, Set<String> writtenOwnFields, Map<String, AnnotationValues> annotations, List<MethodRef> lambdaBodies)`
  - `record ClassFacts(String internalName, String superName, List<String> interfaces, String signature, Map<String, AnnotationValues> annotations, List<FieldFacts> fields, List<MethodFacts> methods)`
  - `static ClassFacts ClassFactsReader.read(byte[] classBytes)`
  - 테스트 도우미 `static byte[] Bytes.of(Class<?> type)`

**참고 — 이 저장소의 테스트 명명 규약:** 메서드명은 영어로 `subject_condition_expectation` 형태로 씁니다 (예: `read_methodWritesOwnField_reportsFieldName`). 범용 라이브러리라 한국어 메서드명과 `@DisplayName` 은 쓰지 않습니다.

**참고 — 어노테이션 값 표현:** ASM 은 어노테이션의 클래스 값을 `org.objectweb.asm.Type` 으로 넘깁니다. `AnnotationValues` 에 담을 때 `Type.getInternalName()` 으로 바꿔 문자열로 저장합니다. 코어 전체가 internal name 을 쓰기 때문입니다. 따라서 `strings("value")` 는 문자열 배열과 클래스 배열을 모두 처리합니다.

**참고 — 중첩 어노테이션은 읽지 않습니다.** 이 라이브러리가 필요한 어노테이션(`@Transactional`, `@Query`, `@Modifying`, `@Table`, `@Entity`, `@OneToMany`, `@ManyToOne`, `@OneToOne`, `@Embedded`, `@EventListener`, `@TransactionalEventListener`, `@Async`, `@ReadsEntities`, `@WritesEntities`, `@InvalidationMapIgnore`)에는 중첩 어노테이션이 없습니다.

- [ ] **Step 1: Gradle 스캐폴드를 만들고 빈 모듈이 컴파일되는지 확인한다**

`gradle` 명령이 PATH 에 없으므로 래퍼를 pirl-spring 에서 복사합니다. 두 프로젝트 모두 Gradle 8.10.2 를 씁니다.

```bash
cd /Users/poku/projects/toktokhan/spring/spring-invalidation-map
SRC=/Users/poku/projects/toktokhan/spring/pirl-spring
cp "$SRC/gradlew" "$SRC/gradlew.bat" .
mkdir -p gradle/wrapper
cp "$SRC/gradle/wrapper/gradle-wrapper.jar" "$SRC/gradle/wrapper/gradle-wrapper.properties" gradle/wrapper/
chmod +x gradlew
mkdir -p invalidation-map-core/src/main/java invalidation-map-core/src/test/java
```

`settings.gradle`:

```groovy
rootProject.name = 'spring-invalidation-map'

include 'invalidation-map-core'
include 'invalidation-map-spring-boot-starter'
```

`build.gradle` (루트):

```groovy
subprojects {
    apply plugin: 'java-library'

    group = 'dev.toktokhan.invalidation'
    version = '0.1.0-SNAPSHOT'

    repositories {
        mavenCentral()
    }

    // 툴체인 대신 release 를 씁니다.
    // Spring Boot 4 의 기준선이 Java 17 이고, Java 17 바이트코드는 JVM 17 과 21 에서 모두 돕니다.
    // 이 장비의 기본 JVM 은 JDK 21 이므로 툴체인으로 17 을 요구하면 JDK 17 설치가 필요해집니다.
    // release = 17 이면 JDK 21 이 Java 17 바이트코드(클래스 파일 버전 61)를 만들고,
    // Java 18 이상에서 추가된 API 를 실수로 쓰면 컴파일 단계에서 걸립니다.
    tasks.withType(JavaCompile).configureEach {
        options.release = 17
        options.encoding = 'UTF-8'
    }

    dependencies {
        testImplementation platform('org.junit:junit-bom:5.10.3')
        testImplementation 'org.junit.jupiter:junit-jupiter'
        testImplementation 'org.assertj:assertj-core:3.26.3'
        testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
    }

    tasks.named('test') {
        useJUnitPlatform()
        testLogging {
            events 'failed'
            exceptionFormat 'full'
        }
    }
}
```

`invalidation-map-core/build.gradle`:

```groovy
dependencies {
    // 전역 제약: main 소스셋은 ASM 외의 의존성을 갖지 않습니다.
    // ASM 타입이 공개 API 시그니처에 나타나지 않으므로 implementation 으로 둡니다.
    implementation 'org.ow2.asm:asm:9.7.1'

    // 픽스처를 실제 어노테이션으로 꾸미기 위한 테스트 전용 의존성입니다.
    // 코어는 어노테이션을 디스크립터 문자열로만 다루므로 main 소스셋에는 이 의존성이 없습니다.
    // 어노테이션 디스크립터는 Boot 3 계열과 4 계열이 동일하므로 한쪽 버전으로 픽스처를 꾸며도
    // 양쪽 소비자에게 그대로 적용됩니다.
    testImplementation 'jakarta.persistence:jakarta.persistence-api:3.2.0'
    testImplementation 'org.springframework.data:spring-data-jpa:4.0.5'
    testImplementation 'org.springframework:spring-tx:7.0.7'
    testImplementation 'org.springframework:spring-context:7.0.7'
    testImplementation 'com.querydsl:querydsl-core:5.0.0'
}
```

`.gitignore`:

```
.gradle/
build/
*.class
.idea/
*.iml
.DS_Store
```

빈 모듈이 컴파일되는지 확인합니다.

```bash
./gradlew :invalidation-map-core:compileJava --console=plain
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: 값 타입 다섯 개를 작성한다**

행동이 없는 순수 데이터입니다. 검증 대상은 이것들을 채우는 `ClassFactsReader` 이므로 값 타입을 먼저 만들고 테스트는 다음 단계에서 씁니다.

`MethodRef.java`:

```java
package dev.toktokhan.invalidation.core;

/**
 * 메서드 하나를 가리키는 식별자입니다.
 *
 * @param owner      선언 클래스의 ASM internal name (예: {@code com/example/run/Run})
 * @param name       메서드명
 * @param descriptor JVM 메서드 디스크립터 (예: {@code (Ljava/lang/String;)V})
 */
public record MethodRef(String owner, String name, String descriptor) {

    public String simpleOwnerName() {
        int slash = owner.lastIndexOf('/');
        return slash < 0 ? owner : owner.substring(slash + 1);
    }

    @Override
    public String toString() {
        return owner + "." + name + descriptor;
    }
}
```

`scan/AnnotationValues.java`:

```java
package dev.toktokhan.invalidation.core.scan;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 어노테이션 하나의 속성 값입니다.
 *
 * <p>클래스 값은 ASM {@code Type} 이 아니라 internal name 문자열로 저장됩니다.
 * 코어 전체가 internal name 을 사용하기 때문입니다.
 */
public record AnnotationValues(Map<String, Object> values) {

    public static final AnnotationValues EMPTY = new AnnotationValues(Map.of());

    public Optional<String> string(String name) {
        Object value = values.get(name);
        return value instanceof String text ? Optional.of(text) : Optional.empty();
    }

    public boolean bool(String name, boolean defaultValue) {
        Object value = values.get(name);
        return value instanceof Boolean flag ? flag : defaultValue;
    }

    /**
     * 문자열 배열과 클래스 배열을 모두 문자열 목록으로 돌려줍니다.
     * 값이 배열이 아닌 단일 값이면 한 원소 목록으로 돌려줍니다.
     */
    public List<String> strings(String name) {
        Object value = values.get(name);
        if (value instanceof List<?> list) {
            return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
        }
        return value instanceof String text ? List.of(text) : List.of();
    }
}
```

`scan/FieldFacts.java`:

```java
package dev.toktokhan.invalidation.core.scan;

import java.util.Map;

/**
 * @param descriptor 필드 타입 디스크립터 (예: {@code Ljava/util/List;})
 * @param signature  제네릭 시그니처. 없으면 null (예: {@code Ljava/util/List<Lcom/example/RunPartner;>;})
 */
public record FieldFacts(
    String name,
    String descriptor,
    String signature,
    Map<String, AnnotationValues> annotations
) {
    public boolean hasAnnotation(String annotationDescriptor) {
        return annotations.containsKey(annotationDescriptor);
    }
}
```

`scan/MethodFacts.java`:

```java
package dev.toktokhan.invalidation.core.scan;

import dev.toktokhan.invalidation.core.MethodRef;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;

/**
 * 메서드 본문에서 뽑아낸 사실입니다.
 *
 * @param calls            호출한 메서드. 바이트코드 순서를 유지합니다
 * @param newTypes         {@code NEW} 로 생성한 타입의 internal name
 * @param stringConstants  {@code LDC} 로 실린 문자열 상수
 * @param writtenOwnFields {@code PUTFIELD} 로 값을 쓴 자기 클래스 필드명
 * @param lambdaBodies     {@code INVOKEDYNAMIC} 부트스트랩 인자가 가리키는 메서드
 */
public record MethodFacts(
    MethodRef ref,
    int access,
    List<MethodRef> calls,
    List<String> newTypes,
    List<String> stringConstants,
    Set<String> writtenOwnFields,
    Map<String, AnnotationValues> annotations,
    List<MethodRef> lambdaBodies
) {
    public boolean isStatic() {
        return (access & Opcodes.ACC_STATIC) != 0;
    }

    public boolean isConstructor() {
        return ref.name().equals("<init>") || ref.name().equals("<clinit>");
    }

    public boolean hasAnnotation(String annotationDescriptor) {
        return annotations.containsKey(annotationDescriptor);
    }

    public AnnotationValues annotation(String annotationDescriptor) {
        return annotations.getOrDefault(annotationDescriptor, AnnotationValues.EMPTY);
    }
}
```

`scan/ClassFacts.java`:

```java
package dev.toktokhan.invalidation.core.scan;

import dev.toktokhan.invalidation.core.MethodRef;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @param signature 클래스 제네릭 시그니처. 없으면 null
 *                  (예: {@code Lcom/querydsl/core/types/dsl/EntityPathBase<Lcom/example/Run;>;})
 */
public record ClassFacts(
    String internalName,
    String superName,
    List<String> interfaces,
    String signature,
    Map<String, AnnotationValues> annotations,
    List<FieldFacts> fields,
    List<MethodFacts> methods
) {
    public boolean hasAnnotation(String annotationDescriptor) {
        return annotations.containsKey(annotationDescriptor);
    }

    public AnnotationValues annotation(String annotationDescriptor) {
        return annotations.getOrDefault(annotationDescriptor, AnnotationValues.EMPTY);
    }

    public Optional<MethodFacts> method(MethodRef ref) {
        return methods.stream().filter(candidate -> candidate.ref().equals(ref)).findFirst();
    }

    /** 오버로드가 있으면 첫 번째를 돌려줍니다. 테스트와 진단용입니다. */
    public Optional<MethodFacts> methodNamed(String name) {
        return methods.stream().filter(candidate -> candidate.ref().name().equals(name)).findFirst();
    }
}
```

- [ ] **Step 3: 실패하는 테스트를 작성한다**

`src/test/java/dev/toktokhan/invalidation/core/support/Bytes.java`:

```java
package dev.toktokhan.invalidation.core.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** 테스트 클래스패스에서 컴파일된 클래스 파일의 바이트를 읽습니다. */
public final class Bytes {

    private Bytes() {
    }

    public static byte[] of(Class<?> type) {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("클래스 파일을 찾을 수 없습니다: " + resource);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String internalName(Class<?> type) {
        return type.getName().replace('.', '/');
    }
}
```

`src/test/java/dev/toktokhan/invalidation/core/fixture/scan/ScanSample.java`:

```java
package dev.toktokhan.invalidation.core.fixture.scan;

import java.util.function.Supplier;

/** ClassFactsReader 가 뽑아야 하는 바이트코드 사실을 모두 담은 픽스처입니다. */
public class ScanSample {

    private String name;

    /** PUTFIELD 로 자기 필드에 씁니다. */
    public void rename(String next) {
        this.name = next;
    }

    /** LDC 문자열 상수와 메서드 호출을 담습니다. */
    public String describe() {
        String prefix = "sample";
        return prefix.concat(name);
    }

    /** NEW 명령을 담습니다. */
    public Object create() {
        return new StringBuilder();
    }

    /** INVOKEDYNAMIC 으로 합성 람다 메서드를 만듭니다. */
    public Supplier<String> lazy() {
        return () -> name;
    }

    /** 런타임 유지 어노테이션이 값과 함께 붙습니다. */
    @Deprecated(since = "1.0")
    public void legacy() {
    }
}
```

`src/test/java/dev/toktokhan/invalidation/core/scan/ClassFactsReaderTest.java`:

```java
package dev.toktokhan.invalidation.core.scan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.fixture.scan.ScanSample;
import dev.toktokhan.invalidation.core.support.Bytes;
import org.junit.jupiter.api.Test;

class ClassFactsReaderTest {

    private final ClassFacts facts = ClassFactsReader.read(Bytes.of(ScanSample.class));

    @Test
    void read_anyClass_reportsInternalNameAndSuperName() {
        assertThat(facts.internalName()).isEqualTo(Bytes.internalName(ScanSample.class));
        assertThat(facts.superName()).isEqualTo("java/lang/Object");
    }

    @Test
    void read_methodWritesOwnField_reportsFieldName() {
        assertThat(method("rename").writtenOwnFields()).containsExactly("name");
    }

    @Test
    void read_methodDoesNotWriteOwnField_reportsNoField() {
        assertThat(method("describe").writtenOwnFields()).isEmpty();
    }

    @Test
    void read_methodCallsAnotherMethod_reportsCallTarget() {
        assertThat(method("describe").calls()).contains(
            new MethodRef("java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;"));
    }

    @Test
    void read_methodLoadsStringConstant_reportsConstant() {
        assertThat(method("describe").stringConstants()).contains("sample");
    }

    @Test
    void read_methodInstantiatesType_reportsNewType() {
        assertThat(method("create").newTypes()).contains("java/lang/StringBuilder");
    }

    @Test
    void read_methodCreatesLambda_reportsSyntheticBody() {
        assertThat(method("lazy").lambdaBodies())
            .anySatisfy(ref -> assertThat(ref.name()).startsWith("lambda$lazy$"));
    }

    @Test
    void read_methodHasRuntimeAnnotation_reportsAnnotationValue() {
        assertThat(method("legacy").annotation("Ljava/lang/Deprecated;").string("since"))
            .contains("1.0");
    }

    @Test
    void read_classHasFields_reportsFieldDescriptor() {
        assertThat(facts.fields())
            .anySatisfy(field -> {
                assertThat(field.name()).isEqualTo("name");
                assertThat(field.descriptor()).isEqualTo("Ljava/lang/String;");
            });
    }

    private MethodFacts method(String name) {
        return facts.methodNamed(name).orElseThrow(
            () -> new AssertionError("픽스처에 메서드가 없습니다: " + name));
    }
}
```

- [ ] **Step 4: 테스트를 실행해 실패를 확인한다**

```bash
./gradlew :invalidation-map-core:test --console=plain
```

Expected: 컴파일 실패. `cannot find symbol: class ClassFactsReader`

- [ ] **Step 5: ClassFactsReader 를 구현한다**

`scan/ClassFactsReader.java`:

```java
package dev.toktokhan.invalidation.core.scan;

import dev.toktokhan.invalidation.core.MethodRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * 클래스 파일 하나를 {@link ClassFacts} 로 바꿉니다.
 *
 * <p>{@code scan} 패키지가 코어에서 ASM 을 쓰는 유일한 층입니다. {@code index}, {@code walk},
 * {@code resolve} 는 {@link ClassFacts} 만 봅니다.
 */
public final class ClassFactsReader {

    private static final int API = Opcodes.ASM9;

    private ClassFactsReader() {
    }

    public static ClassFacts read(byte[] classBytes) {
        ClassCollector collector = new ClassCollector();
        new ClassReader(classBytes).accept(collector, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return collector.build();
    }

    private static final class ClassCollector extends ClassVisitor {

        private String internalName;
        private String superName;
        private String signature;
        private List<String> interfaces = List.of();
        private final Map<String, AnnotationValues> annotations = new LinkedHashMap<>();
        private final List<FieldFacts> fields = new ArrayList<>();
        private final List<MethodFacts> methods = new ArrayList<>();

        ClassCollector() {
            super(API);
        }

        @Override
        public void visit(int version, int access, String name, String classSignature,
            String superClassName, String[] interfaceNames) {
            this.internalName = name;
            this.superName = superClassName;
            this.signature = classSignature;
            this.interfaces = interfaceNames == null ? List.of() : List.of(interfaceNames);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return new ValueCollector(descriptor, annotations::put);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
            String fieldSignature, Object value) {
            Map<String, AnnotationValues> fieldAnnotations = new LinkedHashMap<>();
            fields.add(new FieldFacts(name, descriptor, fieldSignature, fieldAnnotations));
            return new FieldVisitor(API) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean isVisible) {
                    return new ValueCollector(annotationDescriptor, fieldAnnotations::put);
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
            String methodSignature, String[] exceptions) {
            return new MethodCollector(internalName, access, name, descriptor, methods::add);
        }

        ClassFacts build() {
            return new ClassFacts(internalName, superName, interfaces, signature,
                Map.copyOf(annotations), List.copyOf(fields), List.copyOf(methods));
        }
    }

    private static final class MethodCollector extends MethodVisitor {

        private final String ownerInternalName;
        private final MethodRef ref;
        private final int access;
        private final Consumer<MethodFacts> sink;
        private final List<MethodRef> calls = new ArrayList<>();
        private final List<String> newTypes = new ArrayList<>();
        private final List<String> stringConstants = new ArrayList<>();
        private final Set<String> writtenOwnFields = new LinkedHashSet<>();
        private final Map<String, AnnotationValues> annotations = new LinkedHashMap<>();
        private final List<MethodRef> lambdaBodies = new ArrayList<>();

        MethodCollector(String owner, int access, String name, String descriptor,
            Consumer<MethodFacts> sink) {
            super(API);
            this.ownerInternalName = owner;
            this.ref = new MethodRef(owner, name, descriptor);
            this.access = access;
            this.sink = sink;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return new ValueCollector(descriptor, annotations::put);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
            boolean isInterface) {
            calls.add(new MethodRef(owner, name, descriptor));
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (opcode == Opcodes.NEW) {
                newTypes.add(type);
            }
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (value instanceof String text) {
                stringConstants.add(text);
            }
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (opcode == Opcodes.PUTFIELD && owner.equals(ownerInternalName)) {
                writtenOwnFields.add(name);
            }
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor,
            Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
            // 람다의 부트스트랩 인자는 [SAM 타입, 구현 메서드 핸들, 인스턴스화 타입] 입니다.
            // 문자열 결합(makeConcatWithConstants)의 인자에는 Handle 이 없으므로 걸러집니다.
            for (Object argument : bootstrapMethodArguments) {
                if (argument instanceof Handle handle) {
                    lambdaBodies.add(new MethodRef(handle.getOwner(), handle.getName(), handle.getDesc()));
                }
            }
        }

        @Override
        public void visitEnd() {
            sink.accept(new MethodFacts(ref, access, List.copyOf(calls), List.copyOf(newTypes),
                List.copyOf(stringConstants), Set.copyOf(writtenOwnFields),
                Map.copyOf(annotations), List.copyOf(lambdaBodies)));
        }
    }

    private static final class ValueCollector extends AnnotationVisitor {

        private final String descriptor;
        private final BiConsumer<String, AnnotationValues> sink;
        private final Map<String, Object> values = new LinkedHashMap<>();

        ValueCollector(String descriptor, BiConsumer<String, AnnotationValues> sink) {
            super(API);
            this.descriptor = descriptor;
            this.sink = sink;
        }

        @Override
        public void visit(String name, Object value) {
            values.put(name, normalize(value));
        }

        @Override
        public void visitEnum(String name, String enumDescriptor, String value) {
            values.put(name, value);
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            List<Object> items = new ArrayList<>();
            values.put(name, items);
            return new AnnotationVisitor(API) {
                @Override
                public void visit(String itemName, Object value) {
                    items.add(normalize(value));
                }

                @Override
                public void visitEnum(String itemName, String enumDescriptor, String value) {
                    items.add(value);
                }
            };
        }

        @Override
        public void visitEnd() {
            sink.accept(descriptor, new AnnotationValues(Map.copyOf(values)));
        }

        /** 클래스 값은 ASM Type 으로 오므로 internal name 문자열로 바꿉니다. */
        private static Object normalize(Object value) {
            return value instanceof Type type ? type.getInternalName() : value;
        }
    }
}
```

- [ ] **Step 6: 테스트를 실행해 통과를 확인한다**

```bash
./gradlew :invalidation-map-core:test --console=plain
```

Expected: `BUILD SUCCESSFUL`, 9개 테스트 통과

- [ ] **Step 7: 커밋한다 (구현 먼저, 테스트 나중)**

```bash
git add settings.gradle build.gradle .gitignore gradlew gradlew.bat gradle/ \
        invalidation-map-core/build.gradle \
        invalidation-map-core/src/main
git commit -m "$(cat <<'MSG'
feat(core): 바이트코드에서 클래스 사실을 뽑는 ClassFactsReader 추가

Gradle 멀티프로젝트 스캐폴드와 함께, ASM 으로 클래스 파일 하나를 읽어
호출 대상·NEW 타입·문자열 상수·자기 필드 쓰기·어노테이션 값·람다 본문을
값 타입으로 뽑아냅니다. 코어에서 ASM 을 쓰는 유일한 지점입니다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
MSG
)"

git add invalidation-map-core/src/test
git commit -m "$(cat <<'MSG'
test(core): ClassFactsReader 단위 테스트 추가

컴파일된 픽스처 클래스의 바이트를 읽어 여섯 가지 바이트코드 사실이
모두 뽑히는지 확인합니다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
MSG
)"
```

---
## Task 2: ProgramModel 이음새와 ClassRepository

**Files:**
- Create: `invalidation-map-core/src/main/java/dev/toktokhan/invalidation/core/Endpoint.java`
- Create: `invalidation-map-core/src/main/java/dev/toktokhan/invalidation/core/ProgramModel.java`
- Create: `invalidation-map-core/src/main/java/dev/toktokhan/invalidation/core/MethodRefs.java`
- Create: `invalidation-map-core/src/main/java/dev/toktokhan/invalidation/core/scan/SignatureTypeArguments.java`
- Create: `invalidation-map-core/src/main/java/dev/toktokhan/invalidation/core/index/ClassRepository.java`
- Test: `invalidation-map-core/src/test/java/dev/toktokhan/invalidation/core/support/FakeProgramModel.java`
- Test: `invalidation-map-core/src/test/java/dev/toktokhan/invalidation/core/fixture/hierarchy/*.java`
- Test: `invalidation-map-core/src/test/java/dev/toktokhan/invalidation/core/index/ClassRepositoryTest.java`

**Interfaces:**
- Consumes: Task 1 의 `MethodRef`, `ClassFacts`, `MethodFacts`, `ClassFactsReader.read(byte[])`
- Produces:
  - `record Endpoint(String httpMethod, String path, MethodRef handler)`
  - `interface ProgramModel` — `List<Endpoint> endpoints()`, `Optional<byte[]> classBytes(String)`, `Optional<String> entityFor(String)`, `Set<String> implementationsOf(String)`, `Set<String> entities()`
  - `MethodRefs.of(java.lang.reflect.Method)` → `MethodRef`, `MethodRefs.internalNameOf(Class<?>)` → `String`
  - `SignatureTypeArguments.parse(String classSignature)` → `Map<String, List<String>>` (상위 타입 internal name → 타입 인자 internal name 목록)
  - `ClassRepository(ProgramModel)` — `facts(String)`, `methodFacts(MethodRef)`, `resolveMethod(MethodRef)`, `supertypesOf(String)`, `isSubtypeOf(String, String)`, `typeArgumentOfSupertype(String, String)`
  - 테스트 도우미 `FakeProgramModel.create()` 와 빌더 메서드들

**설계 판단 두 가지**

1. `classBytes` 의 반환 타입을 설계 문서의 `byte[]` 에서 `Optional<byte[]>` 로 바꿉니다. JDK 클래스나 외부 라이브러리 클래스는 바이트를 못 구할 수 있고, null 반환은 호출부마다 검사를 빠뜨리게 만듭니다.

2. `MethodRefs` 를 코어에 둡니다. 스타터는 `HandlerMethod.getMethod()` 에서 `MethodRef` 를 만들어야 하는데 ASM 은 코어의 `implementation` 의존성이라 스타터 클래스패스에 없습니다. 디스크립터 계산을 코어에 두면 스타터가 ASM 을 몰라도 됩니다.

3. `scan` 패키지가 코어에서 ASM 을 쓰는 유일한 층입니다. `ClassFactsReader` 와 `SignatureTypeArguments` 두 파일입니다. `index`, `walk`, `resolve` 는 ASM 을 import 하지 않습니다.

- [ ] **Step 1: 실패하는 테스트를 작성한다**

픽스처. `src/test/java/dev/toktokhan/invalidation/core/fixture/hierarchy/`:

```java
package dev.toktokhan.invalidation.core.fixture.hierarchy;

/** 제네릭 상위 타입의 타입 인자를 뽑는 대상입니다. */
public abstract class TypedBase<T> {

    public abstract T load();
}
```

```java
package dev.toktokhan.invalidation.core.fixture.hierarchy;

public class Payload {
}
```

```java
package dev.toktokhan.invalidation.core.fixture.hierarchy;

/** TypedBase<Payload> 를 상속하므로 시그니처에 타입 인자가 남습니다. */
public class TypedChild extends TypedBase<Payload> {

    @Override
    public Payload load() {
        return new Payload();
    }
}
```

```java
package dev.toktokhan.invalidation.core.fixture.hierarchy;

public interface Port {

    void run();
}
```

```java
package dev.toktokhan.invalidation.core.fixture.hierarchy;

/** 인터페이스에는 본문이 없으므로 resolveMethod 가 구현체를 찾아야 합니다. */
public class PortAdapter implements Port {

    @Override
    public void run() {
    }
}
```

테스트 도우미 `support/FakeProgramModel.java`:

```java
package dev.toktokhan.invalidation.core.support;

import dev.toktokhan.invalidation.core.Endpoint;
import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.ProgramModel;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 테스트용 ProgramModel 입니다. 클래스 바이트는 테스트 클래스패스에서 읽고,
 * 나머지 사실은 빌더로 직접 넣습니다.
 */
public final class FakeProgramModel implements ProgramModel {

    private final List<Endpoint> endpoints = new ArrayList<>();
    private final Map<String, String> repositoryEntities = new LinkedHashMap<>();
    private final Map<String, Set<String>> implementations = new LinkedHashMap<>();
    private final Set<String> entities = new LinkedHashSet<>();

    private FakeProgramModel() {
    }

    public static FakeProgramModel create() {
        return new FakeProgramModel();
    }

    public FakeProgramModel withEndpoint(String httpMethod, String path, Class<?> handlerType,
        String methodName, Class<?>... parameterTypes) {
        endpoints.add(new Endpoint(httpMethod, path,
            MethodRefs.of(findMethod(handlerType, methodName, parameterTypes))));
        return this;
    }

    public FakeProgramModel withRepositoryEntity(Class<?> repositoryType, Class<?> entityType) {
        repositoryEntities.put(MethodRefs.internalNameOf(repositoryType),
            MethodRefs.internalNameOf(entityType));
        entities.add(MethodRefs.internalNameOf(entityType));
        return this;
    }

    public FakeProgramModel withImplementation(Class<?> interfaceType, Class<?> implementationType) {
        implementations
            .computeIfAbsent(MethodRefs.internalNameOf(interfaceType), key -> new LinkedHashSet<>())
            .add(MethodRefs.internalNameOf(implementationType));
        return this;
    }

    public FakeProgramModel withEntity(Class<?> entityType) {
        entities.add(MethodRefs.internalNameOf(entityType));
        return this;
    }

    @Override
    public List<Endpoint> endpoints() {
        return List.copyOf(endpoints);
    }

    @Override
    public Optional<byte[]> classBytes(String internalName) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(internalName + ".class")) {
            return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<String> entityFor(String repositoryInternalName) {
        return Optional.ofNullable(repositoryEntities.get(repositoryInternalName));
    }

    @Override
    public Set<String> implementationsOf(String interfaceInternalName) {
        return implementations.getOrDefault(interfaceInternalName, Set.of());
    }

    @Override
    public Set<String> entities() {
        return Set.copyOf(entities);
    }

    public MethodRef ref(Class<?> type, String methodName, Class<?>... parameterTypes) {
        return MethodRefs.of(findMethod(type, methodName, parameterTypes));
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        if (parameterTypes.length > 0) {
            try {
                return type.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException e) {
                throw new IllegalArgumentException("메서드를 찾을 수 없습니다: " + type.getName() + "#" + name, e);
            }
        }
        return Arrays.stream(type.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(name))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "메서드를 찾을 수 없습니다: " + type.getName() + "#" + name));
    }
}
```

`index/ClassRepositoryTest.java`:

```java
package dev.toktokhan.invalidation.core.index;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.fixture.hierarchy.Payload;
import dev.toktokhan.invalidation.core.fixture.hierarchy.Port;
import dev.toktokhan.invalidation.core.fixture.hierarchy.PortAdapter;
import dev.toktokhan.invalidation.core.fixture.hierarchy.TypedBase;
import dev.toktokhan.invalidation.core.fixture.hierarchy.TypedChild;
import dev.toktokhan.invalidation.core.support.FakeProgramModel;
import org.junit.jupiter.api.Test;

class ClassRepositoryTest {

    private final ClassRepository classes = new ClassRepository(FakeProgramModel.create());

    @Test
    void facts_classOnTestClasspath_readsFacts() {
        assertThat(classes.facts(MethodRefs.internalNameOf(TypedChild.class)))
            .get()
            .satisfies(facts -> assertThat(facts.superName())
                .isEqualTo(MethodRefs.internalNameOf(TypedBase.class)));
    }

    @Test
    void facts_classNotOnClasspath_returnsEmpty() {
        assertThat(classes.facts("com/nowhere/Missing")).isEmpty();
    }

    @Test
    void facts_calledTwice_readsBytesOnce() {
        String name = MethodRefs.internalNameOf(TypedChild.class);
        assertThat(classes.facts(name)).isSameAs(classes.facts(name));
    }

    @Test
    void supertypesOf_classWithSuperAndInterface_reportsAllTransitively() {
        assertThat(classes.supertypesOf(MethodRefs.internalNameOf(PortAdapter.class)))
            .contains(MethodRefs.internalNameOf(Port.class), "java/lang/Object");
    }

    @Test
    void isSubtypeOf_sameType_isTrue() {
        String name = MethodRefs.internalNameOf(PortAdapter.class);
        assertThat(classes.isSubtypeOf(name, name)).isTrue();
    }

    @Test
    void isSubtypeOf_implementedInterface_isTrue() {
        assertThat(classes.isSubtypeOf(
            MethodRefs.internalNameOf(PortAdapter.class),
            MethodRefs.internalNameOf(Port.class))).isTrue();
    }

    @Test
    void isSubtypeOf_unrelatedType_isFalse() {
        assertThat(classes.isSubtypeOf(
            MethodRefs.internalNameOf(PortAdapter.class),
            MethodRefs.internalNameOf(Payload.class))).isFalse();
    }

    @Test
    void typeArgumentOfSupertype_genericSuperclass_reportsTypeArgument() {
        assertThat(classes.typeArgumentOfSupertype(
            MethodRefs.internalNameOf(TypedChild.class),
            MethodRefs.internalNameOf(TypedBase.class)))
            .contains(MethodRefs.internalNameOf(Payload.class));
    }

    @Test
    void resolveMethod_interfaceMethodWithoutBody_findsNothingOnInterface() {
        MethodRef onInterface = new MethodRef(MethodRefs.internalNameOf(Port.class), "run", "()V");
        assertThat(classes.methodFacts(onInterface))
            .get()
            .satisfies(facts -> assertThat(facts.calls()).isEmpty());
    }

    @Test
    void resolveMethod_methodDeclaredOnSupertype_findsItOnSupertype() {
        MethodRef declaredOnChild = new MethodRef(
            MethodRefs.internalNameOf(TypedChild.class), "hashCode", "()I");
        assertThat(classes.resolveMethod(declaredOnChild))
            .get()
            .satisfies(facts -> assertThat(facts.ref().owner()).isEqualTo("java/lang/Object"));
    }
}
```

- [ ] **Step 2: 테스트를 실행해 실패를 확인한다**

```bash
./gradlew :invalidation-map-core:test --tests '*ClassRepositoryTest' --console=plain
```

Expected: 컴파일 실패. `cannot find symbol: class ClassRepository`

- [ ] **Step 3: Endpoint, ProgramModel, MethodRefs 를 구현한다**

`Endpoint.java`:

```java
package dev.toktokhan.invalidation.core;

/**
 * 분석 대상 엔드포인트 하나입니다.
 *
 * <p>{@code handler} 가 식별자입니다. {@code httpMethod} 와 {@code path} 는 로그와 진단
 * 출력에만 씁니다. 한 핸들러에 경로가 여러 개 붙은 매핑에서도 키가 갈라지지 않도록
 * {@link InvalidationMap} 은 {@code handler} 로만 키를 잡습니다.
 */
public record Endpoint(String httpMethod, String path, MethodRef handler) {

    @Override
    public String toString() {
        return httpMethod + " " + path;
    }
}
```

`ProgramModel.java`:

```java
package dev.toktokhan.invalidation.core;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 코어가 환경에 묻는 사실입니다. 코어는 Spring 을 모르므로 이 인터페이스로만 환경을 봅니다.
 *
 * <p>런타임 구현은 Spring 메타데이터에서 값을 채웁니다. 빌드 시 구현은 클래스패스 스캔으로
 * 채웁니다. 모든 클래스 이름은 ASM internal name 입니다.
 */
public interface ProgramModel {

    /** 분석 대상 엔드포인트 전체입니다. */
    List<Endpoint> endpoints();

    /** 클래스 파일의 바이트입니다. 구할 수 없으면 빈 값입니다. */
    Optional<byte[]> classBytes(String internalName);

    /** Spring Data 리포지토리 타입이 다루는 엔티티입니다. 리포지토리가 아니면 빈 값입니다. */
    Optional<String> entityFor(String repositoryInternalName);

    /**
     * 인터페이스를 구현하는 타입입니다.
     *
     * <p>런타임 구현은 두 곳에서 찾아야 합니다. 빈으로 등록된 구현체와, Spring Data 가 내부에서
     * 만드는 리포지토리 프래그먼트 구현체입니다. 프래그먼트는 빈이 아니므로 빈 팩토리만 보면
     * 놓칩니다.
     */
    Set<String> implementationsOf(String interfaceInternalName);

    /** JPA 가 관리하는 엔티티 전체입니다. */
    Set<String> entities();
}
```

`MethodRefs.java`:

```java
package dev.toktokhan.invalidation.core;

import java.lang.reflect.Method;
import org.objectweb.asm.Type;

/**
 * 리플렉션 객체를 {@link MethodRef} 와 internal name 으로 바꿉니다.
 *
 * <p>디스크립터 계산을 코어에 두어, 스타터가 ASM 을 클래스패스에 두지 않아도
 * {@code HandlerMethod} 에서 {@link MethodRef} 를 만들 수 있게 합니다.
 */
public final class MethodRefs {

    private MethodRefs() {
    }

    public static MethodRef of(Method method) {
        return new MethodRef(
            internalNameOf(method.getDeclaringClass()),
            method.getName(),
            Type.getMethodDescriptor(method));
    }

    /** 선언 클래스를 직접 지정합니다. 프록시 대신 실제 클래스를 넣을 때 씁니다. */
    public static MethodRef of(Class<?> declaringClass, Method method) {
        return new MethodRef(
            internalNameOf(declaringClass),
            method.getName(),
            Type.getMethodDescriptor(method));
    }

    public static String internalNameOf(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    public static String fqcnOf(String internalName) {
        return internalName.replace('/', '.');
    }

    public static String simpleNameOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        String name = slash < 0 ? internalName : internalName.substring(slash + 1);
        int dollar = name.lastIndexOf('$');
        return dollar < 0 ? name : name.substring(dollar + 1);
    }
}
```

- [ ] **Step 4: SignatureTypeArguments 를 구현한다**

`scan/SignatureTypeArguments.java`:

```java
package dev.toktokhan.invalidation.core.scan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

/**
 * 클래스 제네릭 시그니처에서 상위 타입별 타입 인자를 뽑습니다.
 *
 * <p>예: {@code Lcom/querydsl/core/types/dsl/EntityPathBase<Lcom/example/Run;>;} 에서
 * {@code com/querydsl/core/types/dsl/EntityPathBase -> [com/example/Run]} 를 얻습니다.
 *
 * <p>한 단계 중첩만 읽습니다. {@code List<Map<String, Run>>} 처럼 두 단계 이상 중첩된
 * 타입 인자에서는 바깥 인자만 잡힙니다. 이 라이브러리가 필요한 시그니처
 * ({@code JpaRepository<E, ID>}, {@code EntityPathBase<E>})는 모두 한 단계입니다.
 */
public final class SignatureTypeArguments {

    private static final int API = Opcodes.ASM9;

    private SignatureTypeArguments() {
    }

    public static Map<String, List<String>> parse(String classSignature) {
        if (classSignature == null || classSignature.isBlank()) {
            return Map.of();
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        new SignatureReader(classSignature).accept(new SignatureVisitor(API) {
            @Override
            public SignatureVisitor visitSuperclass() {
                return new SupertypeCollector(result);
            }

            @Override
            public SignatureVisitor visitInterface() {
                return new SupertypeCollector(result);
            }

            // 타입 파라미터 바운드(<T extends Foo>)는 상위 타입이 아니므로 버립니다.
            @Override
            public SignatureVisitor visitClassBound() {
                return new SignatureVisitor(API) {
                };
            }

            @Override
            public SignatureVisitor visitInterfaceBound() {
                return new SignatureVisitor(API) {
                };
            }
        });
        return result;
    }

    private static final class SupertypeCollector extends SignatureVisitor {

        private final Map<String, List<String>> result;
        private String owner;

        SupertypeCollector(Map<String, List<String>> result) {
            super(API);
            this.result = result;
        }

        @Override
        public void visitClassType(String name) {
            if (owner == null) {
                owner = name;
                result.computeIfAbsent(name, key -> new ArrayList<>());
            }
        }

        @Override
        public SignatureVisitor visitTypeArgument(char wildcard) {
            if (owner == null) {
                return new SignatureVisitor(API) {
                };
            }
            List<String> arguments = result.get(owner);
            return new SignatureVisitor(API) {
                @Override
                public void visitClassType(String name) {
                    arguments.add(name);
                }
            };
        }
    }
}
```

- [ ] **Step 5: ClassRepository 를 구현한다**

`index/ClassRepository.java`:

```java
package dev.toktokhan.invalidation.core.index;

import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.ProgramModel;
import dev.toktokhan.invalidation.core.scan.ClassFacts;
import dev.toktokhan.invalidation.core.scan.ClassFactsReader;
import dev.toktokhan.invalidation.core.scan.MethodFacts;
import dev.toktokhan.invalidation.core.scan.SignatureTypeArguments;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ProgramModel} 위에 얹은 {@link ClassFacts} 캐시입니다. 타입 계층 질문도 여기서 답합니다.
 *
 * <p>같은 클래스를 여러 엔드포인트가 지나가므로 캐시가 없으면 같은 바이트를 반복해서 읽습니다.
 */
public final class ClassRepository {

    private final ProgramModel program;
    private final Map<String, Optional<ClassFacts>> factsCache = new ConcurrentHashMap<>();
    private final Map<String, List<String>> supertypeCache = new ConcurrentHashMap<>();

    public ClassRepository(ProgramModel program) {
        this.program = program;
    }

    public Optional<ClassFacts> facts(String internalName) {
        return factsCache.computeIfAbsent(internalName,
            name -> program.classBytes(name).map(ClassFactsReader::read));
    }

    /** 선언된 그 클래스에서만 찾습니다. */
    public Optional<MethodFacts> methodFacts(MethodRef ref) {
        return facts(ref.owner()).flatMap(classFacts -> classFacts.method(ref));
    }

    /**
     * 선언 클래스에 없으면 상위 타입을 따라 올라가며 같은 이름과 디스크립터의 메서드를 찾습니다.
     *
     * <p>호출 지점은 컴파일 시점의 선언 타입으로 기록되므로, 실제 본문이 상위 클래스에 있는
     * 경우가 있습니다.
     */
    public Optional<MethodFacts> resolveMethod(MethodRef ref) {
        Optional<MethodFacts> direct = methodFacts(ref);
        if (direct.isPresent()) {
            return direct;
        }
        return supertypesOf(ref.owner()).stream()
            .map(supertype -> methodFacts(new MethodRef(supertype, ref.name(), ref.descriptor())))
            .flatMap(Optional::stream)
            .findFirst();
    }

    /** 자신을 제외한 모든 상위 클래스와 인터페이스입니다. 너비 우선 순서입니다. */
    public List<String> supertypesOf(String internalName) {
        return supertypeCache.computeIfAbsent(internalName, start -> {
            List<String> ordered = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(start);
            seen.add(start);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                if (!current.equals(start)) {
                    ordered.add(current);
                }
                facts(current).ifPresent(classFacts -> {
                    if (classFacts.superName() != null && seen.add(classFacts.superName())) {
                        queue.add(classFacts.superName());
                    }
                    for (String interfaceName : classFacts.interfaces()) {
                        if (seen.add(interfaceName)) {
                            queue.add(interfaceName);
                        }
                    }
                });
            }
            return List.copyOf(ordered);
        });
    }

    public boolean isSubtypeOf(String internalName, String candidateSupertype) {
        return internalName.equals(candidateSupertype)
            || supertypesOf(internalName).contains(candidateSupertype);
    }

    /**
     * 지정한 상위 타입의 첫 타입 인자를 찾습니다. 상위 타입을 따라 올라가며 찾습니다.
     *
     * <p>{@code QRun extends EntityPathBase<Run>} 에서 {@code Run} 을 얻는 데 씁니다.
     */
    public Optional<String> typeArgumentOfSupertype(String internalName, String supertypeInternalName) {
        List<String> chain = new ArrayList<>();
        chain.add(internalName);
        chain.addAll(supertypesOf(internalName));
        for (String current : chain) {
            Optional<String> found = facts(current)
                .map(classFacts -> SignatureTypeArguments.parse(classFacts.signature()))
                .map(arguments -> arguments.getOrDefault(supertypeInternalName, List.of()))
                .filter(arguments -> !arguments.isEmpty())
                // List.getFirst() 는 Java 21 API 입니다. release = 17 이므로 get(0) 을 씁니다.
                .map(arguments -> arguments.get(0));
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 6: 테스트를 실행해 통과를 확인한다**

```bash
./gradlew :invalidation-map-core:test --console=plain
```

Expected: `BUILD SUCCESSFUL`, `ClassRepositoryTest` 10개 통과

- [ ] **Step 7: 커밋한다 (구현 먼저, 테스트 나중)**

```bash
git add invalidation-map-core/src/main
git commit -m "$(cat <<'MSG'
feat(core): ProgramModel 이음새와 ClassRepository 추가

코어가 환경에 묻는 다섯 가지를 ProgramModel 로 좁히고, 그 위에 ClassFacts
캐시와 타입 계층 판정을 얹었습니다. 제네릭 상위 타입의 타입 인자를 뽑는
SignatureTypeArguments 도 함께 넣었습니다.

MethodRefs 를 코어에 둔 이유는 스타터가 ASM 없이 HandlerMethod 에서
MethodRef 를 만들 수 있게 하기 위함입니다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
MSG
)"

git add invalidation-map-core/src/test
git commit -m "$(cat <<'MSG'
test(core): ClassRepository 단위 테스트와 FakeProgramModel 추가

캐시 동작, 타입 계층 판정, 제네릭 타입 인자 추출, 상위 타입 메서드 해석을
확인합니다. FakeProgramModel 은 테스트 클래스패스에서 바이트를 읽으므로
Spring 컨텍스트가 필요 없습니다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
MSG
)"
```

---
## Task 3: EntityIndex — 엔티티, 변경자, 연관, 테이블명

**Files:**
- Modify: `invalidation-map-core/src/main/java/dev/toktokhan/invalidation/core/scan/SignatureTypeArguments.java` (필드 타입 시그니처용 메서드 추가)
- Create: `invalidation-map-core/src/main/java/dev/toktokhan/invalidation/core/index/EntityIndex.java`
- Test: `invalidation-map-core/src/test/java/dev/toktokhan/invalidation/core/fixture/entity/*.java`
- Test: `invalidation-map-core/src/test/java/dev/toktokhan/invalidation/core/index/EntityIndexTest.java`

**Interfaces:**
- Consumes: Task 1 의 `ClassFacts`/`FieldFacts`/`MethodFacts`, Task 2 의 `ClassRepository`, `MethodRefs`, `ProgramModel.entities()`
- Produces:
  - `SignatureTypeArguments.typeArgumentsOfFieldType(String fieldSignature)` → `List<String>`
  - `EntityIndex(ClassRepository, Set<String> reportedEntities)`
  - `boolean isEntity(String internalName)`
  - `boolean isMutator(MethodRef ref)` — 호출 지점이 엔티티 변경자인지
  - `Set<String> associationsOf(String entityInternalName)` — 한 단계 연관 대상 엔티티
  - `Optional<String> entityForTable(String tableName)`

**판정 규칙 세 가지 (설계 문서 4.3, 5.1 구현)**

1. **변경자 판정.** 엔티티 클래스의 인스턴스 메서드 중 `PUTFIELD` 로 자기 필드에 쓰거나, 같은 클래스의 다른 변경자를 호출하는 것입니다. 고정점에 도달할 때까지 반복해 전이적으로 계산합니다. `<init>`, `<clinit>`, 정적 메서드는 제외합니다.

2. **상속된 변경자.** `Run run = ...; run.markDeleted();` 에서 `markDeleted` 가 `@MappedSuperclass BaseEntity` 에 있어도, javac 은 호출 지점의 owner 를 정적 수신 타입인 `Run` 으로 기록합니다. 따라서 `isMutator` 는 `ref.owner()` 가 엔티티인지 확인하고, 메서드 본문은 상위 타입을 따라 올라가며 찾습니다. 쓰이는 엔티티는 `ref.owner()` 입니다. 이렇게 하면 `BaseEntity` 를 상속한 엔티티 전체를 무효화하는 과잉이 생기지 않습니다.

3. **테이블명 역매핑.** `@Table(name = ...)` 이 있으면 그 값을 씁니다. 없으면 두 가지 기본 규칙을 **모두** 등록합니다. JPA 표준 기본값(엔티티 단순명 그대로)과 Spring Boot 기본값(`SpringPhysicalNamingStrategy` 의 CamelCase → snake_case)입니다. 조회 표에 여분의 항목이 있어도 해가 없고, 어느 네이밍 전략을 쓰는 프로젝트든 덮습니다.

- [ ] **Step 1: 픽스처와 실패하는 테스트를 작성한다**

`src/test/java/dev/toktokhan/invalidation/core/fixture/entity/`:

```java
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
```

```java
package dev.toktokhan.invalidation.core.fixture.entity;

import jakarta.persistence.Embeddable;

@Embeddable
public class Coordinate {

    private double latitude;

    public double latitude() {
        return latitude;
    }
}
```

```java
package dev.toktokhan.invalidation.core.fixture.entity;

import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "trip_log")
public class Trip extends BaseRecord {

    @Id
    private Long id;

    private String title;

    private int distance;

    @OneToMany
    private List<TripLeg> legs = new ArrayList<>();

    @Embedded
    private Coordinate start;

    /** 직접 변경자: 자기 필드에 씁니다. */
    public void rename(String next) {
        this.title = next;
    }

    /** 전이적 변경자: 다른 변경자를 호출합니다. */
    public void reset(String next) {
        rename(next);
        setDistance(0);
    }

    private void setDistance(int next) {
        this.distance = next;
    }

    /** 변경자가 아닙니다: 읽기만 합니다. */
    public String title() {
        return title;
    }

    /** 변경자가 아닙니다: 정적 팩터리는 새 객체를 만듭니다. */
    public static Trip create(String title) {
        Trip trip = new Trip();
        trip.title = title;
        return trip;
    }
}
```

```java
package dev.toktokhan.invalidation.core.fixture.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** @Table 이 없으므로 기본 테이블명 규칙이 적용됩니다. */
@Entity
public class TripLeg {

    @Id
    private Long id;
}
```

`index/EntityIndexTest.java`:

```java
package dev.toktokhan.invalidation.core.index;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.fixture.entity.Coordinate;
import dev.toktokhan.invalidation.core.fixture.entity.Trip;
import dev.toktokhan.invalidation.core.fixture.entity.TripLeg;
import dev.toktokhan.invalidation.core.support.FakeProgramModel;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EntityIndexTest {

    private static final String TRIP = MethodRefs.internalNameOf(Trip.class);

    private final ClassRepository classes = new ClassRepository(FakeProgramModel.create());
    private final EntityIndex entities = new EntityIndex(classes, Set.of(
        TRIP,
        MethodRefs.internalNameOf(TripLeg.class),
        MethodRefs.internalNameOf(Coordinate.class)));

    @Test
    void isMutator_methodWritesOwnField_isTrue() {
        assertThat(entities.isMutator(new MethodRef(TRIP, "rename", "(Ljava/lang/String;)V"))).isTrue();
    }

    @Test
    void isMutator_methodCallsAnotherMutator_isTrue() {
        assertThat(entities.isMutator(new MethodRef(TRIP, "reset", "(Ljava/lang/String;)V"))).isTrue();
    }

    @Test
    void isMutator_readOnlyMethod_isFalse() {
        assertThat(entities.isMutator(new MethodRef(TRIP, "title", "()Ljava/lang/String;"))).isFalse();
    }

    @Test
    void isMutator_staticFactory_isFalse() {
        assertThat(entities.isMutator(
            new MethodRef(TRIP, "create", "(Ljava/lang/String;)L" + TRIP + ";"))).isFalse();
    }

    @Test
    void isMutator_constructor_isFalse() {
        assertThat(entities.isMutator(new MethodRef(TRIP, "<init>", "()V"))).isFalse();
    }

    @Test
    void isMutator_mutatorInheritedFromMappedSuperclass_isTrueAndAttributedToOwner() {
        // 호출 지점의 owner 는 정적 수신 타입인 Trip 입니다. 본문은 BaseRecord 에 있습니다.
        assertThat(entities.isMutator(new MethodRef(TRIP, "markDeleted", "()V"))).isTrue();
    }

    @Test
    void isMutator_methodOnNonEntityClass_isFalse() {
        assertThat(entities.isMutator(
            new MethodRef("java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;"))).isFalse();
    }

    @Test
    void associationsOf_collectionAssociation_reportsElementEntity() {
        assertThat(entities.associationsOf(TRIP))
            .contains(MethodRefs.internalNameOf(TripLeg.class));
    }

    @Test
    void associationsOf_embeddedAssociation_reportsEmbeddableType() {
        assertThat(entities.associationsOf(TRIP))
            .contains(MethodRefs.internalNameOf(Coordinate.class));
    }

    @Test
    void associationsOf_nonEntity_reportsNothing() {
        assertThat(entities.associationsOf("java/lang/String")).isEmpty();
    }

    @Test
    void entityForTable_explicitTableAnnotation_resolvesEntity() {
        assertThat(entities.entityForTable("trip_log")).contains(TRIP);
    }

    @Test
    void entityForTable_noTableAnnotation_resolvesBySnakeCaseDefault() {
        assertThat(entities.entityForTable("trip_leg"))
            .contains(MethodRefs.internalNameOf(TripLeg.class));
    }

    @Test
    void entityForTable_noTableAnnotation_alsoResolvesByJpaDefault() {
        assertThat(entities.entityForTable("TripLeg"))
            .contains(MethodRefs.internalNameOf(TripLeg.class));
    }

    @Test
    void entityForTable_unknownTable_returnsEmpty() {
        assertThat(entities.entityForTable("nowhere")).isEmpty();
    }
}
```

- [ ] **Step 2: 테스트를 실행해 실패를 확인한다**

```bash
./gradlew :invalidation-map-core:test --tests '*EntityIndexTest' --console=plain
```

Expected: 컴파일 실패. `cannot find symbol: class EntityIndex`

- [ ] **Step 3: SignatureTypeArguments 에 필드 타입 시그니처용 메서드를 추가한다**

`SignatureTypeArguments.java` 에 다음 메서드를 추가합니다. 클래스 시그니처는 `accept` 로 읽지만 필드 타입 시그니처는 `acceptType` 으로 읽어야 합니다.

```java
    /**
     * 필드 타입 시그니처의 최상위 타입 인자를 돌려줍니다.
     *
     * <p>{@code Ljava/util/List<Lcom/example/TripLeg;>;} 에서 {@code [com/example/TripLeg]} 를,
     * {@code Ljava/util/Map<Ljava/lang/Long;Lcom/example/Member;>;} 에서
     * {@code [java/lang/Long, com/example/Member]} 를 얻습니다. 제네릭이 없으면 빈 목록입니다.
     */
    public static List<String> typeArgumentsOfFieldType(String fieldSignature) {
        if (fieldSignature == null || fieldSignature.isBlank()) {
            return List.of();
        }
        Map<String, List<String>> collected = new LinkedHashMap<>();
        new SignatureReader(fieldSignature).acceptType(new SupertypeCollector(collected));
        return collected.values().stream().findFirst().orElse(List.of());
    }
```

- [ ] **Step 4: EntityIndex 를 구현한다**

`index/EntityIndex.java`:

```java
package dev.toktokhan.invalidation.core.index;

import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.scan.ClassFacts;
import dev.toktokhan.invalidation.core.scan.FieldFacts;
import dev.toktokhan.invalidation.core.scan.MethodFacts;
import dev.toktokhan.invalidation.core.scan.SignatureTypeArguments;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 엔티티에 대해 알아야 하는 사실을 모아 둡니다. 변경자 판정, 한 단계 연관, 테이블명 역매핑입니다.
 */
public final class EntityIndex {

    private static final String TABLE = "Ljakarta/persistence/Table;";
    private static final String MAPPED_SUPERCLASS = "Ljakarta/persistence/MappedSuperclass;";

    private static final Set<String> ASSOCIATION_ANNOTATIONS = Set.of(
        "Ljakarta/persistence/OneToMany;",
        "Ljakarta/persistence/ManyToOne;",
        "Ljakarta/persistence/OneToOne;",
        "Ljakarta/persistence/ManyToMany;",
        "Ljakarta/persistence/Embedded;",
        "Ljakarta/persistence/ElementCollection;");

    private final ClassRepository classes;
    private final Set<String> entities;

    /** 선언 클래스 -> 그 클래스가 선언한 변경자의 "이름+디스크립터" */
    private final Map<String, Set<String>> mutatorsByDeclaringClass = new ConcurrentHashMap<>();

    private final Map<String, Set<String>> associationCache = new ConcurrentHashMap<>();
    private final Map<String, String> entityByTable;

    /**
     * @param reportedEntities {@code ProgramModel.entities()} 가 알려준 엔티티와 임베더블의
     *                         internal name 입니다
     */
    public EntityIndex(ClassRepository classes, Set<String> reportedEntities) {
        this.classes = classes;
        this.entities = Set.copyOf(reportedEntities);
        this.entityByTable = buildTableIndex();
    }

    public boolean isEntity(String internalName) {
        return entities.contains(internalName);
    }

    public Set<String> entities() {
        return entities;
    }

    /**
     * 이 호출 지점이 엔티티 변경자 호출인지 판정합니다.
     *
     * <p>{@code ref.owner()} 가 엔티티여야 합니다. 메서드 본문은 상위 타입을 따라 올라가며
     * 찾습니다. 상위 타입에 선언된 변경자를 호출해도 쓰이는 엔티티는 {@code ref.owner()} 입니다.
     */
    public boolean isMutator(MethodRef ref) {
        if (!isEntity(ref.owner())) {
            return false;
        }
        List<String> candidates = new ArrayList<>();
        candidates.add(ref.owner());
        candidates.addAll(classes.supertypesOf(ref.owner()));
        String signature = ref.name() + ref.descriptor();
        for (String declaringClass : candidates) {
            if (mutatorsOf(declaringClass).contains(signature)) {
                return true;
            }
        }
        return false;
    }

    /** 이 엔티티에서 한 단계 연관으로 닿는 엔티티입니다. 엔티티가 아닌 대상은 버립니다. */
    public Set<String> associationsOf(String entityInternalName) {
        if (!isEntity(entityInternalName)) {
            return Set.of();
        }
        return associationCache.computeIfAbsent(entityInternalName, name -> {
            Set<String> targets = new LinkedHashSet<>();
            List<String> hierarchy = new ArrayList<>();
            hierarchy.add(name);
            hierarchy.addAll(classes.supertypesOf(name));
            for (String current : hierarchy) {
                classes.facts(current).ifPresent(facts -> {
                    for (FieldFacts field : facts.fields()) {
                        if (isAssociation(field)) {
                            targets.addAll(associationTargets(field));
                        }
                    }
                });
            }
            targets.remove(name);
            return Set.copyOf(targets);
        });
    }

    public Optional<String> entityForTable(String tableName) {
        if (tableName == null) {
            return Optional.empty();
        }
        String direct = entityByTable.get(tableName);
        return direct != null
            ? Optional.of(direct)
            : Optional.ofNullable(entityByTable.get(tableName.toLowerCase()));
    }

    /** 이 클래스가 선언한 변경자의 "이름+디스크립터" 집합입니다. 전이적으로 고정점까지 계산합니다. */
    private Set<String> mutatorsOf(String declaringClass) {
        return mutatorsByDeclaringClass.computeIfAbsent(declaringClass, name -> {
            Optional<ClassFacts> maybeFacts = classes.facts(name);
            if (maybeFacts.isEmpty()) {
                return Set.of();
            }
            ClassFacts facts = maybeFacts.get();
            // 엔티티이거나 @MappedSuperclass 여야 변경자를 셉니다.
            boolean holdsState = isEntity(name) || facts.hasAnnotation(MAPPED_SUPERCLASS);
            if (!holdsState) {
                return Set.of();
            }

            List<MethodFacts> candidates = facts.methods().stream()
                .filter(method -> !method.isStatic())
                .filter(method -> !method.isConstructor())
                .toList();

            Set<String> mutators = new LinkedHashSet<>();
            for (MethodFacts method : candidates) {
                if (!method.writtenOwnFields().isEmpty()) {
                    mutators.add(method.ref().name() + method.ref().descriptor());
                }
            }

            // 다른 변경자를 호출하는 메서드도 변경자입니다. 더 늘지 않을 때까지 반복합니다.
            boolean changed = true;
            while (changed) {
                changed = false;
                for (MethodFacts method : candidates) {
                    String signature = method.ref().name() + method.ref().descriptor();
                    if (mutators.contains(signature)) {
                        continue;
                    }
                    boolean callsMutator = method.calls().stream()
                        .filter(call -> call.owner().equals(name))
                        .anyMatch(call -> mutators.contains(call.name() + call.descriptor()));
                    if (callsMutator) {
                        mutators.add(signature);
                        changed = true;
                    }
                }
            }
            return Set.copyOf(mutators);
        });
    }

    private static boolean isAssociation(FieldFacts field) {
        return field.annotations().keySet().stream().anyMatch(ASSOCIATION_ANNOTATIONS::contains);
    }

    /** 컬렉션 필드는 제네릭 인자에서, 단일 필드는 디스크립터에서 대상 타입을 얻습니다. */
    private Set<String> associationTargets(FieldFacts field) {
        Set<String> targets = new LinkedHashSet<>();
        for (String argument : SignatureTypeArguments.typeArgumentsOfFieldType(field.signature())) {
            if (isEntity(argument)) {
                targets.add(argument);
            }
        }
        if (targets.isEmpty()) {
            objectTypeOf(field.descriptor())
                .filter(this::isEntity)
                .ifPresent(targets::add);
        }
        return targets;
    }

    /** {@code Lcom/example/Trip;} 에서 {@code com/example/Trip} 을 뽑습니다. */
    private static Optional<String> objectTypeOf(String descriptor) {
        if (descriptor != null && descriptor.startsWith("L") && descriptor.endsWith(";")) {
            return Optional.of(descriptor.substring(1, descriptor.length() - 1));
        }
        return Optional.empty();
    }

    private Map<String, String> buildTableIndex() {
        Map<String, String> index = new LinkedHashMap<>();
        for (String entity : entities) {
            Optional<String> explicit = classes.facts(entity)
                .map(facts -> facts.annotation(TABLE))
                .flatMap(values -> values.string("name"))
                .filter(name -> !name.isBlank());
            if (explicit.isPresent()) {
                index.putIfAbsent(explicit.get(), entity);
                index.putIfAbsent(explicit.get().toLowerCase(), entity);
                continue;
            }
            // @Table 이 없으면 두 기본 규칙을 모두 등록합니다.
            // JPA 표준 기본값은 엔티티 단순명 그대로이고,
            // Spring Boot 기본값(SpringPhysicalNamingStrategy)은 snake_case 입니다.
            String simpleName = MethodRefs.simpleNameOf(entity);
            index.putIfAbsent(simpleName, entity);
            index.putIfAbsent(camelToSnake(simpleName), entity);
        }
        return Map.copyOf(index);
    }

    static String camelToSnake(String name) {
        StringBuilder out = new StringBuilder(name.length() + 8);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    out.append('_');
                }
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
```

- [ ] **Step 5: 테스트를 실행해 통과를 확인한다**

```bash
./gradlew :invalidation-map-core:test --console=plain
```

Expected: `BUILD SUCCESSFUL`, `EntityIndexTest` 14개 통과

- [ ] **Step 6: 커밋한다 (구현 먼저, 테스트 나중)**

```bash
git add invalidation-map-core/src/main
git commit -m "$(cat <<'MSG'
feat(core): EntityIndex 로 변경자·연관·테이블명을 색인

save() 명시 호출에 의존하지 않기 위해 더티체킹 변경자를 전이적으로 판정합니다.
상위 타입에 선언된 변경자를 호출해도 쓰이는 엔티티는 호출 지점의 owner 로
귀속시켜, MappedSuperclass 를 상속한 엔티티 전체가 무효화되는 과잉을 막습니다.

테이블명은 @Table 값이 없으면 JPA 표준 기본값과 Spring Boot 의 snake_case
기본값을 모두 등록해 어느 네이밍 전략이든 덮습니다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
MSG
)"

git add invalidation-map-core/src/test
git commit -m "$(cat <<'MSG'
test(core): EntityIndex 단위 테스트 추가

직접 변경자, 전이적 변경자, 읽기 전용 메서드, 정적 팩터리 제외, 생성자 제외,
MappedSuperclass 상속 변경자, 컬렉션·임베디드 연관, 테이블명 세 가지 규칙을
확인합니다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
MSG
)"
```

---
## Task 4: RepositoryIndex 와 ListenerIndex

**Files:**
- Modify: `.../core/ProgramModel.java` — `Set<MethodRef> eventListeners()` 추가
- Modify: `.../core/ProgramModel.java` — `entityFor` javadoc 에 프래그먼트 인터페이스 등록 의무 명시
- Create: `.../core/AccessKind.java`
- Create: `.../core/index/RepositoryIndex.java`
- Create: `.../core/index/ListenerIndex.java`
- Modify: `.../test/.../support/FakeProgramModel.java` — `withEventListener(...)` 빌더와 `eventListeners()` 구현 추가
- Test: `.../test/.../fixture/repo/*.java`, `.../fixture/event/*.java`
- Test: `.../test/.../index/RepositoryIndexTest.java`, `.../index/ListenerIndexTest.java`

**Interfaces:**
- Consumes: Task 2 의 `ClassRepository`, `ProgramModel`, `MethodRefs`
- Produces:
  - `enum AccessKind { READ, WRITE }`
  - `ProgramModel.eventListeners()` → `Set<MethodRef>`
  - `RepositoryIndex(ProgramModel, ClassRepository)`
    - `Optional<String> entityFor(String repositoryInternalName)`
    - `Optional<AccessKind> accessKindOf(MethodRef)` — 판정 불가면 빈 값
    - `Optional<String> queryOf(MethodRef)` — `@Query` 의 `value`
    - `boolean isNativeQuery(MethodRef)` — `@Query(nativeQuery = true)`
  - `ListenerIndex(ClassRepository, Set<MethodRef> listeners)`
    - `Set<MethodRef> listenersFor(String eventInternalName)` — 파라미터 타입에 할당 가능한 리스너

**판정 규칙**

`accessKindOf` 우선순위는 설계 문서 4.2절 1~2번입니다.

1. `@Modifying` (`Lorg/springframework/data/jpa/repository/Modifying;`) 이 있으면 `WRITE`
2. 메서드명 접두어
   - `WRITE`: `save` `delete` `remove` `insert` `update` `persist` `merge` `flush`
   - `READ`: `find` `read` `get` `query` `search` `stream` `exists` `count`
3. 둘 다 아니면 **빈 값을 돌려줍니다.** 임의로 `READ` 로 정하면 쓰기를 놓치고, `WRITE` 로 정하면
   과잉이 심해집니다. 판정을 미루면 워커가 그 메서드 본문으로 내려가 실제 접근을 찾습니다.
   본문에도 못 닿으면 분석기가 미해결 사유를 남깁니다 (Task 8).

`WRITE` 를 `READ` 보다 먼저 검사합니다. 접두어가 겹치는 이름은 현재 없지만, 겹칠 경우 과잉
방향으로 기울이는 것이 4.4 원칙에 맞습니다.

`ListenerIndex` 는 파라미터 타입 **할당 가능성**으로 매칭합니다. `RunCompletedEvent` 를 발행하고
리스너가 `BadgeEvent` 를 받는 경우처럼, 리스너 파라미터가 상위 타입일 수 있습니다.
`ClassRepository.isSubtypeOf(발행 타입, 리스너 파라미터 타입)` 로 판정합니다.

`@TransactionalEventListener(classes = {...})` 처럼 파라미터가 없고 어노테이션에 타입을 적는
형태도 지원합니다. `classes` 속성이 있으면 그것을 쓰고, 없으면 첫 파라미터 타입을 씁니다.

- [ ] **Step 1: 픽스처와 실패하는 테스트를 작성한다**

`fixture/repo/`:

```java
package dev.toktokhan.invalidation.core.fixture.repo;

import dev.toktokhan.invalidation.core.fixture.entity.Trip;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface TripJpaRepository extends JpaRepository<Trip, Long>, TripRepositoryCustom {

    Optional<Trip> findByTitle(String title);

    long countByTitle(String title);

    /** 이름은 read 로 시작하지만 @Modifying 이 있으므로 쓰기입니다. */
    @Modifying
    @Query("update Trip t set t.title = :title where t.id = :id")
    int readAndRewrite(Long id, String title);

    @Query("select t from Trip t join t.legs l where l.id = :legId")
    List<Trip> findByLeg(Long legId);

    @Query(value = "select * from trip_log", nativeQuery = true)
    List<Trip> findAllNative();
}
```

```java
package dev.toktokhan.invalidation.core.fixture.repo;

/** Spring Data 프래그먼트 인터페이스입니다. 메서드명이 어느 접두어에도 맞지 않습니다. */
public interface TripRepositoryCustom {

    void upsert(Long id, String title);
}
```

`fixture/event/`:

```java
package dev.toktokhan.invalidation.core.fixture.event;

import org.springframework.context.ApplicationEvent;

public abstract class TripEvent extends ApplicationEvent {

    protected TripEvent(Object source) {
        super(source);
    }
}
```

```java
package dev.toktokhan.invalidation.core.fixture.event;

public class TripCompletedEvent extends TripEvent {

    public TripCompletedEvent(Object source) {
        super(source);
    }
}
```

```java
package dev.toktokhan.invalidation.core.fixture.event;

import org.springframework.context.event.EventListener;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

public class TripEventListeners {

    /** 파라미터가 상위 타입입니다. TripCompletedEvent 발행에도 걸려야 합니다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTripEvent(TripEvent event) {
    }

    /** 관련 없는 이벤트를 듣습니다. 걸리면 안 됩니다. */
    @EventListener
    public void onString(String event) {
    }

    /** 리스너가 아닙니다. */
    public void notAListener(TripEvent event) {
    }
}
```

`index/RepositoryIndexTest.java`:

```java
package dev.toktokhan.invalidation.core.index;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.AccessKind;
import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.fixture.entity.Trip;
import dev.toktokhan.invalidation.core.fixture.repo.TripJpaRepository;
import dev.toktokhan.invalidation.core.fixture.repo.TripRepositoryCustom;
import dev.toktokhan.invalidation.core.support.FakeProgramModel;
import org.junit.jupiter.api.Test;

class RepositoryIndexTest {

    private static final String REPO = MethodRefs.internalNameOf(TripJpaRepository.class);
    private static final String FRAGMENT = MethodRefs.internalNameOf(TripRepositoryCustom.class);
    private static final String TRIP = MethodRefs.internalNameOf(Trip.class);

    private final FakeProgramModel program = FakeProgramModel.create()
        .withRepositoryEntity(TripJpaRepository.class, Trip.class)
        // 런타임 구현은 프래그먼트 인터페이스도 같은 엔티티로 등록해야 합니다.
        .withRepositoryEntity(TripRepositoryCustom.class, Trip.class);
    private final RepositoryIndex repositories =
        new RepositoryIndex(program, new ClassRepository(program));

    @Test
    void entityFor_springDataRepository_resolvesEntity() {
        assertThat(repositories.entityFor(REPO)).contains(TRIP);
    }

    @Test
    void entityFor_fragmentInterface_resolvesEntity() {
        assertThat(repositories.entityFor(FRAGMENT)).contains(TRIP);
    }

    @Test
    void entityFor_notARepository_returnsEmpty() {
        assertThat(repositories.entityFor("java/lang/String")).isEmpty();
    }

    @Test
    void accessKindOf_findPrefix_isRead() {
        assertThat(repositories.accessKindOf(new MethodRef(REPO, "findByTitle",
            "(Ljava/lang/String;)Ljava/util/Optional;"))).contains(AccessKind.READ);
    }

    @Test
    void accessKindOf_countPrefix_isRead() {
        assertThat(repositories.accessKindOf(new MethodRef(REPO, "countByTitle",
            "(Ljava/lang/String;)J"))).contains(AccessKind.READ);
    }

    @Test
    void accessKindOf_savePrefix_isWrite() {
        assertThat(repositories.accessKindOf(new MethodRef(REPO, "save",
            "(Ljava/lang/Object;)Ljava/lang/Object;"))).contains(AccessKind.WRITE);
    }

    @Test
    void accessKindOf_modifyingAnnotationBeatsReadPrefix_isWrite() {
        assertThat(repositories.accessKindOf(new MethodRef(REPO, "readAndRewrite",
            "(Ljava/lang/Long;Ljava/lang/String;)I"))).contains(AccessKind.WRITE);
    }

    @Test
    void accessKindOf_unknownPrefix_returnsEmptySoWalkerCanDescend() {
        assertThat(repositories.accessKindOf(new MethodRef(FRAGMENT, "upsert",
            "(Ljava/lang/Long;Ljava/lang/String;)V"))).isEmpty();
    }

    @Test
    void queryOf_methodWithQueryAnnotation_returnsQueryText() {
        assertThat(repositories.queryOf(new MethodRef(REPO, "findByLeg",
            "(Ljava/lang/Long;)Ljava/util/List;")))
            .contains("select t from Trip t join t.legs l where l.id = :legId");
    }

    @Test
    void queryOf_methodWithoutQueryAnnotation_returnsEmpty() {
        assertThat(repositories.queryOf(new MethodRef(REPO, "findByTitle",
            "(Ljava/lang/String;)Ljava/util/Optional;"))).isEmpty();
    }

    @Test
    void isNativeQuery_nativeQueryFlagSet_isTrue() {
        assertThat(repositories.isNativeQuery(new MethodRef(REPO, "findAllNative",
            "()Ljava/util/List;"))).isTrue();
    }

    @Test
    void isNativeQuery_jpqlQuery_isFalse() {
        assertThat(repositories.isNativeQuery(new MethodRef(REPO, "findByLeg",
            "(Ljava/lang/Long;)Ljava/util/List;"))).isFalse();
    }
}
```

`index/ListenerIndexTest.java`:

```java
package dev.toktokhan.invalidation.core.index;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.fixture.event.TripCompletedEvent;
import dev.toktokhan.invalidation.core.fixture.event.TripEventListeners;
import dev.toktokhan.invalidation.core.support.FakeProgramModel;
import org.junit.jupiter.api.Test;

class ListenerIndexTest {

    private final FakeProgramModel program = FakeProgramModel.create()
        .withEventListener(TripEventListeners.class, "onTripEvent")
        .withEventListener(TripEventListeners.class, "onString");
    private final ListenerIndex listeners =
        new ListenerIndex(new ClassRepository(program), program.eventListeners());

    @Test
    void listenersFor_listenerParameterIsSupertype_matches() {
        assertThat(listeners.listenersFor(MethodRefs.internalNameOf(TripCompletedEvent.class)))
            .anySatisfy(ref -> assertThat(ref.name()).isEqualTo("onTripEvent"));
    }

    @Test
    void listenersFor_unrelatedEventType_doesNotMatch() {
        assertThat(listeners.listenersFor(MethodRefs.internalNameOf(TripCompletedEvent.class)))
            .noneSatisfy(ref -> assertThat(ref.name()).isEqualTo("onString"));
    }

    @Test
    void listenersFor_eventWithNoListener_returnsEmpty() {
        assertThat(listeners.listenersFor("java/lang/Integer")).isEmpty();
    }

    @Test
    void listenersFor_methodNotAnnotated_isNotRegistered() {
        // notAListener 는 program 에 등록하지 않았으므로 어느 이벤트에도 걸리지 않습니다.
        assertThat(listeners.listenersFor(MethodRefs.internalNameOf(TripCompletedEvent.class)))
            .noneSatisfy(ref -> assertThat(ref.name()).isEqualTo("notAListener"));
    }
}
```

- [ ] **Step 2: 테스트를 실행해 실패를 확인한다**

```bash
./gradlew :invalidation-map-core:test --tests '*RepositoryIndexTest' --tests '*ListenerIndexTest' --console=plain
```

Expected: 컴파일 실패. `cannot find symbol: class RepositoryIndex`

- [ ] **Step 3: ProgramModel 과 FakeProgramModel 을 확장한다**

`ProgramModel.java` 의 `entityFor` javadoc 을 다음으로 바꾸고 `eventListeners()` 를 추가합니다.

```java
    /**
     * Spring Data 리포지토리 타입이 다루는 엔티티입니다. 리포지토리가 아니면 빈 값입니다.
     *
     * <p>구현은 **프래그먼트 인터페이스도 같은 엔티티로 등록해야 합니다.**
     * {@code SlotInstanceRepositoryCustom} 처럼 Spring Data 가 직접 리포지토리로 보지 않는
     * 인터페이스에 대고 호출이 일어나기 때문입니다.
     */
    Optional<String> entityFor(String repositoryInternalName);

    /**
     * {@code @EventListener} 또는 {@code @TransactionalEventListener} 가 붙은 메서드 전체입니다.
     *
     * <p>{@code publishEvent} 호출 지점에서 어떤 리스너로 이어붙일지 판단하려면 리스너를
     * 열거할 수 있어야 합니다. 런타임 구현은 빈 타입을 훑어 모으면 됩니다.
     */
    Set<MethodRef> eventListeners();
```

`FakeProgramModel` 에 다음을 추가합니다.

```java
    private final Set<MethodRef> eventListeners = new LinkedHashSet<>();

    public FakeProgramModel withEventListener(Class<?> type, String methodName) {
        eventListeners.add(MethodRefs.of(findMethod(type, methodName)));
        return this;
    }

    @Override
    public Set<MethodRef> eventListeners() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(eventListeners));
    }
```

- [ ] **Step 4: AccessKind, RepositoryIndex, ListenerIndex 를 구현한다**

`AccessKind.java`:

```java
package dev.toktokhan.invalidation.core;

/** 엔티티에 대한 접근 방향입니다. */
public enum AccessKind {
    READ,
    WRITE
}
```

`index/RepositoryIndex.java`:

```java
package dev.toktokhan.invalidation.core.index;

import dev.toktokhan.invalidation.core.AccessKind;
import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.ProgramModel;
import dev.toktokhan.invalidation.core.scan.AnnotationValues;
import dev.toktokhan.invalidation.core.scan.MethodFacts;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data 리포지토리에 대한 판정입니다. 엔티티 매핑과 접근 방향, {@code @Query} 문자열입니다.
 */
public final class RepositoryIndex {

    private static final String QUERY = "Lorg/springframework/data/jpa/repository/Query;";
    private static final String MODIFYING = "Lorg/springframework/data/jpa/repository/Modifying;";

    private static final List<String> WRITE_PREFIXES = List.of(
        "save", "delete", "remove", "insert", "update", "persist", "merge", "flush");
    private static final List<String> READ_PREFIXES = List.of(
        "find", "read", "get", "query", "search", "stream", "exists", "count");

    private final ProgramModel program;
    private final ClassRepository classes;

    public RepositoryIndex(ProgramModel program, ClassRepository classes) {
        this.program = program;
        this.classes = classes;
    }

    public Optional<String> entityFor(String repositoryInternalName) {
        return program.entityFor(repositoryInternalName);
    }

    /**
     * 접근 방향입니다. 판정할 수 없으면 빈 값입니다.
     *
     * <p>빈 값은 "모른다"는 뜻이며, 워커가 메서드 본문으로 내려가 실제 접근을 찾도록 합니다.
     * 임의로 한쪽을 고르면 쓰기를 놓치거나 과잉이 심해집니다.
     */
    public Optional<AccessKind> accessKindOf(MethodRef ref) {
        if (hasModifying(ref)) {
            return Optional.of(AccessKind.WRITE);
        }
        String name = ref.name();
        if (startsWithAny(name, WRITE_PREFIXES)) {
            return Optional.of(AccessKind.WRITE);
        }
        if (startsWithAny(name, READ_PREFIXES)) {
            return Optional.of(AccessKind.READ);
        }
        return Optional.empty();
    }

    public Optional<String> queryOf(MethodRef ref) {
        return queryAnnotation(ref).flatMap(values -> values.string("value"))
            .filter(text -> !text.isBlank());
    }

    public boolean isNativeQuery(MethodRef ref) {
        return queryAnnotation(ref).map(values -> values.bool("nativeQuery", false)).orElse(false);
    }

    private boolean hasModifying(MethodRef ref) {
        return classes.resolveMethod(ref).filter(facts -> facts.hasAnnotation(MODIFYING)).isPresent();
    }

    private Optional<AnnotationValues> queryAnnotation(MethodRef ref) {
        return classes.resolveMethod(ref)
            .map(MethodFacts::annotations)
            .map(annotations -> annotations.get(QUERY));
    }

    private static boolean startsWithAny(String name, List<String> prefixes) {
        return prefixes.stream().anyMatch(name::startsWith);
    }
}
```

`index/ListenerIndex.java`:

```java
package dev.toktokhan.invalidation.core.index;

import dev.toktokhan.invalidation.core.MethodRef;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 이벤트 타입에서 그것을 듣는 리스너 메서드를 찾습니다.
 *
 * <p>{@code publishEvent} 는 직접 호출이 아니므로 호출 사슬이 끊깁니다. 이 색인이 그 자리를
 * 이어붙입니다. 리스너 파라미터가 상위 타입인 경우를 반드시 처리해야 합니다.
 */
public final class ListenerIndex {

    private static final String EVENT_LISTENER =
        "Lorg/springframework/context/event/EventListener;";
    private static final String TRANSACTIONAL_EVENT_LISTENER =
        "Lorg/springframework/transaction/event/TransactionalEventListener;";

    private final ClassRepository classes;
    /** 리스너 메서드 -> 그 리스너가 받는 이벤트 타입들 */
    private final Map<MethodRef, Set<String>> acceptedTypes = new ConcurrentHashMap<>();
    private final Map<String, Set<MethodRef>> cache = new ConcurrentHashMap<>();

    public ListenerIndex(ClassRepository classes, Set<MethodRef> listeners) {
        this.classes = classes;
        for (MethodRef listener : listeners) {
            acceptedTypes.put(listener, acceptedTypesOf(listener));
        }
    }

    /** 이 이벤트 타입을 듣는 리스너입니다. 파라미터가 상위 타입인 경우도 포함합니다. */
    public Set<MethodRef> listenersFor(String eventInternalName) {
        return cache.computeIfAbsent(eventInternalName, event -> {
            Set<MethodRef> matched = new LinkedHashSet<>();
            acceptedTypes.forEach((listener, accepted) -> {
                for (String candidate : accepted) {
                    if (classes.isSubtypeOf(event, candidate)) {
                        matched.add(listener);
                        return;
                    }
                }
            });
            return Collections.unmodifiableSet(new LinkedHashSet<>(matched));
        });
    }

    /**
     * 리스너가 받는 이벤트 타입입니다.
     *
     * <p>어노테이션의 {@code classes} 속성이 있으면 그것을 씁니다. 없으면 첫 파라미터 타입입니다.
     */
    private Set<String> acceptedTypesOf(MethodRef listener) {
        Set<String> declared = new LinkedHashSet<>();
        classes.resolveMethod(listener).ifPresent(facts -> {
            declared.addAll(facts.annotation(EVENT_LISTENER).strings("classes"));
            declared.addAll(facts.annotation(TRANSACTIONAL_EVENT_LISTENER).strings("classes"));
            declared.addAll(facts.annotation(EVENT_LISTENER).strings("value"));
            declared.addAll(facts.annotation(TRANSACTIONAL_EVENT_LISTENER).strings("value"));
        });
        if (!declared.isEmpty()) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(declared));
        }
        return Collections.unmodifiableSet(
            new LinkedHashSet<>(firstParameterType(listener.descriptor())));
    }

    /** 디스크립터의 첫 파라미터가 객체 타입이면 그 internal name 을 돌려줍니다. */
    private static List<String> firstParameterType(String descriptor) {
        int start = descriptor.indexOf('(');
        int end = descriptor.indexOf(')');
        if (start < 0 || end < 0) {
            return List.of();
        }
        String parameters = descriptor.substring(start + 1, end);
        if (!parameters.startsWith("L")) {
            return List.of();
        }
        int semicolon = parameters.indexOf(';');
        return semicolon < 0 ? List.of() : List.of(parameters.substring(1, semicolon));
    }
}
```

- [ ] **Step 5: 테스트를 실행해 통과를 확인한다**

```bash
./gradlew :invalidation-map-core:test --console=plain
```

Expected: `BUILD SUCCESSFUL`, `RepositoryIndexTest` 12개와 `ListenerIndexTest` 4개 통과

- [ ] **Step 6: 커밋한다 (구현 먼저, 테스트 나중)**

```bash
git add invalidation-map-core/src/main
git commit -m "$(cat <<'MSG'
feat(core): RepositoryIndex 와 ListenerIndex 추가

리포지토리 접근 방향은 @Modifying 을 메서드명 접두어보다 먼저 봅니다. 둘 다
맞지 않으면 임의로 정하지 않고 빈 값을 돌려주어, 워커가 본문으로 내려가
실제 접근을 찾게 합니다.

ProgramModel 에 eventListeners() 를 추가했습니다. publishEvent 호출 지점에서
리스너로 이어붙이려면 리스너를 열거할 수 있어야 하고, 기존 다섯 메서드로는
불가능했습니다. 리스너 파라미터가 상위 타입인 경우를 할당 가능성으로 매칭합니다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
MSG
)"

git add invalidation-map-core/src/test
git commit -m "$(cat <<'MSG'
test(core): RepositoryIndex 와 ListenerIndex 단위 테스트 추가

@Modifying 이 read 접두어를 이기는지, 미지의 접두어가 빈 값을 돌려주는지,
프래그먼트 인터페이스가 엔티티로 풀리는지, 리스너 상위 타입 매칭이 되는지
확인합니다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
MSG
)"
```

---
## Task 5: CallGraphWalker — 호출 사슬 따라가기

**Files:**
- Modify: `.../core/index/ClassRepository.java` — 읽기 실패를 삼키고 기록하도록 변경
- Create: `.../core/walk/WalkState.java`, `WalkResult.java`, `WalkVisitor.java`, `CallGraphWalker.java`
- Test: `.../test/.../fixture/service/*.java`
- Test: `.../test/.../walk/CallGraphWalkerTest.java`

**Interfaces:**
- Consumes: Task 2 의 `ClassRepository`, Task 4 의 `ListenerIndex`, `ProgramModel.implementationsOf`
- Produces:
  - `record WalkState(MethodFacts caller, boolean inTransaction, boolean readOnlyTransaction)`
  - `record WalkResult(int visitedMethods, boolean budgetExceeded, List<String> unresolved)`
  - `interface WalkVisitor { void onCall(MethodRef callee, WalkState state); }`
  - `CallGraphWalker(ClassRepository, ProgramModel, ListenerIndex, List<String> basePackages, int nodeBudget)`
  - `WalkResult walk(MethodRef start, WalkVisitor visitor)`
  - `ClassRepository.unreadableClasses()` → `Map<String, String>` (internal name → 실패 사유)

**설계 판단 네 가지**

1. **인터페이스 여부를 판정하지 않고 합집합으로 내려갑니다.** `ClassFacts` 에는 `ACC_INTERFACE` 플래그가 없습니다. 플래그를 추가하는 대신, 호출 대상 자신과 `implementationsOf(owner)` 로 얻은 구현체를 **모두** 내려갈 대상으로 삼습니다. 구현체가 없는 일반 클래스에서는 `implementationsOf` 가 빈 집합을 돌려주므로 결과가 같고, `ClassFacts` 를 고치지 않아도 됩니다.

2. **이벤트 후보를 `ApplicationEvent` 하위로 걸러내지 않습니다.** Spring 4.2 부터 임의의 객체가 이벤트가 될 수 있어, `ApplicationEvent` 로 걸러내면 POJO 이벤트를 통째로 놓칩니다. 대신 `publishEvent` 를 호출하는 메서드 안에서 `NEW` 로 만든 **모든 타입**을 후보로 넘기고, `ListenerIndex.listenersFor` 가 실제로 그 타입을 듣는 리스너가 있을 때만 걸리게 합니다. 판정을 색인에 맡기는 쪽이 더 안전하고 코드도 짧습니다.

3. **`publishEvent` 는 메서드명으로만 매칭합니다.** 발행 지점의 owner 는 `ApplicationEventPublisher`, `ApplicationContext`, 또는 그 하위 타입일 수 있습니다. 이름이 `publishEvent` 인 다른 메서드를 잘못 잡을 위험은 무시할 수 있고, 잡아도 리스너가 없으면 아무 일이 없습니다.

4. **`readOnly` 억제는 사슬 전체가 읽기 전용일 때만 적용합니다.** 규칙은 다음 한 줄입니다.

   ```
   readOnly_new = @Transactional 선언이 있으면
                    (선언된 readOnly && (트랜잭션 밖이었거나 이미 readOnly))
                  없으면
                    들어온 readOnly
   ```

   이렇게 하면 두 방향 모두 안전합니다. 쓰기 트랜잭션 안의 `readOnly = true` 메서드는 억제되지 않습니다 — `REQUIRED` 전파로 기존 트랜잭션에 참여하면 Hibernate 가 그 힌트를 무시할 수 있어, 억제하면 쓰기를 놓칩니다. 반대로 읽기 전용 트랜잭션 안의 `readOnly = false` 메서드는 쓰기 가능으로 봅니다 — 과잉 방향이라 4.4 원칙에 맞습니다.

5. **클래스를 읽다 실패해도 워커가 멈추지 않습니다.** `ClassRepository.facts` 가 예외를 삼키고 사유를 기록합니다. ASM 이 모르는 클래스 파일 버전을 만나도 애플리케이션의 `/v3/api-docs` 가 통째로 실패하지 않습니다.

- [ ] **Step 1: 픽스처와 실패하는 테스트를 작성한다**

`fixture/service/`:

```java
package dev.toktokhan.invalidation.core.fixture.service;

/** 포트 인터페이스입니다. 본문이 없으므로 구현체로 확장해야 합니다. */
public interface TripPort {

    void store(String title);
}
```

```java
package dev.toktokhan.invalidation.core.fixture.service;

/** 워커가 여기까지 닿아야 합니다. */
public class TripPortAdapter implements TripPort {

    @Override
    public void store(String title) {
        deepest(title);
    }

    void deepest(String title) {
    }
}
```

```java
package dev.toktokhan.invalidation.core.fixture.service;

import dev.toktokhan.invalidation.core.fixture.event.TripCompletedEvent;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

public class TripService {

    private TripPort port;
    private ApplicationEventPublisher publisher;

    @Transactional
    public void write(String title) {
        port.store(title);
    }

    @Transactional(readOnly = true)
    public void readOnlyWrite(String title) {
        port.store(title);
    }

    /** 읽기 전용 안에서 쓰기 가능을 선언합니다. 쓰기 가능으로 봐야 합니다. */
    @Transactional(readOnly = true)
    public void readOnlyOuter(String title) {
        writableInner(title);
    }

    @Transactional(readOnly = false)
    public void writableInner(String title) {
        port.store(title);
    }

    /** 쓰기 트랜잭션 안의 읽기 전용 선언입니다. 억제되지 않아야 합니다. */
    @Transactional
    public void writableOuter(String title) {
        readOnlyInner(title);
    }

    @Transactional(readOnly = true)
    public void readOnlyInner(String title) {
        port.store(title);
    }

    /** 람다 본문 안의 호출도 따라가야 합니다. */
    public void insideLambda(List<String> titles) {
        titles.forEach(title -> port.store(title));
    }

    /** 이벤트를 발행합니다. 리스너로 이어붙여야 합니다. */
    @Transactional
    public void publish() {
        publisher.publishEvent(new TripCompletedEvent(this));
    }

    /** 순환 호출입니다. 종료해야 합니다. */
    public void loopA() {
        loopB();
    }

    public void loopB() {
        loopA();
    }

    /** 트랜잭션 밖입니다. */
    public void noTransaction(String title) {
        port.store(title);
    }
}
```

`walk/CallGraphWalkerTest.java`:

```java
package dev.toktokhan.invalidation.core.walk;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.fixture.event.TripEventListeners;
import dev.toktokhan.invalidation.core.fixture.service.TripPort;
import dev.toktokhan.invalidation.core.fixture.service.TripPortAdapter;
import dev.toktokhan.invalidation.core.fixture.service.TripService;
import dev.toktokhan.invalidation.core.index.ClassRepository;
import dev.toktokhan.invalidation.core.index.ListenerIndex;
import dev.toktokhan.invalidation.core.support.FakeProgramModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class CallGraphWalkerTest {

    private static final String BASE = "dev/toktokhan/invalidation/core/fixture";

    private final FakeProgramModel program = FakeProgramModel.create()
        .withImplementation(TripPort.class, TripPortAdapter.class)
        .withEventListener(TripEventListeners.class, "onTripEvent");
    private final ClassRepository classes = new ClassRepository(program);
    private final CallGraphWalker walker = new CallGraphWalker(
        classes, program, new ListenerIndex(classes, program.eventListeners()),
        List.of(BASE), 20_000);

    private final List<MethodRef> seen = new ArrayList<>();
    private final Map<MethodRef, WalkState> stateAt = new ConcurrentHashMap<>();
    private final WalkVisitor visitor = (callee, state) -> {
        seen.add(callee);
        stateAt.put(callee, state);
    };

    @Test
    void walk_callThroughInterface_reachesImplementationBody() {
        walker.walk(ref("write"), visitor);
        assertThat(seen).anySatisfy(callee -> {
            assertThat(callee.owner()).isEqualTo(MethodRefs.internalNameOf(TripPortAdapter.class));
            assertThat(callee.name()).isEqualTo("deepest");
        });
    }

    @Test
    void walk_transactionalMethod_marksInTransaction() {
        walker.walk(ref("write"), visitor);
        assertThat(stateAt.get(storeOnPort()).inTransaction()).isTrue();
    }

    @Test
    void walk_nonTransactionalMethod_marksOutsideTransaction() {
        walker.walk(ref("noTransaction"), visitor);
        assertThat(stateAt.get(storeOnPort()).inTransaction()).isFalse();
    }

    @Test
    void walk_readOnlyTransaction_marksReadOnly() {
        walker.walk(ref("readOnlyWrite"), visitor);
        assertThat(stateAt.get(storeOnPort()).readOnlyTransaction()).isTrue();
    }

    @Test
    void walk_writableInnerInsideReadOnlyOuter_marksWritable() {
        walker.walk(ref("readOnlyOuter"), visitor);
        assertThat(stateAt.get(storeOnPort()).readOnlyTransaction()).isFalse();
    }

    @Test
    void walk_readOnlyInnerInsideWritableOuter_staysWritable() {
        // REQUIRED 전파로 쓰기 트랜잭션에 참여하면 readOnly 힌트가 무시될 수 있으므로
        // 억제하지 않습니다. 억제하면 쓰기를 놓칩니다.
        walker.walk(ref("writableOuter"), visitor);
        assertThat(stateAt.get(storeOnPort()).readOnlyTransaction()).isFalse();
    }

    @Test
    void walk_callInsideLambda_isFollowed() {
        walker.walk(ref("insideLambda"), visitor);
        assertThat(seen).contains(storeOnPort());
    }

    @Test
    void walk_publishEvent_followsListenerBody() {
        walker.walk(ref("publish"), visitor);
        assertThat(seen).anySatisfy(callee ->
            assertThat(callee.owner()).isEqualTo(MethodRefs.internalNameOf(TripEventListeners.class)));
    }

    @Test
    void walk_recursiveCalls_terminates() {
        WalkResult result = walker.walk(ref("loopA"), visitor);
        assertThat(result.budgetExceeded()).isFalse();
        assertThat(result.visitedMethods()).isLessThan(10);
    }

    @Test
    void walk_budgetTooSmall_reportsBudgetExceeded() {
        CallGraphWalker tiny = new CallGraphWalker(
            classes, program, new ListenerIndex(classes, program.eventListeners()),
            List.of(BASE), 1);
        assertThat(tiny.walk(ref("write"), visitor).budgetExceeded()).isTrue();
    }

    @Test
    void walk_bodyOutsideBasePackages_isVisitedButNotDescended() {
        walker.walk(ref("insideLambda"), visitor);
        // JDK 호출은 방문 목록에 있지만 본문으로 내려가지 않습니다.
        assertThat(seen).anySatisfy(callee -> assertThat(callee.owner()).startsWith("java/"));
        assertThat(walker.walk(ref("insideLambda"), visitor).unresolved())
            .noneSatisfy(reason -> assertThat(reason).contains("java/util"));
    }

    private MethodRef ref(String methodName) {
        return program.ref(TripService.class, methodName);
    }

    private MethodRef storeOnPort() {
        return new MethodRef(MethodRefs.internalNameOf(TripPort.class), "store",
            "(Ljava/lang/String;)V");
    }
}
```

- [ ] **Step 2: 테스트를 실행해 실패를 확인한다**

```bash
./gradlew :invalidation-map-core:test --tests '*CallGraphWalkerTest' --console=plain
```

Expected: 컴파일 실패. `cannot find symbol: class CallGraphWalker`

- [ ] **Step 3: ClassRepository 가 읽기 실패를 삼키도록 바꾼다**

`ClassRepository` 에 필드와 메서드를 추가하고 `facts` 를 교체합니다.

```java
    private final Map<String, String> unreadable = new ConcurrentHashMap<>();

    /**
     * 클래스 바이트를 {@link ClassFacts} 로 바꿉니다.
     *
     * <p>읽기가 실패하면 예외를 다시 던지지 않고 사유를 기록한 뒤 빈 값을 돌려줍니다.
     * 이 라이브러리는 런타임에 도므로, ASM 이 모르는 클래스 파일 버전을 만나 예외를 던지면
     * 애플리케이션의 {@code /v3/api-docs} 가 통째로 실패합니다.
     */
    public Optional<ClassFacts> facts(String internalName) {
        return factsCache.computeIfAbsent(internalName, name -> {
            try {
                return program.classBytes(name).map(ClassFactsReader::read);
            } catch (RuntimeException e) {
                unreadable.put(name, e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
                return Optional.empty();
            }
        });
    }

    /** 읽지 못한 클래스와 그 사유입니다. 분석기가 미해결 사유로 옮겨 담습니다. */
    public Map<String, String> unreadableClasses() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(unreadable));
    }
```

- [ ] **Step 4: walk 패키지의 값 타입과 워커를 구현한다**

`walk/WalkState.java`:

```java
package dev.toktokhan.invalidation.core.walk;

import dev.toktokhan.invalidation.core.scan.MethodFacts;

/**
 * 호출 지점을 만난 순간의 상태입니다.
 *
 * @param caller              그 호출을 담고 있는 메서드. 문자열 상수를 보려면 필요합니다
 * @param inTransaction       {@code @Transactional} 경계 안인지
 * @param readOnlyTransaction 사슬 전체가 읽기 전용 트랜잭션인지
 */
public record WalkState(MethodFacts caller, boolean inTransaction, boolean readOnlyTransaction) {
}
```

`walk/WalkResult.java`:

```java
package dev.toktokhan.invalidation.core.walk;

import java.util.List;

public record WalkResult(int visitedMethods, boolean budgetExceeded, List<String> unresolved) {
}
```

`walk/WalkVisitor.java`:

```java
package dev.toktokhan.invalidation.core.walk;

import dev.toktokhan.invalidation.core.MethodRef;

/** 워커가 호출 지점을 하나 만날 때마다 부릅니다. */
public interface WalkVisitor {

    void onCall(MethodRef callee, WalkState state);
}
```

`walk/CallGraphWalker.java`:

```java
package dev.toktokhan.invalidation.core.walk;

import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.ProgramModel;
import dev.toktokhan.invalidation.core.index.ClassRepository;
import dev.toktokhan.invalidation.core.index.ListenerIndex;
import dev.toktokhan.invalidation.core.scan.AnnotationValues;
import dev.toktokhan.invalidation.core.scan.MethodFacts;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 핸들러 메서드에서 시작해 호출 사슬을 따라가며 만나는 호출 지점을 방문자에게 넘깁니다.
 *
 * <p>사슬이 끊기는 두 자리를 이어붙입니다. 인터페이스 호출은 구현체로, 이벤트 발행은
 * 리스너로 잇습니다.
 */
public final class CallGraphWalker {

    private static final String TRANSACTIONAL =
        "Lorg/springframework/transaction/annotation/Transactional;";
    private static final String PUBLISH_EVENT = "publishEvent";

    private final ClassRepository classes;
    private final ProgramModel program;
    private final ListenerIndex listeners;
    private final List<String> basePackages;
    private final int nodeBudget;

    public CallGraphWalker(ClassRepository classes, ProgramModel program, ListenerIndex listeners,
        List<String> basePackages, int nodeBudget) {
        this.classes = classes;
        this.program = program;
        this.listeners = listeners;
        this.basePackages = List.copyOf(basePackages);
        this.nodeBudget = nodeBudget;
    }

    public WalkResult walk(MethodRef start, WalkVisitor visitor) {
        Set<MethodRef> visited = new HashSet<>();
        List<String> unresolved = new ArrayList<>();
        Deque<Frame> pending = new ArrayDeque<>();
        pending.push(new Frame(start, false, false));
        boolean budgetExceeded = false;

        while (!pending.isEmpty()) {
            if (visited.size() >= nodeBudget) {
                budgetExceeded = true;
                break;
            }
            Frame frame = pending.pop();
            if (!visited.add(frame.ref())) {
                continue;
            }

            Optional<MethodFacts> maybeFacts = classes.resolveMethod(frame.ref());
            if (maybeFacts.isEmpty()) {
                if (inBasePackages(frame.ref().owner())) {
                    unresolved.add("본문을 읽을 수 없습니다: " + frame.ref());
                }
                continue;
            }
            MethodFacts facts = maybeFacts.get();
            WalkState state = stateFor(facts, frame);

            boolean publishesEvent = false;
            for (MethodRef callee : facts.calls()) {
                visitor.onCall(callee, state);
                if (callee.name().equals(PUBLISH_EVENT)) {
                    publishesEvent = true;
                }
                for (MethodRef target : descendTargets(callee)) {
                    pending.push(new Frame(target, state.inTransaction(), state.readOnlyTransaction()));
                }
            }

            for (MethodRef lambdaBody : facts.lambdaBodies()) {
                if (inBasePackages(lambdaBody.owner())) {
                    pending.push(new Frame(lambdaBody,
                        state.inTransaction(), state.readOnlyTransaction()));
                }
            }

            if (publishesEvent) {
                // 이 메서드 안에서 NEW 로 만든 모든 타입을 이벤트 후보로 봅니다.
                // ApplicationEvent 로 걸러내면 Spring 4.2 이후의 POJO 이벤트를 놓칩니다.
                for (String candidate : facts.newTypes()) {
                    for (MethodRef listener : listeners.listenersFor(candidate)) {
                        pending.push(new Frame(listener,
                            state.inTransaction(), state.readOnlyTransaction()));
                    }
                }
            }
        }

        return new WalkResult(visited.size(), budgetExceeded, List.copyOf(unresolved));
    }

    /**
     * 이 호출로 내려갈 대상입니다.
     *
     * <p>호출 대상 자신과 구현체를 모두 넣습니다. 인터페이스 여부를 판정하지 않아도 되는 이유는
     * 일반 클래스에서 {@code implementationsOf} 가 빈 집합을 돌려주기 때문입니다.
     */
    private Set<MethodRef> descendTargets(MethodRef callee) {
        if (!inBasePackages(callee.owner())) {
            return Set.of();
        }
        Set<MethodRef> targets = new LinkedHashSet<>();
        targets.add(callee);
        for (String implementation : program.implementationsOf(callee.owner())) {
            targets.add(new MethodRef(implementation, callee.name(), callee.descriptor()));
        }
        return targets;
    }

    /**
     * {@code @Transactional} 을 반영한 새 상태입니다. 메서드 어노테이션이 클래스보다 우선합니다.
     *
     * <p>{@code readOnly} 억제는 사슬 전체가 읽기 전용일 때만 적용합니다. 쓰기 트랜잭션 안의
     * {@code readOnly = true} 는 {@code REQUIRED} 전파로 무시될 수 있으므로 억제하지 않습니다.
     */
    private WalkState stateFor(MethodFacts facts, Frame frame) {
        Optional<AnnotationValues> transactional = transactionalOf(facts);
        if (transactional.isEmpty()) {
            return new WalkState(facts, frame.inTransaction(), frame.readOnly());
        }
        boolean declaredReadOnly = transactional.get().bool("readOnly", false);
        boolean readOnly = declaredReadOnly && (!frame.inTransaction() || frame.readOnly());
        return new WalkState(facts, true, readOnly);
    }

    private Optional<AnnotationValues> transactionalOf(MethodFacts facts) {
        if (facts.hasAnnotation(TRANSACTIONAL)) {
            return Optional.of(facts.annotation(TRANSACTIONAL));
        }
        return classes.facts(facts.ref().owner())
            .filter(classFacts -> classFacts.hasAnnotation(TRANSACTIONAL))
            .map(classFacts -> classFacts.annotation(TRANSACTIONAL));
    }

    private boolean inBasePackages(String internalName) {
        return basePackages.stream().anyMatch(internalName::startsWith);
    }

    private record Frame(MethodRef ref, boolean inTransaction, boolean readOnly) {
    }
}
```

- [ ] **Step 5: 테스트를 실행해 통과를 확인한다**

```bash
./gradlew :invalidation-map-core:test --console=plain
```

Expected: `BUILD SUCCESSFUL`, `CallGraphWalkerTest` 11개 통과

- [ ] **Step 6: 커밋한다 (구현 먼저, 테스트 나중)**

```bash
git add invalidation-map-core/src/main
git commit -m "$(cat <<'MSG'
feat(core): CallGraphWalker 로 핸들러부터 호출 사슬을 따라간다

사슬이 끊기는 두 자리를 이어붙입니다. 인터페이스 호출은 구현체로, 이벤트
발행은 리스너로 잇습니다. 이벤트 후보를 ApplicationEvent 하위로 걸러내지
않는 이유는 Spring 4.2 이후 임의의 객체가 이벤트가 될 수 있기 때문입니다.

readOnly 억제는 사슬 전체가 읽기 전용일 때만 적용합니다. 쓰기 트랜잭션 안의
readOnly=true 는 REQUIRED 전파로 무시될 수 있어 억제하면 쓰기를 놓칩니다.

ClassRepository 가 클래스 읽기 실패를 삼키고 기록하도록 바꿨습니다. 런타임
라이브러리이므로 예외가 /v3/api-docs 를 통째로 실패시키면 안 됩니다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
MSG
)"

git add invalidation-map-core/src/test
git commit -m "$(cat <<'MSG'
test(core): CallGraphWalker 단위 테스트 추가

인터페이스 확장, 트랜잭션 상태 전이 네 가지, 람다 본문, 이벤트 이어붙이기,
순환 종료, 예산 초과, 기준 패키지 밖 처리를 확인합니다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
MSG
)"
```

---
## Task 6: 리졸버 기반과 JpaRepositoryResolver

**Files:**
- Modify: `.../core/index/EntityIndex.java` — `entityByName`, `associationTargets(entity, fieldName)` 추가
- Create: `.../core/EntityAccess.java`
- Create: `.../core/resolve/EntityResolver.java`, `ResolutionContext.java`
- Create: `.../core/resolve/JpqlEntityExtractor.java`, `SqlTableExtractor.java`
- Create: `.../core/resolve/JpaRepositoryResolver.java`
- Test: `.../test/.../resolve/JpqlEntityExtractorTest.java`, `SqlTableExtractorTest.java`, `JpaRepositoryResolverTest.java`
- Test: `.../test/.../support/StubResolutionContext.java`

**Interfaces:**
- Consumes: Task 3 의 `EntityIndex`, Task 4 의 `RepositoryIndex`/`AccessKind`, Task 5 의 `WalkState`
- Produces:
  - `record EntityAccess(Set<String> entities, AccessKind kind)`
  - `interface EntityResolver { Optional<EntityAccess> resolve(MethodRef callee, ResolutionContext context); }`
  - `interface ResolutionContext { ClassRepository classes(); EntityIndex entities(); RepositoryIndex repositories(); WalkState state(); }`
  - `JpqlEntityExtractor.entities(String jpql, EntityIndex, ClassRepository)` → `Set<String>`
  - `JpqlEntityExtractor.kindOf(String jpql)` → `AccessKind`
  - `SqlTableExtractor.entities(String sql, EntityIndex)` → `Set<String>`
  - `SqlTableExtractor.kindOf(String sql)` → `AccessKind`
  - `JpaRepositoryResolver` (무상태, `EntityResolver` 구현)
  - `EntityIndex.entityByName(String jpqlEntityName)` → `Optional<String>`
  - `EntityIndex.associationTargets(String entity, String fieldName)` → `Set<String>`

**판정 규칙**

`JpaRepositoryResolver` 는 호출 대상 owner 가 리포지토리일 때만 동작합니다.

1. 리포지토리의 엔티티를 결과에 넣습니다.
2. `@Query` 가 있으면 그 문자열에서 **추가** 엔티티를 뽑습니다. JPQL 은 `JpqlEntityExtractor`,
   `nativeQuery = true` 면 `SqlTableExtractor` 로 보냅니다. 조인 대상이나 리포지토리 엔티티와
   다른 `FROM` 대상이 여기서 잡힙니다.
3. 접근 방향은 `RepositoryIndex.accessKindOf` 가 정합니다. 판정 불가이고 `@Query` 가 있으면
   쿼리문의 선행 키워드로 정합니다.
4. 접근 방향을 끝까지 정할 수 없으면 **빈 값을 돌려줍니다.** 워커가 그 메서드 본문으로 내려가
   실제 접근을 찾습니다. 본문에도 못 닿으면 분석기가 미해결로 남깁니다.

`JpqlEntityExtractor` 는 별칭 표를 만들어 연관 경로를 해석합니다.

```
"select t from Trip t join t.legs l where l.id = :id"
  1) FROM Trip t        -> alias t = Trip,  결과에 Trip
  2) JOIN t.legs l      -> t 는 Trip, Trip.legs 의 대상 엔티티 -> 결과에 TripLeg
```

JPQL 의 엔티티명은 기본이 단순 클래스명이지만 `@Entity(name = "...")` 로 바꿀 수 있습니다.
`EntityIndex.entityByName` 이 두 경우를 모두 색인합니다.

- [ ] **Step 1: 실패하는 테스트를 작성한다**

`support/StubResolutionContext.java`:

```java
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
```

`resolve/JpqlEntityExtractorTest.java`:

```java
package dev.toktokhan.invalidation.core.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.AccessKind;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.fixture.entity.Coordinate;
import dev.toktokhan.invalidation.core.fixture.entity.Trip;
import dev.toktokhan.invalidation.core.fixture.entity.TripLeg;
import dev.toktokhan.invalidation.core.index.ClassRepository;
import dev.toktokhan.invalidation.core.index.EntityIndex;
import dev.toktokhan.invalidation.core.support.FakeProgramModel;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JpqlEntityExtractorTest {

    private static final String TRIP = MethodRefs.internalNameOf(Trip.class);
    private static final String LEG = MethodRefs.internalNameOf(TripLeg.class);

    private final ClassRepository classes = new ClassRepository(FakeProgramModel.create());
    private final EntityIndex entities = new EntityIndex(classes,
        Set.of(TRIP, LEG, MethodRefs.internalNameOf(Coordinate.class)));

    @Test
    void entities_selectFrom_resolvesFromTarget() {
        assertThat(extract("select t from Trip t where t.id = :id")).containsExactly(TRIP);
    }

    @Test
    void entities_joinOnAssociationPath_resolvesJoinTarget() {
        assertThat(extract("select t from Trip t join t.legs l where l.id = :id"))
            .containsExactlyInAnyOrder(TRIP, LEG);
    }

    @Test
    void entities_updateStatement_resolvesTarget() {
        assertThat(extract("update Trip t set t.title = :title")).containsExactly(TRIP);
    }

    @Test
    void entities_deleteFrom_resolvesTarget() {
        assertThat(extract("delete from TripLeg l where l.id = :id")).containsExactly(LEG);
    }

    @Test
    void entities_unknownEntityName_isIgnored() {
        assertThat(extract("select x from Nowhere x")).isEmpty();
    }

    @Test
    void entities_unresolvableAssociationPath_isIgnored() {
        assertThat(extract("select t from Trip t join t.missing m")).containsExactly(TRIP);
    }

    @Test
    void kindOf_selectStatement_isRead() {
        assertThat(JpqlEntityExtractor.kindOf("  SELECT t FROM Trip t ")).isEqualTo(AccessKind.READ);
    }

    @Test
    void kindOf_updateStatement_isWrite() {
        assertThat(JpqlEntityExtractor.kindOf("update Trip t set t.title = :t"))
            .isEqualTo(AccessKind.WRITE);
    }

    @Test
    void kindOf_deleteStatement_isWrite() {
        assertThat(JpqlEntityExtractor.kindOf("DELETE FROM Trip t")).isEqualTo(AccessKind.WRITE);
    }

    private Set<String> extract(String jpql) {
        return JpqlEntityExtractor.entities(jpql, entities, classes);
    }
}
```

`resolve/SqlTableExtractorTest.java`:

```java
package dev.toktokhan.invalidation.core.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.AccessKind;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.fixture.entity.Trip;
import dev.toktokhan.invalidation.core.fixture.entity.TripLeg;
import dev.toktokhan.invalidation.core.index.ClassRepository;
import dev.toktokhan.invalidation.core.index.EntityIndex;
import dev.toktokhan.invalidation.core.support.FakeProgramModel;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SqlTableExtractorTest {

    private static final String TRIP = MethodRefs.internalNameOf(Trip.class);
    private static final String LEG = MethodRefs.internalNameOf(TripLeg.class);

    private final EntityIndex entities =
        new EntityIndex(new ClassRepository(FakeProgramModel.create()), Set.of(TRIP, LEG));

    @Test
    void entities_insertInto_resolvesExplicitTableName() {
        // Trip 은 @Table(name = "trip_log") 입니다.
        assertThat(SqlTableExtractor.entities(
            "INSERT INTO trip_log (title) VALUES (?) ON CONFLICT DO NOTHING", entities))
            .containsExactly(TRIP);
    }

    @Test
    void entities_selectFrom_resolvesSnakeCaseDefaultName() {
        // TripLeg 은 @Table 이 없으므로 snake_case 기본값으로 풀립니다.
        assertThat(SqlTableExtractor.entities("select * from trip_leg", entities))
            .containsExactly(LEG);
    }

    @Test
    void entities_joinedTables_resolvesAll() {
        assertThat(SqlTableExtractor.entities(
            "select * from trip_log t join trip_leg l on l.trip_id = t.id", entities))
            .containsExactlyInAnyOrder(TRIP, LEG);
    }

    @Test
    void entities_unknownTable_isIgnored() {
        assertThat(SqlTableExtractor.entities("select * from audit_log", entities)).isEmpty();
    }

    @Test
    void kindOf_insert_isWrite() {
        assertThat(SqlTableExtractor.kindOf("INSERT INTO t VALUES (1)")).isEqualTo(AccessKind.WRITE);
    }

    @Test
    void kindOf_select_isRead() {
        assertThat(SqlTableExtractor.kindOf(" select 1 ")).isEqualTo(AccessKind.READ);
    }
}
```

`resolve/JpaRepositoryResolverTest.java`:

```java
package dev.toktokhan.invalidation.core.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.AccessKind;
import dev.toktokhan.invalidation.core.EntityAccess;
import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.fixture.entity.Trip;
import dev.toktokhan.invalidation.core.fixture.entity.TripLeg;
import dev.toktokhan.invalidation.core.fixture.repo.TripJpaRepository;
import dev.toktokhan.invalidation.core.fixture.repo.TripRepositoryCustom;
import dev.toktokhan.invalidation.core.index.ClassRepository;
import dev.toktokhan.invalidation.core.index.EntityIndex;
import dev.toktokhan.invalidation.core.index.RepositoryIndex;
import dev.toktokhan.invalidation.core.support.FakeProgramModel;
import dev.toktokhan.invalidation.core.support.StubResolutionContext;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JpaRepositoryResolverTest {

    private static final String REPO = MethodRefs.internalNameOf(TripJpaRepository.class);
    private static final String FRAGMENT = MethodRefs.internalNameOf(TripRepositoryCustom.class);
    private static final String TRIP = MethodRefs.internalNameOf(Trip.class);
    private static final String LEG = MethodRefs.internalNameOf(TripLeg.class);

    private final FakeProgramModel program = FakeProgramModel.create()
        .withRepositoryEntity(TripJpaRepository.class, Trip.class)
        .withRepositoryEntity(TripRepositoryCustom.class, Trip.class)
        .withEntity(TripLeg.class);
    private final ClassRepository classes = new ClassRepository(program);
    private final EntityIndex entities = new EntityIndex(classes, Set.of(TRIP, LEG));
    private final RepositoryIndex repositories = new RepositoryIndex(program, classes);
    private final JpaRepositoryResolver resolver = new JpaRepositoryResolver();

    @Test
    void resolve_findMethod_reportsRepositoryEntityAsRead() {
        assertThat(resolve(new MethodRef(REPO, "findByTitle", "(Ljava/lang/String;)Ljava/util/Optional;")))
            .contains(new EntityAccess(Set.of(TRIP), AccessKind.READ));
    }

    @Test
    void resolve_saveMethod_reportsWrite() {
        assertThat(resolve(new MethodRef(REPO, "save", "(Ljava/lang/Object;)Ljava/lang/Object;")))
            .get()
            .satisfies(access -> assertThat(access.kind()).isEqualTo(AccessKind.WRITE));
    }

    @Test
    void resolve_queryWithJoin_addsJoinedEntity() {
        assertThat(resolve(new MethodRef(REPO, "findByLeg", "(Ljava/lang/Long;)Ljava/util/List;")))
            .get()
            .satisfies(access -> assertThat(access.entities()).containsExactlyInAnyOrder(TRIP, LEG));
    }

    @Test
    void resolve_modifyingQuery_isWrite() {
        assertThat(resolve(new MethodRef(REPO, "readAndRewrite",
            "(Ljava/lang/Long;Ljava/lang/String;)I")))
            .get()
            .satisfies(access -> assertThat(access.kind()).isEqualTo(AccessKind.WRITE));
    }

    @Test
    void resolve_nativeQuery_resolvesTableNames() {
        assertThat(resolve(new MethodRef(REPO, "findAllNative", "()Ljava/util/List;")))
            .get()
            .satisfies(access -> assertThat(access.entities()).contains(TRIP));
    }

    @Test
    void resolve_unknownPrefixWithoutQuery_returnsEmptySoWalkerCanDescend() {
        assertThat(resolve(new MethodRef(FRAGMENT, "upsert",
            "(Ljava/lang/Long;Ljava/lang/String;)V"))).isEmpty();
    }

    @Test
    void resolve_notARepository_returnsEmpty() {
        assertThat(resolve(new MethodRef("java/lang/String", "trim", "()Ljava/lang/String;"))).isEmpty();
    }

    private Optional<EntityAccess> resolve(MethodRef callee) {
        return resolver.resolve(callee, new StubResolutionContext(
            classes, entities, repositories, null));
    }
}
```

- [ ] **Step 2: 테스트를 실행해 실패를 확인한다**

```bash
./gradlew :invalidation-map-core:test --tests '*Extractor*' --tests '*JpaRepositoryResolverTest' --console=plain
```

Expected: 컴파일 실패. `cannot find symbol: class JpqlEntityExtractor`

- [ ] **Step 3: EntityIndex 에 이름 색인과 필드별 연관 조회를 추가한다**

`EntityIndex` 에 상수와 필드, 메서드를 추가하고 `associationsOf` 를 새 메서드로 재구성합니다.

```java
    private static final String ENTITY = "Ljakarta/persistence/Entity;";

    /** JPQL 엔티티명 -> internal name. @Entity(name=) 값과 단순 클래스명을 모두 색인합니다. */
    private final Map<String, String> entityByJpqlName;
```

생성자 마지막에 `this.entityByJpqlName = buildNameIndex();` 를 추가하고 다음 메서드를 넣습니다.

```java
    /** JPQL 이 쓰는 엔티티명으로 엔티티를 찾습니다. */
    public Optional<String> entityByName(String jpqlEntityName) {
        return Optional.ofNullable(entityByJpqlName.get(jpqlEntityName));
    }

    /** 이 엔티티의 특정 필드가 가리키는 연관 대상 엔티티입니다. */
    public Set<String> associationTargets(String entityInternalName, String fieldName) {
        List<String> hierarchy = new ArrayList<>();
        hierarchy.add(entityInternalName);
        hierarchy.addAll(classes.supertypesOf(entityInternalName));
        for (String current : hierarchy) {
            Optional<Set<String>> found = classes.facts(current)
                .flatMap(facts -> facts.fields().stream()
                    .filter(field -> field.name().equals(fieldName))
                    .findFirst())
                .map(this::associationTargets)
                .filter(targets -> !targets.isEmpty());
            if (found.isPresent()) {
                return found.get();
            }
        }
        return Set.of();
    }

    private Map<String, String> buildNameIndex() {
        Map<String, String> index = new LinkedHashMap<>();
        for (String entity : entities) {
            index.putIfAbsent(MethodRefs.simpleNameOf(entity), entity);
            classes.facts(entity)
                .map(facts -> facts.annotation(ENTITY))
                .flatMap(values -> values.string("name"))
                .filter(name -> !name.isBlank())
                .ifPresent(name -> index.put(name, entity));
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(index));
    }
```

`associationTargets(FieldFacts)` 는 이미 있는 private 메서드를 그대로 씁니다. 접근 제어자만
`private` 에서 유지하고, 위 새 메서드가 같은 클래스 안에서 호출합니다.

- [ ] **Step 4: EntityAccess, EntityResolver, ResolutionContext 를 구현한다**

```java
package dev.toktokhan.invalidation.core;

import java.util.Set;

/**
 * 호출 지점 하나가 만들어내는 엔티티 접근입니다.
 *
 * @param entities internal name 집합
 * @param kind     이 호출의 접근 방향
 */
public record EntityAccess(Set<String> entities, AccessKind kind) {

    public static EntityAccess of(String entity, AccessKind kind) {
        return new EntityAccess(Set.of(entity), kind);
    }
}
```

```java
package dev.toktokhan.invalidation.core.resolve;

import dev.toktokhan.invalidation.core.EntityAccess;
import dev.toktokhan.invalidation.core.MethodRef;
import java.util.Optional;

/**
 * 호출 지점을 엔티티 접근으로 바꿉니다.
 *
 * <p>빈 값은 "이 호출은 내 담당이 아니다" 또는 "판정할 수 없다"는 뜻입니다. 어느 쪽이든
 * 워커가 그 메서드 본문으로 내려가 실제 접근을 찾습니다. 임의로 방향을 정하지 않습니다.
 */
public interface EntityResolver {

    Optional<EntityAccess> resolve(MethodRef callee, ResolutionContext context);
}
```

```java
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
```

- [ ] **Step 5: JpqlEntityExtractor 와 SqlTableExtractor 를 구현한다**

`resolve/JpqlEntityExtractor.java`:

```java
package dev.toktokhan.invalidation.core.resolve;

import dev.toktokhan.invalidation.core.AccessKind;
import dev.toktokhan.invalidation.core.index.ClassRepository;
import dev.toktokhan.invalidation.core.index.EntityIndex;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * JPQL 문자열에서 접근 대상 엔티티를 뽑습니다.
 *
 * <p>{@code FROM} 과 {@code UPDATE} 대상은 엔티티명으로, {@code JOIN} 대상은 별칭 표를 통해
 * 연관 경로로 해석합니다. 해석하지 못한 토큰은 버립니다. 리포지토리 제네릭으로 기본 엔티티는
 * 이미 확보되므로 완전 누락이 되지 않습니다.
 */
public final class JpqlEntityExtractor {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$.]*");

    private JpqlEntityExtractor() {
    }

    public static AccessKind kindOf(String jpql) {
        String head = firstWord(jpql);
        return switch (head) {
            case "update", "delete", "insert" -> AccessKind.WRITE;
            default -> AccessKind.READ;
        };
    }

    public static Set<String> entities(String jpql, EntityIndex entities, ClassRepository classes) {
        List<String> tokens = tokenize(jpql);
        Set<String> found = new LinkedHashSet<>();
        Map<String, String> aliasToEntity = new LinkedHashMap<>();

        for (int i = 0; i < tokens.size(); i++) {
            String keyword = tokens.get(i).toLowerCase(Locale.ROOT);
            boolean isTarget = keyword.equals("from") || keyword.equals("update")
                || keyword.equals("join");
            if (!isTarget || i + 1 >= tokens.size()) {
                continue;
            }
            String target = tokens.get(i + 1);
            String alias = aliasAfter(tokens, i + 2);

            if (target.contains(".")) {
                // JOIN t.legs l — 별칭 표에서 소유 엔티티를 찾아 필드 타입으로 해석합니다.
                int dot = target.indexOf('.');
                String owner = aliasToEntity.get(target.substring(0, dot));
                if (owner == null) {
                    continue;
                }
                Set<String> targets = entities.associationTargets(owner, target.substring(dot + 1));
                found.addAll(targets);
                if (alias != null && targets.size() == 1) {
                    aliasToEntity.put(alias, targets.iterator().next());
                }
                continue;
            }

            entities.entityByName(target).ifPresent(entity -> {
                found.add(entity);
                if (alias != null) {
                    aliasToEntity.put(alias, entity);
                }
            });
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(found));
    }

    /** 대상 뒤에 오는 토큰이 예약어가 아니면 별칭으로 봅니다. {@code AS} 는 건너뜁니다. */
    private static String aliasAfter(List<String> tokens, int index) {
        if (index >= tokens.size()) {
            return null;
        }
        String candidate = tokens.get(index);
        if (candidate.equalsIgnoreCase("as")) {
            return index + 1 < tokens.size() ? tokens.get(index + 1) : null;
        }
        return RESERVED.contains(candidate.toLowerCase(Locale.ROOT)) ? null : candidate;
    }

    private static final Set<String> RESERVED = Set.of(
        "select", "from", "where", "join", "left", "right", "inner", "outer", "fetch",
        "update", "delete", "insert", "set", "on", "group", "order", "by", "having", "and", "or");

    private static List<String> tokenize(String text) {
        return TOKEN.matcher(text == null ? "" : text).results()
            .map(match -> match.group())
            .toList();
    }

    private static String firstWord(String text) {
        List<String> tokens = tokenize(text);
        return tokens.isEmpty() ? "" : tokens.get(0).toLowerCase(Locale.ROOT);
    }
}
```

`resolve/SqlTableExtractor.java`:

```java
package dev.toktokhan.invalidation.core.resolve;

import dev.toktokhan.invalidation.core.AccessKind;
import dev.toktokhan.invalidation.core.index.EntityIndex;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 네이티브 SQL 문자열에서 테이블명을 뽑아 엔티티로 역매핑합니다.
 *
 * <p>{@code FROM}, {@code INTO}, {@code UPDATE}, {@code JOIN} 뒤의 토큰을 테이블명 후보로 보고
 * {@link EntityIndex#entityForTable(String)} 에 물어봅니다. 풀리지 않은 후보는 버립니다.
 */
public final class SqlTableExtractor {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z_][A-Za-z0-9_.\"`]*");
    private static final Set<String> TABLE_KEYWORDS = Set.of("from", "into", "update", "join");

    private SqlTableExtractor() {
    }

    public static AccessKind kindOf(String sql) {
        List<String> tokens = tokenize(sql);
        String head = tokens.isEmpty() ? "" : tokens.get(0).toLowerCase(Locale.ROOT);
        return switch (head) {
            case "insert", "update", "delete", "merge", "upsert", "replace", "truncate" ->
                AccessKind.WRITE;
            default -> AccessKind.READ;
        };
    }

    public static Set<String> entities(String sql, EntityIndex entities) {
        List<String> tokens = tokenize(sql);
        Set<String> found = new LinkedHashSet<>();
        for (int i = 0; i < tokens.size() - 1; i++) {
            if (!TABLE_KEYWORDS.contains(tokens.get(i).toLowerCase(Locale.ROOT))) {
                continue;
            }
            entities.entityForTable(unquote(tokens.get(i + 1))).ifPresent(found::add);
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(found));
    }

    /** 스키마 접두어와 인용 부호를 떼어 냅니다. {@code "public"."trip_log"} -> {@code trip_log} */
    private static String unquote(String token) {
        String cleaned = token.replace("\"", "").replace("`", "");
        int dot = cleaned.lastIndexOf('.');
        return dot < 0 ? cleaned : cleaned.substring(dot + 1);
    }

    private static List<String> tokenize(String text) {
        return TOKEN.matcher(text == null ? "" : text).results()
            .map(match -> match.group())
            .toList();
    }
}
```

- [ ] **Step 6: JpaRepositoryResolver 를 구현한다**

```java
package dev.toktokhan.invalidation.core.resolve;

import dev.toktokhan.invalidation.core.AccessKind;
import dev.toktokhan.invalidation.core.EntityAccess;
import dev.toktokhan.invalidation.core.MethodRef;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Spring Data 리포지토리 호출을 엔티티 접근으로 바꿉니다. 설계 문서 4.2절의 1~2번입니다.
 */
public final class JpaRepositoryResolver implements EntityResolver {

    @Override
    public Optional<EntityAccess> resolve(MethodRef callee, ResolutionContext context) {
        Optional<String> repositoryEntity = context.repositories().entityFor(callee.owner());
        if (repositoryEntity.isEmpty()) {
            return Optional.empty();
        }

        Set<String> found = new LinkedHashSet<>();
        found.add(repositoryEntity.get());

        Optional<String> query = context.repositories().queryOf(callee);
        Optional<AccessKind> declaredKind = context.repositories().accessKindOf(callee);

        if (query.isEmpty()) {
            // 방향을 정할 수 없으면 미루고, 워커가 본문으로 내려가게 합니다.
            return declaredKind.map(kind -> new EntityAccess(Collections.unmodifiableSet(new LinkedHashSet<>(found)), kind));
        }

        String queryText = query.get();
        AccessKind kind;
        if (context.repositories().isNativeQuery(callee)) {
            found.addAll(SqlTableExtractor.entities(queryText, context.entities()));
            kind = declaredKind.orElseGet(() -> SqlTableExtractor.kindOf(queryText));
        } else {
            found.addAll(JpqlEntityExtractor.entities(queryText, context.entities(), context.classes()));
            kind = declaredKind.orElseGet(() -> JpqlEntityExtractor.kindOf(queryText));
        }
        return Optional.of(new EntityAccess(Collections.unmodifiableSet(new LinkedHashSet<>(found)), kind));
    }
}
```

- [ ] **Step 7: 테스트를 실행해 통과를 확인한다**

```bash
./gradlew :invalidation-map-core:test --console=plain
```

Expected: `BUILD SUCCESSFUL`, 세 테스트 클래스 22개 통과

- [ ] **Step 8: 커밋한다 (구현 먼저, 테스트 나중)**

```bash
git add invalidation-map-core/src/main
git commit -m "$(cat <<'MSG'
feat(core): 리졸버 기반과 JpaRepositoryResolver 추가

리포지토리 호출에서 제네릭 엔티티를 얻고, @Query 문자열에서 조인 대상 같은
추가 엔티티를 뽑습니다. JPQL 은 별칭 표로 연관 경로를 해석하고, 네이티브
SQL 은 테이블명을 엔티티로 역매핑합니다.

방향을 끝까지 정할 수 없으면 임의로 정하지 않고 빈 값을 돌려줍니다. 워커가
본문으로 내려가 실제 접근을 찾습니다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
MSG
)"

git add invalidation-map-core/src/test
git commit -m "$(cat <<'MSG'
test(core): JPQL·SQL 추출기와 JpaRepositoryResolver 단위 테스트 추가

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
MSG
)"
```

---

## Task 7: 나머지 리졸버 세 개

**Files:**
- Create: `.../core/resolve/EntityManagerResolver.java`, `QuerydslResolver.java`, `DirtyCheckResolver.java`
- Test: `.../test/.../fixture/repo/EntityManagerRepository.java`, `.../fixture/repo/QuerydslRepository.java`
- Test: `.../test/.../resolve/EntityManagerResolverTest.java`, `QuerydslResolverTest.java`, `DirtyCheckResolverTest.java`

**Interfaces:**
- Consumes: Task 6 의 `EntityResolver`, `ResolutionContext`, 두 추출기. Task 3 의 `EntityIndex.isMutator`
- Produces: `EntityManagerResolver`, `QuerydslResolver`, `DirtyCheckResolver` (모두 무상태 `EntityResolver` 구현)

**판정 규칙**

`EntityManagerResolver` — 설계 문서 4.2절 3번.

| 호출 | 방향 | 엔티티 출처 |
| --- | --- | --- |
| `persist` `merge` `remove` | WRITE | 첫 파라미터의 정적 타입이 엔티티면 그것 |
| `find` `getReference` | READ | 첫 파라미터가 `Class` 리터럴일 때만 알 수 있어 판정하지 않음 |
| `createQuery` | 문자열의 선행 키워드 | `JpqlEntityExtractor` |
| `createNativeQuery` | 문자열의 선행 키워드 | `SqlTableExtractor` |

`createQuery` / `createNativeQuery` 의 문자열은 호출 지점의 인자입니다. 바이트코드에서 인자를
스택 추적 없이 정확히 집을 수는 없으므로, **호출을 담은 메서드의 문자열 상수 전체**를
후보로 넘깁니다. `WalkState.caller().stringConstants()` 가 그 목록입니다. 한 메서드가 쿼리
문자열을 두 개 담고 있으면 둘 다 반영되어 과잉이 됩니다. 4.4 원칙에 맞는 방향입니다.

`persist` / `merge` / `remove` 는 디스크립터가 `(Ljava/lang/Object;)V` 라 파라미터 타입에서
엔티티를 알 수 없습니다. 그래서 이 호출은 **엔티티를 특정하지 않고 미해결로 남깁니다** —
빈 값을 돌려주면 분석기가 미해결 사유를 붙입니다. 다만 대부분의 코드가
`repository.save()` 를 쓰므로 실제로 걸리는 일이 드뭅니다.

`QuerydslResolver` — 설계 문서 4.2절 4번. 호출 지점의 owner 나 인자 타입이 아니라,
**호출을 담은 메서드가 참조한 Q클래스**로 판정합니다. `MethodFacts.calls()` 와 `newTypes()` 에
나타난 타입 중 `EntityPathBase<T>` 를 상속한 것을 찾아 `T` 를 엔티티로 씁니다.
`ConstructorExpression<T>` 를 상속한 DTO 프로젝션 Q클래스는 상위 타입이 다르므로 자동으로
걸러집니다. 방향은 QueryDSL 진입 메서드명으로 정합니다 — `update` / `delete` 는 WRITE,
그 밖(`selectFrom`, `select`, `from`)은 READ 입니다.

`DirtyCheckResolver` — 설계 문서 4.2절 5번. `EntityIndex.isMutator(callee)` 가 참이고
`state().inTransaction()` 이 참이고 `state().readOnlyTransaction()` 이 거짓일 때만 WRITE 를
돌려줍니다. 쓰이는 엔티티는 `callee.owner()` 입니다.

- [ ] **Step 1: 픽스처와 실패하는 테스트를 작성한다**

`fixture/repo/EntityManagerRepository.java`:

```java
package dev.toktokhan.invalidation.core.fixture.repo;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

public class EntityManagerRepository {

    @PersistenceContext
    private EntityManager em;

    public void upsert(Long id, String title) {
        em.createNativeQuery(
                "INSERT INTO trip_log (id, title) VALUES (?, ?) ON CONFLICT DO NOTHING")
            .setParameter(1, id)
            .setParameter(2, title)
            .executeUpdate();
    }

    public void bulkRename(String title) {
        em.createQuery("update Trip t set t.title = :title")
            .setParameter("title", title)
            .executeUpdate();
    }

    public Object load(Long id) {
        return em.createQuery("select t from Trip t where t.id = :id")
            .setParameter("id", id)
            .getSingleResult();
    }
}
```

`fixture/repo/QuerydslRepository.java`:

```java
package dev.toktokhan.invalidation.core.fixture.repo;

import com.querydsl.core.types.dsl.EntityPathBase;
import com.querydsl.core.types.dsl.ConstructorExpression;
import dev.toktokhan.invalidation.core.fixture.entity.Trip;

public class QuerydslRepository {

    /** 엔티티 Q클래스를 흉내 낸 픽스처입니다. EntityPathBase<Trip> 를 상속합니다. */
    public static class QTrip extends EntityPathBase<Trip> {

        public QTrip() {
            super(Trip.class, "trip");
        }
    }

    /** DTO 프로젝션 Q클래스를 흉내 낸 픽스처입니다. 걸러져야 합니다. */
    public static class QTripTitle extends ConstructorExpression<String> {

        public QTripTitle() {
            super(String.class, new Class<?>[0]);
        }
    }

    public Object selectFrom() {
        QTrip trip = new QTrip();
        return trip.toString();
    }

    public Object projection() {
        QTripTitle title = new QTripTitle();
        return title.toString();
    }
}
```

**주의:** `QTrip` 과 `QTripTitle` 은 `EntityPathBase` / `ConstructorExpression` 의 실제 생성자
시그니처를 만족해야 합니다. 컴파일이 안 되면 QueryDSL 5.0.0 의 해당 생성자를 확인해
맞추십시오. 검증 대상은 **상위 타입의 제네릭 인자를 읽는 것**이지 QueryDSL 동작이 아닙니다.

`resolve/EntityManagerResolverTest.java`, `QuerydslResolverTest.java`, `DirtyCheckResolverTest.java`
는 아래 표의 케이스를 각각 `StubResolutionContext` 로 검증합니다. `WalkState` 의 `caller` 에는
`ClassRepository.methodFacts(...)` 로 얻은 픽스처 메서드의 `MethodFacts` 를 넣습니다.

| 테스트 | 대상 | 기대 |
| --- | --- | --- |
| `resolve_createNativeQueryInsert_isWriteOnMappedEntity` | `EntityManagerRepository.upsert` 안의 `createNativeQuery` | `{Trip}`, WRITE |
| `resolve_createQueryUpdate_isWrite` | `bulkRename` 안의 `createQuery` | `{Trip}`, WRITE |
| `resolve_createQuerySelect_isRead` | `load` 안의 `createQuery` | `{Trip}`, READ |
| `resolve_persistWithObjectDescriptor_returnsEmpty` | `EntityManager.persist` | 빈 값 |
| `resolve_notEntityManager_returnsEmpty` | `String.trim` | 빈 값 |
| `resolve_callerReferencesEntityQClass_reportsEntity` | `QuerydslRepository.selectFrom` 안의 임의 호출 | `{Trip}`, READ |
| `resolve_callerReferencesProjectionQClass_returnsEmpty` | `QuerydslRepository.projection` | 빈 값 |
| `resolve_mutatorInWritableTransaction_isWrite` | `Trip.rename` / `inTransaction=true, readOnly=false` | `{Trip}`, WRITE |
| `resolve_mutatorInReadOnlyTransaction_returnsEmpty` | `Trip.rename` / `readOnly=true` | 빈 값 |
| `resolve_mutatorOutsideTransaction_returnsEmpty` | `Trip.rename` / `inTransaction=false` | 빈 값 |
| `resolve_nonMutatorInTransaction_returnsEmpty` | `Trip.title` / `inTransaction=true` | 빈 값 |

- [ ] **Step 2: 테스트를 실행해 실패를 확인한다**

```bash
./gradlew :invalidation-map-core:test --tests '*EntityManagerResolverTest' --tests '*QuerydslResolverTest' --tests '*DirtyCheckResolverTest' --console=plain
```

Expected: 컴파일 실패. `cannot find symbol: class EntityManagerResolver`

- [ ] **Step 3: EntityManagerResolver 를 구현한다**

```java
package dev.toktokhan.invalidation.core.resolve;

import dev.toktokhan.invalidation.core.AccessKind;
import dev.toktokhan.invalidation.core.EntityAccess;
import dev.toktokhan.invalidation.core.MethodRef;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * {@code EntityManager} 호출을 엔티티 접근으로 바꿉니다. 설계 문서 4.2절 3번입니다.
 *
 * <p>쿼리 문자열은 호출 인자이지만 바이트코드에서 스택을 추적하지 않으므로, 호출을 담은
 * 메서드의 문자열 상수 전체를 후보로 씁니다. 한 메서드에 쿼리가 여러 개면 모두 반영되어
 * 과잉이 됩니다. 오차 방향 원칙에 맞습니다.
 */
public final class EntityManagerResolver implements EntityResolver {

    private static final String ENTITY_MANAGER = "jakarta/persistence/EntityManager";

    @Override
    public Optional<EntityAccess> resolve(MethodRef callee, ResolutionContext context) {
        if (!isEntityManager(callee.owner(), context)) {
            return Optional.empty();
        }
        if (context.state() == null || context.state().caller() == null) {
            return Optional.empty();
        }

        boolean nativeQuery = callee.name().equals("createNativeQuery");
        boolean jpql = callee.name().equals("createQuery");
        if (!nativeQuery && !jpql) {
            // persist / merge / remove 는 디스크립터가 Object 라 엔티티를 특정할 수 없습니다.
            // find / getReference 도 Class 리터럴을 스택에서 읽어야 하므로 판정하지 않습니다.
            return Optional.empty();
        }

        Set<String> found = new LinkedHashSet<>();
        AccessKind kind = AccessKind.READ;
        boolean matched = false;
        for (String candidate : context.state().caller().stringConstants()) {
            Set<String> resolved = nativeQuery
                ? SqlTableExtractor.entities(candidate, context.entities())
                : JpqlEntityExtractor.entities(candidate, context.entities(), context.classes());
            if (resolved.isEmpty()) {
                continue;
            }
            matched = true;
            found.addAll(resolved);
            AccessKind candidateKind = nativeQuery
                ? SqlTableExtractor.kindOf(candidate)
                : JpqlEntityExtractor.kindOf(candidate);
            if (candidateKind == AccessKind.WRITE) {
                kind = AccessKind.WRITE;
            }
        }
        return matched ? Optional.of(new EntityAccess(Collections.unmodifiableSet(new LinkedHashSet<>(found)), kind)) : Optional.empty();
    }

    private static boolean isEntityManager(String owner, ResolutionContext context) {
        return owner.equals(ENTITY_MANAGER) || context.classes().isSubtypeOf(owner, ENTITY_MANAGER);
    }
}
```

- [ ] **Step 4: QuerydslResolver 를 구현한다**

```java
package dev.toktokhan.invalidation.core.resolve;

import dev.toktokhan.invalidation.core.AccessKind;
import dev.toktokhan.invalidation.core.EntityAccess;
import dev.toktokhan.invalidation.core.MethodRef;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * QueryDSL 사용 지점을 엔티티 접근으로 바꿉니다. 설계 문서 4.2절 4번입니다.
 *
 * <p>호출을 담은 메서드가 참조한 Q클래스에서 엔티티를 얻습니다. 엔티티 Q클래스는
 * {@code EntityPathBase<T>} 를 상속하고 DTO 프로젝션 Q클래스는
 * {@code ConstructorExpression<T>} 를 상속하므로, 상위 타입으로 구분되어 프로젝션은
 * 자동으로 걸러집니다.
 */
public final class QuerydslResolver implements EntityResolver {

    private static final String ENTITY_PATH_BASE = "com/querydsl/core/types/dsl/EntityPathBase";
    private static final Set<String> WRITE_ENTRY_POINTS = Set.of("update", "delete");

    @Override
    public Optional<EntityAccess> resolve(MethodRef callee, ResolutionContext context) {
        if (context.state() == null || context.state().caller() == null) {
            return Optional.empty();
        }
        Set<String> referencedTypes = new LinkedHashSet<>(context.state().caller().newTypes());
        context.state().caller().calls().forEach(call -> referencedTypes.add(call.owner()));

        Set<String> found = new LinkedHashSet<>();
        for (String type : referencedTypes) {
            context.classes().typeArgumentOfSupertype(type, ENTITY_PATH_BASE)
                .filter(context.entities()::isEntity)
                .ifPresent(found::add);
        }
        if (found.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new EntityAccess(Collections.unmodifiableSet(new LinkedHashSet<>(found)), kindOf(callee)));
    }

    /** QueryDSL 진입점이 update / delete 가 아니면 읽기입니다. */
    private static AccessKind kindOf(MethodRef callee) {
        return WRITE_ENTRY_POINTS.contains(callee.name()) ? AccessKind.WRITE : AccessKind.READ;
    }
}
```

**주의:** QueryDSL 진입점을 읽기로 잘못 판정해 쓰기를 놓치는 경로는 `DirtyCheckResolver` 와
`JpaRepositoryResolver` 가 따로 잡습니다.

- [ ] **Step 5: DirtyCheckResolver 를 구현한다**

```java
package dev.toktokhan.invalidation.core.resolve;

import dev.toktokhan.invalidation.core.AccessKind;
import dev.toktokhan.invalidation.core.EntityAccess;
import dev.toktokhan.invalidation.core.MethodRef;
import java.util.Optional;

/**
 * 엔티티 변경자 호출을 쓰기로 바꿉니다. 설계 문서 4.2절 5번, 4.3절입니다.
 *
 * <p>{@code save()} 를 명시적으로 부르지 않고 {@code @Transactional} 안에서 엔티티 메서드만
 * 호출하는 코드를 잡습니다. 이 판정이 없으면 그런 프로젝트에서 쓰기가 통째로 누락됩니다.
 */
public final class DirtyCheckResolver implements EntityResolver {

    @Override
    public Optional<EntityAccess> resolve(MethodRef callee, ResolutionContext context) {
        if (context.state() == null) {
            return Optional.empty();
        }
        if (!context.state().inTransaction() || context.state().readOnlyTransaction()) {
            return Optional.empty();
        }
        if (!context.entities().isMutator(callee)) {
            return Optional.empty();
        }
        return Optional.of(EntityAccess.of(callee.owner(), AccessKind.WRITE));
    }
}
```

- [ ] **Step 6: 테스트를 실행해 통과를 확인한다**

```bash
./gradlew :invalidation-map-core:test --console=plain
```

Expected: `BUILD SUCCESSFUL`, 표의 11개 케이스 통과

- [ ] **Step 7: 커밋한다 (구현 먼저, 테스트 나중)**

```bash
git add invalidation-map-core/src/main
git commit -m "$(cat <<'MSG'
feat(core): EntityManager·QueryDSL·더티체킹 리졸버 추가

QueryDSL 은 호출을 담은 메서드가 참조한 Q클래스에서 엔티티를 얻습니다.
EntityPathBase<T> 상속 여부로 판정하므로 ConstructorExpression 을 상속한 DTO
프로젝션 Q클래스는 자동으로 걸러집니다.

더티체킹 리졸버는 쓰기 가능 트랜잭션 안의 변경자 호출만 쓰기로 봅니다.
save() 명시 호출에 의존하지 않는 프로젝트를 덮기 위한 판정입니다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
MSG
)"

git add invalidation-map-core/src/test
git commit -m "$(cat <<'MSG'
test(core): 나머지 리졸버 세 개의 단위 테스트 추가

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
MSG
)"
```

---
## Task 8: 어노테이션 탈출구와 InvalidationMapAnalyzer

**Files:**
- Create: `.../core/annotation/ReadsEntities.java`, `WritesEntities.java`, `InvalidationMapIgnore.java`
- Create: `.../core/EndpointEntities.java`, `InvalidationMap.java`, `AnalyzerOptions.java`
- Create: `.../core/InvalidationMapAnalyzer.java`
- Test: `.../test/.../fixture/web/TripController.java`
- Test: `.../test/.../InvalidationMapAnalyzerTest.java`

**Interfaces:**
- Consumes: Task 5 의 `CallGraphWalker`, Task 6~7 의 리졸버 네 개
- Produces:
  - `@ReadsEntities(Class<?>[] value, boolean override default false)`
  - `@WritesEntities(Class<?>[] value, boolean override default false)`
  - `@InvalidationMapIgnore`
  - `record EndpointEntities(Set<String> reads, Set<String> writes, List<String> unresolved)` + `boolean resolved()`
  - `record InvalidationMap(Map<MethodRef, EndpointEntities> byHandler)` + `Optional<EndpointEntities> forHandler(MethodRef)`
  - `record AnalyzerOptions(List<String> basePackages, int nodeBudget, boolean expandReadAssociations)` + `static defaults(List<String>)`
  - `InvalidationMapAnalyzer.analyze(ProgramModel, AnalyzerOptions)` → `InvalidationMap`

**판정 규칙**

리졸버는 설계 문서 4.2절 순서대로 적용하고 **처음 값을 돌려준 것이 이깁니다.**

```
JpaRepositoryResolver → EntityManagerResolver → QuerydslResolver → DirtyCheckResolver
```

엔드포인트 하나의 처리 순서입니다.

1. 핸들러에 `@InvalidationMapIgnore` 가 있으면 맵에서 **제외**합니다. 결과에 넣지 않으므로
   스타터가 `x-entities` 를 붙이지 않습니다.
2. 핸들러에서 워커를 돌리며 호출 지점마다 리졸버 체인을 적용해 `reads` 와 `writes` 를 모읍니다.
3. `@ReadsEntities` / `@WritesEntities` 를 반영합니다. 기본은 **추가**, `override = true` 면
   그 방향의 분석 결과를 **대체**합니다.
4. `expandReadAssociations` 가 참이면 `reads` 를 연관 한 단계로 넓힙니다. `writes` 는 넓히지
   않습니다 (설계 문서 5.1).
5. 미해결 사유를 모읍니다. 세 곳에서 옵니다.
   - 워커가 남긴 사유 (본문을 읽을 수 없음)
   - 예산 초과
   - `ClassRepository.unreadableClasses()` — ASM 이 읽지 못한 클래스

**엔티티를 하나도 못 찾은 엔드포인트를 미해결로 표시합니다.**

`reads` 와 `writes` 가 모두 비어 있으면 사유 `"엔티티 접근을 찾지 못했습니다"` 를 붙입니다.
MyBatis 매퍼처럼 이 라이브러리가 모르는 조회 경로만 쓰는 엔드포인트는 워커가 오류 없이
빈 결과를 내놓기 때문입니다. 이것을 표시하지 않으면 조용한 누락이 되어 4.4 원칙에 반합니다.

**대가:** 엔티티를 정말로 건드리지 않는 엔드포인트(헬스체크, 프리사인드 URL 발급, 로그인 등)도
함께 표시됩니다. pirl-spring 기준으로 컨트롤러 5개가 여기 해당합니다. 이 엔드포인트에는
`@InvalidationMapIgnore` 를 붙여 의도를 밝히면 됩니다. 스타터가 부팅 시 미해결 목록을 로그에
찍으므로 어디에 붙일지 바로 알 수 있습니다.

**어노테이션은 상위 타입에서도 찾습니다.** 이 저장소의 컨트롤러 관례처럼 문서 어노테이션을
인터페이스에 붙이는 프로젝트가 있기 때문입니다. 코어는 Spring 의 `AnnotatedElementUtils` 를
쓸 수 없으므로, 핸들러의 owner 와 그 상위 타입에서 같은 이름·디스크립터 메서드를 찾아
어노테이션을 모읍니다.

- [ ] **Step 1: 픽스처와 실패하는 테스트를 작성한다**

`fixture/web/TripController.java`:

```java
package dev.toktokhan.invalidation.core.fixture.web;

import dev.toktokhan.invalidation.core.annotation.InvalidationMapIgnore;
import dev.toktokhan.invalidation.core.annotation.ReadsEntities;
import dev.toktokhan.invalidation.core.annotation.WritesEntities;
import dev.toktokhan.invalidation.core.fixture.entity.Coordinate;
import dev.toktokhan.invalidation.core.fixture.entity.Trip;
import dev.toktokhan.invalidation.core.fixture.repo.TripJpaRepository;
import dev.toktokhan.invalidation.core.fixture.service.TripService;
import org.springframework.transaction.annotation.Transactional;

public class TripController {

    private TripJpaRepository repository;
    private TripService service;

    /** 리포지토리 읽기입니다. 연관 한 단계 확장으로 TripLeg 와 Coordinate 가 붙어야 합니다. */
    public Object read(String title) {
        return repository.findByTitle(title);
    }

    /** 쓰기 트랜잭션 안의 변경자 호출입니다. */
    @Transactional
    public void rename(Trip trip, String title) {
        trip.rename(title);
    }

    /** 이벤트를 지나 리스너까지 도달해야 합니다. */
    public void publish() {
        service.publish();
    }

    /** 엔티티에 닿지 않습니다. 미해결로 표시되어야 합니다. */
    public String ping() {
        return "pong";
    }

    /** 엔티티에 닿지 않지만 의도한 것입니다. 맵에서 제외되어야 합니다. */
    @InvalidationMapIgnore
    public String health() {
        return "ok";
    }

    /** 분석이 못 찾는 것을 개발자가 선언합니다. 분석 결과에 추가됩니다. */
    @ReadsEntities(Coordinate.class)
    public Object readWithHint(String title) {
        return repository.findByTitle(title);
    }

    /** 분석 결과를 대체합니다. */
    @WritesEntities(value = Trip.class, override = true)
    @Transactional
    public void writeWithOverride(Trip trip, String title) {
        trip.reset(title);
    }
}
```

`InvalidationMapAnalyzerTest.java` 가 검증할 케이스입니다.

| 테스트 | 기대 |
| --- | --- |
| `analyze_repositoryRead_reportsEntityAsRead` | `read` 의 `reads` 에 `Trip` 포함 |
| `analyze_readAssociationsExpanded_addsOneStepTargets` | `read` 의 `reads` 에 `TripLeg`, `Coordinate` 포함 |
| `analyze_writeAssociationsNotExpanded_keepsWritesNarrow` | `rename` 의 `writes` 는 `Trip` 만 |
| `analyze_mutatorInTransaction_reportsWrite` | `rename` 의 `writes` 에 `Trip` |
| `analyze_eventListenerReached_includesListenerEntities` | `publish` 의 결과가 리스너 본문의 접근을 포함 |
| `analyze_noEntityAccess_marksUnresolved` | `ping` 의 `resolved()` 가 거짓, 사유에 "엔티티 접근을 찾지 못했습니다" |
| `analyze_ignoredHandler_isAbsentFromMap` | `health` 가 `byHandler` 에 없음 |
| `analyze_readsEntitiesAnnotation_addsToAnalysisResult` | `readWithHint` 의 `reads` 에 `Trip` 과 `Coordinate` 모두 |
| `analyze_overrideAnnotation_replacesAnalysisResult` | `writeWithOverride` 의 `writes` 가 정확히 `{Trip}` |
| `analyze_outputSetsAreSorted_producesStableOrder` | 같은 입력을 두 번 분석하면 결과 목록 순서가 같음 |
| `analyze_unreadableClass_becomesUnresolvedReason` | 바이트를 못 주는 `ProgramModel` 로 분석하면 사유가 남음 |
| `analyze_nodeBudgetExceeded_becomesUnresolvedReason` | `nodeBudget = 1` 인 `AnalyzerOptions` 로 분석하면 사유에 "노드 예산" 포함 (설계 문서 8.1절 요구) |

- [ ] **Step 2: 테스트를 실행해 실패를 확인한다**

```bash
./gradlew :invalidation-map-core:test --tests '*InvalidationMapAnalyzerTest' --console=plain
```

Expected: 컴파일 실패. `cannot find symbol: class InvalidationMapAnalyzer`

- [ ] **Step 3: 어노테이션 세 개를 작성한다**

```java
package dev.toktokhan.invalidation.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 엔드포인트가 읽는 엔티티를 직접 선언합니다.
 *
 * <p>자동 분석이 닿지 못하는 조회 경로(MyBatis, 외부 캐시, 복잡한 네이티브 SQL)를 개발자가
 * 메웁니다. {@code Class} 참조이므로 컴파일 시 검증되고 리네임에 안전합니다.
 *
 * <p>기본 동작은 분석 결과에 더하는 것입니다. {@code override = true} 면 분석 결과를 대체합니다.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ReadsEntities {

    Class<?>[] value();

    boolean override() default false;
}
```

```java
package dev.toktokhan.invalidation.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 이 엔드포인트가 쓰는 엔티티를 직접 선언합니다. 규칙은 {@link ReadsEntities} 와 같습니다. */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface WritesEntities {

    Class<?>[] value();

    boolean override() default false;
}
```

```java
package dev.toktokhan.invalidation.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 엔드포인트를 분석 대상에서 제외합니다. {@code x-entities} 가 붙지 않습니다.
 *
 * <p>엔티티를 정말로 건드리지 않는 엔드포인트(헬스체크, 파일 업로드 URL 발급 등)에 붙입니다.
 * 붙이지 않으면 "엔티티 접근을 찾지 못했습니다" 로 미해결 표시됩니다.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface InvalidationMapIgnore {
}
```

- [ ] **Step 4: 결과 값 타입 세 개를 작성한다**

```java
package dev.toktokhan.invalidation.core;

import java.util.List;
import java.util.Set;

/**
 * 엔드포인트 하나의 분석 결과입니다.
 *
 * @param unresolved 판정하지 못한 자리의 사유입니다. 비어 있지 않으면 소비자는 보수적으로
 *                   다뤄야 합니다
 */
public record EndpointEntities(Set<String> reads, Set<String> writes, List<String> unresolved) {

    public boolean resolved() {
        return unresolved.isEmpty();
    }

    public boolean isEmpty() {
        return reads.isEmpty() && writes.isEmpty();
    }
}
```

```java
package dev.toktokhan.invalidation.core;

import java.util.Map;
import java.util.Optional;

/**
 * 핸들러 메서드에서 그 엔드포인트의 엔티티 집합으로 가는 표입니다.
 *
 * <p>경로가 아니라 핸들러 메서드로 키를 잡습니다. 한 핸들러에 경로나 HTTP 메서드가 여러 개
 * 붙은 매핑에서도 항목이 갈라지지 않고, 스타터가 {@code HandlerMethod} 로 바로 찾습니다.
 */
public record InvalidationMap(Map<MethodRef, EndpointEntities> byHandler) {

    public static InvalidationMap empty() {
        return new InvalidationMap(Map.of());
    }

    public Optional<EndpointEntities> forHandler(MethodRef handler) {
        return Optional.ofNullable(byHandler.get(handler));
    }
}
```

```java
package dev.toktokhan.invalidation.core;

import java.util.List;

/**
 * @param basePackages            사슬을 따라 내려갈 패키지의 internal name 접두어
 * @param nodeBudget              엔드포인트 하나가 방문할 수 있는 최대 메서드 수
 * @param expandReadAssociations  읽기 집합을 연관 한 단계로 넓힐지
 */
public record AnalyzerOptions(List<String> basePackages, int nodeBudget,
    boolean expandReadAssociations) {

    public static AnalyzerOptions defaults(List<String> basePackages) {
        return new AnalyzerOptions(List.copyOf(basePackages), 20_000, true);
    }
}
```

- [ ] **Step 5: InvalidationMapAnalyzer 를 구현한다**

```java
package dev.toktokhan.invalidation.core;

import dev.toktokhan.invalidation.core.index.ClassRepository;
import dev.toktokhan.invalidation.core.index.EntityIndex;
import dev.toktokhan.invalidation.core.index.ListenerIndex;
import dev.toktokhan.invalidation.core.index.RepositoryIndex;
import dev.toktokhan.invalidation.core.resolve.DirtyCheckResolver;
import dev.toktokhan.invalidation.core.resolve.EntityManagerResolver;
import dev.toktokhan.invalidation.core.resolve.EntityResolver;
import dev.toktokhan.invalidation.core.resolve.JpaRepositoryResolver;
import dev.toktokhan.invalidation.core.resolve.QuerydslResolver;
import dev.toktokhan.invalidation.core.resolve.ResolutionContext;
import dev.toktokhan.invalidation.core.scan.AnnotationValues;
import dev.toktokhan.invalidation.core.walk.CallGraphWalker;
import dev.toktokhan.invalidation.core.walk.WalkResult;
import dev.toktokhan.invalidation.core.walk.WalkState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** 엔드포인트를 순회하며 {@link InvalidationMap} 을 만듭니다. 각 층을 여기서 조립합니다. */
public final class InvalidationMapAnalyzer {

    private static final String READS = "Ldev/toktokhan/invalidation/core/annotation/ReadsEntities;";
    private static final String WRITES = "Ldev/toktokhan/invalidation/core/annotation/WritesEntities;";
    private static final String IGNORE =
        "Ldev/toktokhan/invalidation/core/annotation/InvalidationMapIgnore;";

    private static final List<EntityResolver> RESOLVERS = List.of(
        new JpaRepositoryResolver(),
        new EntityManagerResolver(),
        new QuerydslResolver(),
        new DirtyCheckResolver());

    public InvalidationMap analyze(ProgramModel program, AnalyzerOptions options) {
        ClassRepository classes = new ClassRepository(program);
        EntityIndex entities = new EntityIndex(classes, program.entities());
        RepositoryIndex repositories = new RepositoryIndex(program, classes);
        ListenerIndex listeners = new ListenerIndex(classes, program.eventListeners());
        CallGraphWalker walker = new CallGraphWalker(
            classes, program, listeners, options.basePackages(), options.nodeBudget());

        Map<MethodRef, EndpointEntities> result = new LinkedHashMap<>();
        for (Endpoint endpoint : program.endpoints()) {
            MethodRef handler = endpoint.handler();
            if (annotationOn(classes, handler, IGNORE).isPresent()) {
                continue;
            }
            result.put(handler, analyzeEndpoint(
                handler, classes, entities, repositories, walker, options));
        }

        // ASM 이 읽지 못한 클래스는 전체에 영향을 주므로 모든 항목에 사유로 붙입니다.
        Map<String, String> unreadable = classes.unreadableClasses();
        if (!unreadable.isEmpty()) {
            List<String> reasons = unreadable.entrySet().stream()
                .map(entry -> "클래스를 읽지 못했습니다: " + entry.getKey() + " (" + entry.getValue() + ")")
                .sorted()
                .toList();
            result.replaceAll((handler, value) -> withReasons(value, reasons));
        }
        return new InvalidationMap(Collections.unmodifiableMap(new LinkedHashMap<>(result)));
    }

    private EndpointEntities analyzeEndpoint(MethodRef handler, ClassRepository classes,
        EntityIndex entities, RepositoryIndex repositories, CallGraphWalker walker,
        AnalyzerOptions options) {

        Set<String> reads = new TreeSet<>();
        Set<String> writes = new TreeSet<>();

        WalkResult walk = walker.walk(handler, (callee, state) -> {
            ResolutionContext context = new WalkResolutionContext(
                classes, entities, repositories, state);
            for (EntityResolver resolver : RESOLVERS) {
                Optional<EntityAccess> access = resolver.resolve(callee, context);
                if (access.isPresent()) {
                    EntityAccess found = access.get();
                    (found.kind() == AccessKind.WRITE ? writes : reads).addAll(found.entities());
                    return;
                }
            }
        });

        applyAnnotation(classes, handler, READS, reads);
        applyAnnotation(classes, handler, WRITES, writes);

        if (options.expandReadAssociations()) {
            Set<String> expanded = new TreeSet<>(reads);
            for (String entity : reads) {
                expanded.addAll(entities.associationsOf(entity));
            }
            reads.clear();
            reads.addAll(expanded);
        }

        List<String> unresolved = new ArrayList<>(walk.unresolved());
        if (walk.budgetExceeded()) {
            unresolved.add("호출 사슬이 노드 예산 " + options.nodeBudget() + " 을 넘었습니다");
        }
        if (reads.isEmpty() && writes.isEmpty()) {
            // MyBatis 처럼 이 라이브러리가 모르는 조회 경로만 쓰면 오류 없이 빈 결과가 나옵니다.
            // 표시하지 않으면 조용한 누락이 됩니다. 의도한 경우 @InvalidationMapIgnore 를 붙입니다.
            unresolved.add("엔티티 접근을 찾지 못했습니다");
        }
        unresolved.sort(String::compareTo);

        return new EndpointEntities(Collections.unmodifiableSet(new LinkedHashSet<>(reads)), Collections.unmodifiableSet(new LinkedHashSet<>(writes)), List.copyOf(unresolved));
    }

    /** {@code override = true} 면 대체하고, 아니면 더합니다. */
    private void applyAnnotation(ClassRepository classes, MethodRef handler,
        String annotationDescriptor, Set<String> target) {
        annotationOn(classes, handler, annotationDescriptor).ifPresent(values -> {
            List<String> declared = values.strings("value");
            if (declared.isEmpty()) {
                return;
            }
            if (values.bool("override", false)) {
                target.clear();
            }
            target.addAll(declared);
        });
    }

    /**
     * 핸들러 자신과 상위 타입에서 같은 이름·디스크립터 메서드의 어노테이션을 찾습니다.
     *
     * <p>문서 어노테이션을 인터페이스에 붙이는 프로젝트를 지원하기 위함입니다. 코어는 Spring 의
     * {@code AnnotatedElementUtils} 를 쓸 수 없으므로 직접 올라갑니다.
     */
    private Optional<AnnotationValues> annotationOn(ClassRepository classes, MethodRef handler,
        String annotationDescriptor) {
        List<String> candidates = new ArrayList<>();
        candidates.add(handler.owner());
        candidates.addAll(classes.supertypesOf(handler.owner()));
        for (String owner : candidates) {
            Optional<AnnotationValues> found = classes
                .methodFacts(new MethodRef(owner, handler.name(), handler.descriptor()))
                .map(facts -> facts.annotations().get(annotationDescriptor));
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static EndpointEntities withReasons(EndpointEntities value, List<String> reasons) {
        List<String> merged = new ArrayList<>(value.unresolved());
        merged.addAll(reasons);
        merged.sort(String::compareTo);
        return new EndpointEntities(value.reads(), value.writes(), List.copyOf(merged));
    }

    private record WalkResolutionContext(ClassRepository classes, EntityIndex entities,
        RepositoryIndex repositories, WalkState state) implements ResolutionContext {
    }
}
```

**주의 — 정렬:** `reads` 와 `writes` 를 `TreeSet` 으로 모으고 `unresolved` 를 정렬합니다.
정렬하지 않으면 실행마다 순서가 바뀌어 스펙 디프가 흔들립니다 (전역 제약).

- [ ] **Step 6: 테스트를 실행해 통과를 확인한다**

```bash
./gradlew :invalidation-map-core:test --console=plain
```

Expected: `BUILD SUCCESSFUL`, 표의 11개 케이스 통과

- [ ] **Step 7: 커밋한다 (구현 먼저, 테스트 나중)**

```bash
git add invalidation-map-core/src/main
git commit -m "$(cat <<'MSG'
feat(core): InvalidationMapAnalyzer 와 탈출구 어노테이션 추가

각 층을 조립해 엔드포인트별 읽기·쓰기 엔티티 집합을 만듭니다. 리졸버는 설계
문서 4.2절 순서로 적용하고 처음 값을 돌려준 것이 이깁니다.

엔티티를 하나도 못 찾은 엔드포인트를 미해결로 표시합니다. MyBatis 처럼 이
라이브러리가 모르는 조회 경로만 쓰면 오류 없이 빈 결과가 나오는데, 표시하지
않으면 조용한 누락이 되기 때문입니다. 의도한 경우 @InvalidationMapIgnore 로
밝힙니다.

어노테이션은 핸들러의 상위 타입에서도 찾습니다. 문서 어노테이션을 인터페이스에
붙이는 프로젝트를 지원하기 위함이며, 코어는 Spring 을 쓸 수 없어 직접 올라갑니다.

출력 집합은 정렬합니다. 정렬하지 않으면 실행마다 스펙 디프가 흔들립니다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
MSG
)"

git add invalidation-map-core/src/test
git commit -m "$(cat <<'MSG'
test(core): InvalidationMapAnalyzer 단위 테스트 추가

연관 확장이 읽기에만 적용되는지, 무접근 엔드포인트가 미해결로 표시되는지,
@InvalidationMapIgnore 가 맵에서 제외되는지, override 가 결과를 대체하는지,
출력 순서가 안정적인지 확인합니다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
MSG
)"
```

---
## Task 9: 스타터 — SpringProgramModel

**Files:**
- Create: `invalidation-map-spring-boot-starter/build.gradle`
- Create: `.../springboot/SpringProgramModel.java`
- Test: `.../starter/src/test/java/.../app/*.java` (픽스처 애플리케이션)
- Test: `.../starter/src/test/java/.../SpringProgramModelTest.java`

**Interfaces:**
- Consumes: 코어의 `ProgramModel`, `Endpoint`, `MethodRef`, `MethodRefs`
- Produces: `SpringProgramModel(ConfigurableListableBeanFactory, RequestMappingHandlerMapping, EntityManagerFactory, ClassLoader)` — `ProgramModel` 구현

**확인한 Spring API (Boot 3.3.5 계열과 4.0.6 계열 모두 존재)**

| 쓰는 API | 확인한 버전 |
| --- | --- |
| `RequestMappingInfo.getPathPatternsCondition()` | Framework 6.1.14, 7.0.7 |
| `RequestMappingInfo.getPatternsCondition()` | Framework 6.1.14, 7.0.7 |
| `RequestMappingInfo.getMethodsCondition()` | Framework 6.1.14, 7.0.7 |
| `BeanFactory.getType(String, boolean)` | Framework 6.1.14, 7.0.7 |
| `AutoConfigurationPackages.get(BeanFactory)` | Boot 3.3.5, 4.0.6 |
| `Repositories.iterator()` / `getRepositoryInformationFor(Class)` | Data 3.3.5, 4.0.5 |
| `RepositoryMetadata.getRepositoryInterface()` / `getFragments()` | Data 3.3.5, 4.0.5 |
| `RepositoryFragment.getSignatureContributor()` / `getImplementation()` | Data 3.3.5, 4.0.5 |

**설계 판단 세 가지**

1. **`implementationsOf` 는 두 곳에서 찾습니다.** 빈으로 등록된 구현체와, Spring Data 가 내부에서 만드는 리포지토리 프래그먼트 구현체입니다. 프래그먼트는 빈이 아니므로 빈 팩토리만 보면 놓칩니다. pirl-spring 에서 `SlotInstanceRepositoryImpl` 이 정확히 이 경우이고, 놓치면 그 안의 네이티브 SQL 에 도달하지 못해 조용한 누락이 됩니다.

2. **`getType(beanName, false)` 를 씁니다.** `allowFactoryBeanInit = false` 라 `FactoryBean` 을 초기화하는 부수 효과가 없습니다. 타입 조회가 실패하는 빈은 건너뜁니다.

3. **색인 계산은 지연합니다.** 빈 생성은 싸게 두고, 첫 사용 시점에 한 번만 계산합니다. 분석 자체도 지연되므로(Task 10) Swagger 를 열지 않으면 아무 비용이 없습니다.

4. **`entities()` 에 엔티티와 임베더블을 모두 넣습니다.** `@Embedded` 로 응답에 실리는 값 타입도 소비자가 키로 쓸 수 있어야 하고, 코어의 연관 확장이 임베더블 대상을 유지하려면 엔티티 집합에 들어 있어야 합니다.

- [ ] **Step 1: 스타터 모듈 build.gradle 을 만든다**

```groovy
dependencies {
    // 소비자가 @ReadsEntities 등을 쓰므로 api 로 노출합니다.
    api project(':invalidation-map-core')

    // 주 대상은 Boot 4.x 입니다. 소비자가 자기 버전을 가져오도록 compileOnly 로 둡니다.
    compileOnly platform('org.springframework.boot:spring-boot-dependencies:4.0.6')
    compileOnly 'org.springframework.boot:spring-boot-autoconfigure'
    compileOnly 'org.springframework.boot:spring-boot-starter-web'
    compileOnly 'org.springframework.boot:spring-boot-starter-data-jpa'
    compileOnly 'org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.1'

    annotationProcessor platform('org.springframework.boot:spring-boot-dependencies:4.0.6')
    annotationProcessor 'org.springframework.boot:spring-boot-configuration-processor'

    testImplementation platform('org.springframework.boot:spring-boot-dependencies:4.0.6')
    testImplementation 'org.springframework.boot:spring-boot-starter-web'
    testImplementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.1'
    testRuntimeOnly 'com.h2database:h2'
}
```

Boot 3.x 검증은 Task 11 에서 별도 테스트 태스크로 추가합니다.

- [ ] **Step 2: 픽스처 애플리케이션과 실패하는 테스트를 작성한다**

`src/test/java/dev/toktokhan/invalidation/springboot/app/` 에 다음을 만듭니다. 이 픽스처는
Task 10 과 11 에서도 그대로 씁니다.

- `TestApplication` — `@SpringBootApplication`
- `Note` — `@Entity`, 필드 `id`, `title`, `@OneToMany List<NoteTag> tags`, 변경자 `rename(String)`
- `NoteTag` — `@Entity`, 필드 `id`, `label`
- `NoteJpaRepository extends JpaRepository<Note, Long>, NoteRepositoryCustom` — `findByTitle`
- `NoteRepositoryCustom` — `void upsert(Long id, String title)` (프래그먼트 인터페이스)
- `NoteRepositoryImpl implements NoteRepositoryCustom` — `EntityManager.createNativeQuery("INSERT INTO note ...")` (프래그먼트 구현체. 빈이 아닙니다)
- `NotePort` / `NotePortAdapter` — `@Repository` 가 붙은 어댑터 빈. `NoteJpaRepository` 를 호출
- `NoteEventListener` — `@TransactionalEventListener` 로 `NoteCreatedEvent` 수신, `NoteTag` 저장
- `NoteCreatedEvent` — POJO 이벤트 (`ApplicationEvent` 를 상속하지 않습니다)
- `NoteService` — `@Transactional`. 포트 호출, 이벤트 발행, 변경자 호출
- `NoteController` — `@RestController @RequestMapping("/notes")`. `@GetMapping` 조회, `@PostMapping` 생성, `@PutMapping("{id}")` 수정, `@GetMapping("/health")` + `@InvalidationMapIgnore`

`SpringProgramModelTest` (`@SpringBootTest(classes = TestApplication.class)`) 가 검증할 케이스입니다.

| 테스트 | 기대 |
| --- | --- |
| `endpoints_restController_reportsHandlerMethodRefs` | `NoteController` 의 네 핸들러가 모두 나옴 |
| `endpoints_getMapping_reportsHttpMethodAndPath` | `GET`, `/notes` |
| `entityFor_springDataRepository_resolvesEntity` | `NoteJpaRepository` → `Note` |
| `entityFor_repositoryFragmentInterface_resolvesEntity` | `NoteRepositoryCustom` → `Note` |
| `implementationsOf_beanAdapter_findsBean` | `NotePort` → `NotePortAdapter` |
| `implementationsOf_repositoryFragment_findsNonBeanImplementation` | `NoteRepositoryCustom` → `NoteRepositoryImpl` |
| `entities_metamodel_includesEntitiesAndEmbeddables` | `Note`, `NoteTag` 포함 |
| `eventListeners_transactionalEventListener_isReported` | `NoteEventListener.onNoteCreated` 포함 |
| `classBytes_applicationClass_returnsBytes` | 비어 있지 않음 |
| `classBytes_missingClass_returnsEmpty` | 빈 값 |

- [ ] **Step 3: 테스트를 실행해 실패를 확인한다**

```bash
./gradlew :invalidation-map-spring-boot-starter:test --console=plain
```

Expected: 컴파일 실패. `cannot find symbol: class SpringProgramModel`

- [ ] **Step 4: SpringProgramModel 을 구현한다**

```java
package dev.toktokhan.invalidation.springboot;

import dev.toktokhan.invalidation.core.Endpoint;
import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.core.ProgramModel;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Metamodel;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.support.RepositoryFragment;
import org.springframework.data.repository.support.Repositories;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Spring 이 이미 알고 있는 사실로 {@link ProgramModel} 을 채웁니다.
 *
 * <p>엔드포인트 신원은 {@code RequestMappingHandlerMapping} 이, 리포지토리와 엔티티의 대응은
 * Spring Data 의 {@code Repositories} 가, 인터페이스에 실제로 꽂힌 구현은 빈 팩토리가
 * 알려줍니다. 그 규칙을 다시 구현하지 않습니다.
 *
 * <p>색인은 첫 사용 시점에 한 번만 계산합니다.
 */
public final class SpringProgramModel implements ProgramModel {

    private final ConfigurableListableBeanFactory beanFactory;
    private final RequestMappingHandlerMapping handlerMapping;
    private final EntityManagerFactory entityManagerFactory;
    private final ClassLoader classLoader;

    private final Map<String, Set<String>> implementationCache = new ConcurrentHashMap<>();

    private volatile boolean initialized;
    private List<Endpoint> endpoints = List.of();
    private Map<String, String> repositoryEntities = Map.of();
    private Map<String, Set<String>> fragmentImplementations = Map.of();
    private Set<String> entities = Set.of();
    private Set<MethodRef> eventListeners = Set.of();

    public SpringProgramModel(ConfigurableListableBeanFactory beanFactory,
        RequestMappingHandlerMapping handlerMapping, EntityManagerFactory entityManagerFactory,
        ClassLoader classLoader) {
        this.beanFactory = beanFactory;
        this.handlerMapping = handlerMapping;
        this.entityManagerFactory = entityManagerFactory;
        this.classLoader = classLoader;
    }

    @Override
    public List<Endpoint> endpoints() {
        initialize();
        return endpoints;
    }

    @Override
    public Optional<byte[]> classBytes(String internalName) {
        try (InputStream in = classLoader.getResourceAsStream(internalName + ".class")) {
            return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> entityFor(String repositoryInternalName) {
        initialize();
        return Optional.ofNullable(repositoryEntities.get(repositoryInternalName));
    }

    @Override
    public Set<String> implementationsOf(String interfaceInternalName) {
        initialize();
        return implementationCache.computeIfAbsent(interfaceInternalName, name -> {
            Set<String> found = new LinkedHashSet<>(
                fragmentImplementations.getOrDefault(name, Set.of()));
            Class<?> declared = loadClass(name).orElse(null);
            if (declared != null) {
                for (String beanName : beanFactory.getBeanDefinitionNames()) {
                    Class<?> beanType = typeOf(beanName);
                    if (beanType == null || beanType.equals(declared)) {
                        continue;
                    }
                    if (declared.isAssignableFrom(beanType)) {
                        found.add(MethodRefs.internalNameOf(ClassUtils.getUserClass(beanType)));
                    }
                }
            }
            return Collections.unmodifiableSet(new LinkedHashSet<>(found));
        });
    }

    @Override
    public Set<String> entities() {
        initialize();
        return entities;
    }

    @Override
    public Set<MethodRef> eventListeners() {
        initialize();
        return eventListeners;
    }

    private void initialize() {
        if (initialized) {
            return;
        }
        synchronized (this) {
            if (initialized) {
                return;
            }
            this.endpoints = buildEndpoints();
            buildRepositoryIndex();
            this.entities = buildEntities();
            this.eventListeners = buildEventListeners();
            this.initialized = true;
        }
    }

    /**
     * {@code getHandlerMethods()} 가 돌려주는 맵의 순회 순서는 보장되지 않습니다. 그대로 담으면
     * 엔드포인트 목록 순서가 JVM 을 다시 띄울 때마다 달라지고, 그 순서에 얹히는 진단 출력과
     * 미해결 목록 순서도 함께 흔들립니다. HTTP 메서드·경로·핸들러 순으로 정렬해 고정합니다.
     */
    private List<Endpoint> buildEndpoints() {
        List<Endpoint> found = new ArrayList<>();
        handlerMapping.getHandlerMethods().forEach((info, handlerMethod) -> {
            Class<?> userClass = ClassUtils.getUserClass(handlerMethod.getBeanType());
            MethodRef handler = MethodRefs.of(userClass, handlerMethod.getMethod());
            found.add(new Endpoint(httpMethodOf(info), pathOf(info), handler));
        });
        found.sort(Comparator.comparing(Endpoint::httpMethod)
            .thenComparing(Endpoint::path)
            .thenComparing(endpoint -> endpoint.handler().owner())
            .thenComparing(endpoint -> endpoint.handler().name())
            .thenComparing(endpoint -> endpoint.handler().descriptor()));
        return List.copyOf(found);
    }

    private static String httpMethodOf(RequestMappingInfo info) {
        return info.getMethodsCondition().getMethods().stream()
            .findFirst()
            .map(Enum::name)
            .orElse("ANY");
    }

    /** 진단 표시용입니다. 신원은 핸들러 메서드가 잡으므로 첫 패턴만 있으면 됩니다. */
    private static String pathOf(RequestMappingInfo info) {
        var pathPatterns = info.getPathPatternsCondition();
        if (pathPatterns != null && !pathPatterns.getPatterns().isEmpty()) {
            return pathPatterns.getPatterns().iterator().next().getPatternString();
        }
        var patterns = info.getPatternsCondition();
        if (patterns != null && !patterns.getPatterns().isEmpty()) {
            return patterns.getPatterns().iterator().next();
        }
        return info.toString();
    }

    /**
     * 리포지토리 인터페이스와 프래그먼트 인터페이스를 모두 엔티티에 대응시키고, 프래그먼트
     * 구현체를 따로 모읍니다.
     *
     * <p>프래그먼트 구현체는 빈이 아닙니다. Spring Data 가 내부에서 만들기 때문에 빈 팩토리로는
     * 찾을 수 없고, 놓치면 그 안의 네이티브 SQL 에 도달하지 못합니다.
     */
    private void buildRepositoryIndex() {
        Map<String, String> byRepository = new LinkedHashMap<>();
        Map<String, Set<String>> byFragment = new LinkedHashMap<>();

        Repositories repositories = new Repositories(beanFactory);
        for (Class<?> domainType : repositories) {
            Optional<RepositoryInformation> information =
                repositories.getRepositoryInformationFor(domainType);
            if (information.isEmpty()) {
                continue;
            }
            RepositoryInformation info = information.get();
            String entity = MethodRefs.internalNameOf(domainType);
            byRepository.put(MethodRefs.internalNameOf(info.getRepositoryInterface()), entity);

            for (RepositoryFragment<?> fragment : info.getFragments()) {
                Class<?> contributor = fragment.getSignatureContributor();
                byRepository.putIfAbsent(MethodRefs.internalNameOf(contributor), entity);
                // getImplementationClass() 는 Spring Data 4.x 에만 있습니다.
                // 3.x 와 4.x 모두에 있는 getImplementation() 을 씁니다.
                fragment.getImplementation().ifPresent(implementation -> byFragment
                    .computeIfAbsent(MethodRefs.internalNameOf(contributor),
                        key -> new LinkedHashSet<>())
                    .add(MethodRefs.internalNameOf(
                        ClassUtils.getUserClass(implementation.getClass()))));
            }
        }
        this.repositoryEntities = Collections.unmodifiableMap(new LinkedHashMap<>(byRepository));
        this.fragmentImplementations = Collections.unmodifiableMap(new LinkedHashMap<>(byFragment));
    }

    /** 엔티티와 임베더블을 모두 넣습니다. @Embedded 값 타입도 응답에 실리기 때문입니다. */
    private Set<String> buildEntities() {
        Set<String> found = new LinkedHashSet<>();
        Metamodel metamodel = entityManagerFactory.getMetamodel();
        metamodel.getEntities().forEach(type -> addJavaType(found, type.getJavaType()));
        metamodel.getEmbeddables().forEach(type -> addJavaType(found, type.getJavaType()));
        return Collections.unmodifiableSet(new LinkedHashSet<>(found));
    }

    private static void addJavaType(Set<String> target, Class<?> javaType) {
        if (javaType != null) {
            target.add(MethodRefs.internalNameOf(javaType));
        }
    }

    /**
     * {@code @TransactionalEventListener} 는 {@code @EventListener} 로 메타 어노테이션되어
     * 있으므로 한 번만 검사하면 둘 다 걸립니다.
     */
    private Set<MethodRef> buildEventListeners() {
        Set<MethodRef> found = new LinkedHashSet<>();
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            Class<?> beanType = typeOf(beanName);
            if (beanType == null) {
                continue;
            }
            Class<?> userClass = ClassUtils.getUserClass(beanType);
            for (Method method : ReflectionUtils.getAllDeclaredMethods(userClass)) {
                if (AnnotatedElementUtils.hasAnnotation(method, EventListener.class)) {
                    found.add(MethodRefs.of(userClass, method));
                }
            }
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(found));
    }

    /** {@code allowFactoryBeanInit = false} 라 FactoryBean 초기화 부수 효과가 없습니다. */
    private Class<?> typeOf(String beanName) {
        try {
            return beanFactory.getType(beanName, false);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Optional<Class<?>> loadClass(String internalName) {
        try {
            return Optional.of(ClassUtils.forName(MethodRefs.fqcnOf(internalName), classLoader));
        } catch (ClassNotFoundException | LinkageError e) {
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 5: 테스트를 실행해 통과를 확인한다**

```bash
./gradlew :invalidation-map-spring-boot-starter:test --console=plain
```

Expected: `BUILD SUCCESSFUL`, 10개 통과

- [ ] **Step 6: 커밋한다 (구현 먼저, 테스트 나중)**

```bash
git add invalidation-map-spring-boot-starter/build.gradle invalidation-map-spring-boot-starter/src/main
git commit -m "$(cat <<'MSG'
feat(starter): Spring 메타데이터로 ProgramModel 을 채우는 SpringProgramModel 추가

엔드포인트 신원은 RequestMappingHandlerMapping, 리포지토리와 엔티티의 대응은
Spring Data 의 Repositories, 인터페이스에 꽂힌 구현은 빈 팩토리에서 받습니다.
Spring 의 해석 규칙을 다시 구현하지 않습니다.

구현체 조회는 빈 팩토리와 Spring Data 프래그먼트 두 곳을 봅니다. 프래그먼트
구현체는 빈이 아니라 Spring Data 가 내부에서 만들기 때문에, 빈 팩토리만 보면
그 안의 네이티브 SQL 에 도달하지 못합니다.

Boot 4.x 전용 API 를 쓰지 않아 3.x 계열에서도 그대로 동작합니다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
MSG
)"

git add invalidation-map-spring-boot-starter/src/test
git commit -m "$(cat <<'MSG'
test(starter): SpringProgramModel 통합 테스트와 픽스처 애플리케이션 추가

프래그먼트 인터페이스가 엔티티로 풀리는지, 빈이 아닌 프래그먼트 구현체를
찾는지, POJO 이벤트 리스너가 열거되는지 확인합니다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
MSG
)"
```

---
## Task 10: 스타터 — x-entities 주입과 자동 설정

**Files:**
- Create: `.../springboot/EntityNaming.java`, `InvalidationMapProperties.java`
- Create: `.../springboot/InvalidationMapOperationCustomizer.java`
- Create: `.../springboot/InvalidationMapAutoConfiguration.java`
- Create: `.../starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `.../starter/src/test/java/.../InvalidationMapIntegrationTest.java`

**Interfaces:**
- Consumes: Task 8 의 `InvalidationMapAnalyzer`/`AnalyzerOptions`/`InvalidationMap`, Task 9 의 `SpringProgramModel`
- Produces:
  - `enum EntityNaming { FQCN, SIMPLE }`
  - `InvalidationMapProperties` (prefix `invalidation-map`)
  - `InvalidationMapOperationCustomizer` + `void failIfUnresolved()`
  - `InvalidationMapAutoConfiguration`

**설계 판단 세 가지**

1. **분석을 첫 스펙 요청까지 미룹니다.** springdoc 은 `/v3/api-docs` 를 처음 요청받을 때 스펙을 만듭니다. 그때 분석하면 부팅 시간이 전혀 늘지 않고, Swagger 를 열지 않는 운영 환경에서는 분석이 아예 돌지 않습니다. 결과는 `AtomicReference` 에 담아 한 번만 계산합니다.

2. **`SpringProgramModel` 을 빈으로 만들지 않고 커스터마이저가 첫 사용 시점에 만듭니다.** `RequestMappingHandlerMapping` 과 `EntityManagerFactory` 를 빈 생성 시점에 주입받으면 자동 설정 순서를 지정해야 하는데, Boot 4 에서 자동 설정 클래스가 모듈별로 재배치되어 `@AutoConfiguration(after = WebMvcAutoConfiguration.class)` 같은 클래스 참조가 버전에 묶입니다. `ObjectProvider` 로 받아 나중에 꺼내면 순서 제약이 사라지고 3.x 와 4.x 양쪽에서 같은 코드가 돕니다.

3. **`fail-on-unresolved = true` 일 때만 부팅 중에 분석합니다.** 부팅을 실패시키려면 부팅 중에 결과가 있어야 하기 때문입니다. 기본값은 거짓이므로 대부분의 소비자는 지연 분석의 이점을 그대로 받습니다.

- [ ] **Step 1: 실패하는 통합 테스트를 작성한다**

`InvalidationMapIntegrationTest` — `@SpringBootTest(classes = TestApplication.class, webEnvironment = RANDOM_PORT)`
로 띄우고 `TestRestTemplate` 으로 `/v3/api-docs` 를 받아 JSON 을 검증합니다.

| 테스트 | 기대 |
| --- | --- |
| `apiDocs_getEndpoint_hasReadsExtension` | `paths./notes.get.x-entities.reads` 에 `Note` 의 FQCN 포함 |
| `apiDocs_getEndpoint_readsIncludeAssociatedEntity` | 같은 위치에 `NoteTag` 포함 (연관 한 단계 확장) |
| `apiDocs_postEndpoint_hasWritesExtension` | `paths./notes.post.x-entities.writes` 에 `Note` 포함 |
| `apiDocs_postEndpoint_writesIncludeEventListenerEntity` | `writes` 에 `NoteTag` 포함 (POJO 이벤트 리스너를 지나 도달) |
| `apiDocs_putEndpoint_dirtyCheckMutatorIsWrite` | `paths./notes/{id}.put.x-entities.writes` 에 `Note` 포함 |
| `apiDocs_fragmentNativeSql_resolvesEntity` | 프래그먼트를 지나는 엔드포인트의 `writes` 에 `Note` 포함 |
| `apiDocs_ignoredEndpoint_hasNoExtension` | `paths./notes/health.get` 에 `x-entities` 없음 |
| `apiDocs_entityNamingSimple_usesSimpleNames` | `entity-naming: SIMPLE` 프로퍼티로 띄우면 `Note` 처럼 단순명 |
| `apiDocs_extensionListsAreSorted_isStable` | 목록이 정렬되어 있음 |
| `apiDocs_resolvedTrue_isOmitted` | 해결된 엔드포인트에는 `resolved` 키가 없음 |

- [ ] **Step 2: 테스트를 실행해 실패를 확인한다**

```bash
./gradlew :invalidation-map-spring-boot-starter:test --tests '*InvalidationMapIntegrationTest' --console=plain
```

Expected: 실패. `x-entities` 확장이 없음

- [ ] **Step 3: EntityNaming 과 Properties 를 작성한다**

```java
package dev.toktokhan.invalidation.springboot;

/** 스펙에 엔티티를 어떤 이름으로 실을지 정합니다. */
public enum EntityNaming {

    /** 패키지를 포함한 이름입니다. 동명 엔티티 충돌이 없습니다. */
    FQCN,

    /** 단순 클래스명입니다. 짧지만 패키지가 다른 동명 엔티티에서 충돌합니다. */
    SIMPLE
}
```

```java
package dev.toktokhan.invalidation.springboot;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "invalidation-map")
public class InvalidationMapProperties {

    /** 끄면 x-entities 를 붙이지 않고 분석도 하지 않습니다. */
    private boolean enabled = true;

    /** 호출 사슬을 따라 내려갈 패키지입니다. 비우면 @SpringBootApplication 의 패키지를 씁니다. */
    private List<String> basePackages = new ArrayList<>();

    private EntityNaming entityNaming = EntityNaming.FQCN;

    /** 엔드포인트 하나가 방문할 수 있는 최대 메서드 수입니다. */
    private int nodeBudget = 20_000;

    /** 읽기 집합을 연관 한 단계로 넓힐지 정합니다. */
    private boolean expandReadAssociations = true;

    /** 참이면 미해결 엔드포인트가 하나라도 있을 때 부팅을 실패시킵니다. */
    private boolean failOnUnresolved = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getBasePackages() {
        return basePackages;
    }

    public void setBasePackages(List<String> basePackages) {
        this.basePackages = basePackages;
    }

    public EntityNaming getEntityNaming() {
        return entityNaming;
    }

    public void setEntityNaming(EntityNaming entityNaming) {
        this.entityNaming = entityNaming;
    }

    public int getNodeBudget() {
        return nodeBudget;
    }

    public void setNodeBudget(int nodeBudget) {
        this.nodeBudget = nodeBudget;
    }

    public boolean isExpandReadAssociations() {
        return expandReadAssociations;
    }

    public void setExpandReadAssociations(boolean expandReadAssociations) {
        this.expandReadAssociations = expandReadAssociations;
    }

    public boolean isFailOnUnresolved() {
        return failOnUnresolved;
    }

    public void setFailOnUnresolved(boolean failOnUnresolved) {
        this.failOnUnresolved = failOnUnresolved;
    }
}
```

- [ ] **Step 4: OperationCustomizer 를 구현한다**

```java
package dev.toktokhan.invalidation.springboot;

import dev.toktokhan.invalidation.core.AnalyzerOptions;
import dev.toktokhan.invalidation.core.EndpointEntities;
import dev.toktokhan.invalidation.core.InvalidationMap;
import dev.toktokhan.invalidation.core.InvalidationMapAnalyzer;
import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.MethodRefs;
import io.swagger.v3.oas.models.Operation;
import jakarta.persistence.EntityManagerFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 분석 결과를 OpenAPI 오퍼레이션의 {@code x-entities} 확장으로 붙입니다.
 *
 * <p>분석은 첫 스펙 요청 때 한 번만 합니다. 부팅 시간이 늘지 않고, Swagger 를 열지 않는
 * 환경에서는 아예 돌지 않습니다.
 */
public final class InvalidationMapOperationCustomizer implements OperationCustomizer {

    private static final Log log = LogFactory.getLog(InvalidationMapOperationCustomizer.class);
    private static final String EXTENSION = "x-entities";

    private final ConfigurableListableBeanFactory beanFactory;
    private final ObjectProvider<RequestMappingHandlerMapping> handlerMappings;
    private final ObjectProvider<EntityManagerFactory> entityManagerFactories;
    private final InvalidationMapProperties properties;
    private final AtomicReference<InvalidationMap> cached = new AtomicReference<>();

    public InvalidationMapOperationCustomizer(ConfigurableListableBeanFactory beanFactory,
        ObjectProvider<RequestMappingHandlerMapping> handlerMappings,
        ObjectProvider<EntityManagerFactory> entityManagerFactories,
        InvalidationMapProperties properties) {
        this.beanFactory = beanFactory;
        this.handlerMappings = handlerMappings;
        this.entityManagerFactories = entityManagerFactories;
        this.properties = properties;
    }

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        Class<?> userClass = ClassUtils.getUserClass(handlerMethod.getBeanType());
        MethodRef handler = MethodRefs.of(userClass, handlerMethod.getMethod());
        map().forHandler(handler).ifPresent(entities -> apply(operation, entities));
        return operation;
    }

    /** 미해결 엔드포인트가 있으면 예외를 던집니다. fail-on-unresolved 일 때만 부릅니다. */
    public void failIfUnresolved() {
        List<String> offenders = map().byHandler().entrySet().stream()
            .filter(entry -> !entry.getValue().resolved())
            .map(entry -> entry.getKey() + " -> " + entry.getValue().unresolved())
            .sorted()
            .toList();
        if (!offenders.isEmpty()) {
            throw new IllegalStateException(
                "엔티티 접근을 판정하지 못한 엔드포인트가 있습니다 (" + offenders.size() + "개). "
                    + "의도한 것이면 @InvalidationMapIgnore 를 붙이십시오.\n"
                    + String.join("\n", offenders));
        }
    }

    private void apply(Operation operation, EndpointEntities entities) {
        Map<String, Object> extension = new LinkedHashMap<>();
        if (!entities.reads().isEmpty()) {
            extension.put("reads", names(entities.reads()));
        }
        if (!entities.writes().isEmpty()) {
            extension.put("writes", names(entities.writes()));
        }
        if (!entities.resolved()) {
            // resolved 는 false 일 때만 넣습니다. true 를 전부 넣으면 스펙만 커집니다.
            extension.put("resolved", false);
            extension.put("unresolved", entities.unresolved());
        }
        if (!extension.isEmpty()) {
            operation.addExtension(EXTENSION, extension);
        }
    }

    private List<String> names(Set<String> internalNames) {
        Function<String, String> mapper = properties.getEntityNaming() == EntityNaming.SIMPLE
            ? MethodRefs::simpleNameOf
            : MethodRefs::fqcnOf;
        return internalNames.stream().map(mapper).sorted().toList();
    }

    private InvalidationMap map() {
        InvalidationMap existing = cached.get();
        if (existing != null) {
            return existing;
        }
        cached.compareAndSet(null, analyze());
        return cached.get();
    }

    private InvalidationMap analyze() {
        Optional<RequestMappingHandlerMapping> handlerMapping =
            handlerMappings.orderedStream().findFirst();
        Optional<EntityManagerFactory> entityManagerFactory =
            entityManagerFactories.orderedStream().findFirst();
        if (handlerMapping.isEmpty() || entityManagerFactory.isEmpty()) {
            log.info("invalidation-map: RequestMappingHandlerMapping 또는 EntityManagerFactory 가 "
                + "없어 분석하지 않습니다");
            return InvalidationMap.empty();
        }

        SpringProgramModel program = new SpringProgramModel(beanFactory, handlerMapping.get(),
            entityManagerFactory.get(), beanFactory.getBeanClassLoader());
        AnalyzerOptions options = new AnalyzerOptions(basePackages(),
            properties.getNodeBudget(), properties.isExpandReadAssociations());

        long startedAt = System.currentTimeMillis();
        InvalidationMap result = new InvalidationMapAnalyzer().analyze(program, options);
        logSummary(result, System.currentTimeMillis() - startedAt);
        return result;
    }

    /** 프로퍼티가 없으면 @SpringBootApplication 의 패키지를 씁니다. */
    private List<String> basePackages() {
        List<String> configured = properties.getBasePackages();
        List<String> source = configured.isEmpty() && AutoConfigurationPackages.has(beanFactory)
            ? AutoConfigurationPackages.get(beanFactory)
            : configured;
        return source.stream().map(name -> name.replace('.', '/')).toList();
    }

    private void logSummary(InvalidationMap result, long elapsedMillis) {
        List<String> unresolved = result.byHandler().entrySet().stream()
            .filter(entry -> !entry.getValue().resolved())
            .map(entry -> "  " + entry.getKey() + " -> " + entry.getValue().unresolved())
            .sorted()
            .toList();
        log.info("invalidation-map: 엔드포인트 " + result.byHandler().size() + "개 분석 완료 ("
            + elapsedMillis + "ms), 미해결 " + unresolved.size() + "개");
        if (!unresolved.isEmpty()) {
            log.info("invalidation-map: 미해결 엔드포인트입니다. 의도한 것이면 "
                + "@InvalidationMapIgnore 를 붙이십시오.\n" + String.join("\n", unresolved));
        }
    }
}
```

- [ ] **Step 5: 자동 설정과 등록 파일을 작성한다**

```java
package dev.toktokhan.invalidation.springboot;

import jakarta.persistence.EntityManagerFactory;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * springdoc 과 JPA 가 클래스패스에 있고 {@code invalidation-map.enabled} 가 꺼져 있지 않을 때만
 * 동작합니다.
 *
 * <p>{@code @AutoConfiguration(after = ...)} 로 순서를 지정하지 않습니다. Boot 4 에서 자동 설정
 * 클래스가 모듈별로 재배치되어 클래스 참조가 버전에 묶이기 때문입니다. 대신 {@code ObjectProvider}
 * 로 받아 첫 사용 시점에 꺼냅니다.
 */
@AutoConfiguration
@ConditionalOnClass({OperationCustomizer.class, EntityManagerFactory.class,
    RequestMappingHandlerMapping.class})
@ConditionalOnProperty(prefix = "invalidation-map", name = "enabled", havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties(InvalidationMapProperties.class)
public class InvalidationMapAutoConfiguration {

    @Bean
    public InvalidationMapOperationCustomizer invalidationMapOperationCustomizer(
        ConfigurableListableBeanFactory beanFactory,
        ObjectProvider<RequestMappingHandlerMapping> handlerMappings,
        ObjectProvider<EntityManagerFactory> entityManagerFactories,
        InvalidationMapProperties properties) {
        return new InvalidationMapOperationCustomizer(
            beanFactory, handlerMappings, entityManagerFactories, properties);
    }

    /**
     * 부팅을 실패시키려면 부팅 중에 결과가 있어야 하므로, 이 경우에만 즉시 분석합니다.
     * 기본값은 거짓이라 대부분의 소비자는 지연 분석의 이점을 그대로 받습니다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "invalidation-map", name = "fail-on-unresolved",
        havingValue = "true")
    public ApplicationListener<org.springframework.boot.context.event.ApplicationReadyEvent>
        invalidationMapUnresolvedCheck(InvalidationMapOperationCustomizer customizer) {
        return event -> customizer.failIfUnresolved();
    }
}
```

`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
dev.toktokhan.invalidation.springboot.InvalidationMapAutoConfiguration
```

- [ ] **Step 6: 테스트를 실행해 통과를 확인한다**

```bash
./gradlew :invalidation-map-spring-boot-starter:test --console=plain
```

Expected: `BUILD SUCCESSFUL`, 통합 테스트 10개 통과

- [ ] **Step 7: 커밋한다 (구현 먼저, 테스트 나중)**

```bash
git add invalidation-map-spring-boot-starter/src/main
git commit -m "$(cat <<'MSG'
feat(starter): x-entities 주입과 자동 설정 추가

분석을 첫 스펙 요청까지 미룹니다. 부팅 시간이 늘지 않고, Swagger 를 열지 않는
운영 환경에서는 분석이 아예 돌지 않습니다.

자동 설정 순서를 클래스 참조로 지정하지 않고 ObjectProvider 로 받습니다.
Boot 4 에서 자동 설정 클래스가 모듈별로 재배치되어 클래스 참조가 버전에
묶이기 때문이며, 이렇게 하면 3.x 와 4.x 에서 같은 코드가 돕니다.

resolved 는 false 일 때만 스펙에 넣고, 목록은 정렬해 디프를 안정시킵니다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
MSG
)"

git add invalidation-map-spring-boot-starter/src/test
git commit -m "$(cat <<'MSG'
test(starter): /v3/api-docs 의 x-entities 를 검증하는 통합 테스트 추가

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
MSG
)"
```

---

## Task 11: Spring Boot 3.x 호환 검증

**Files:**
- Modify: `invalidation-map-spring-boot-starter/build.gradle` — `boot3Test` 소스셋과 태스크 추가

**Interfaces:**
- Consumes: Task 9~10 의 스타터 코드와 테스트
- Produces: `./gradlew :invalidation-map-spring-boot-starter:boot3Test` 태스크

**왜 필요한가**

전역 제약에 "Boot 3.x 도 지원한다"고 적었습니다. 그 주장을 테스트가 확인하지 않으면 근거 없는
주장입니다. 같은 테스트 코드를 Boot 3.3.5 + springdoc 2.5.0 클래스패스로 한 번 더 돌립니다.

- [ ] **Step 1: boot3Test 소스셋과 태스크를 추가한다**

`invalidation-map-spring-boot-starter/build.gradle` 에 다음을 추가합니다.

```groovy
// 같은 테스트 코드를 Boot 3.x 클래스패스로 한 번 더 돌립니다.
// "3.x 도 지원한다"는 주장을 테스트가 확인하게 하려는 것입니다.
sourceSets {
    boot3Test {
        java.srcDir 'src/test/java'
        resources.srcDir 'src/test/resources'
        compileClasspath += sourceSets.main.output
        runtimeClasspath += sourceSets.main.output
    }
}

dependencies {
    boot3TestImplementation project(':invalidation-map-core')
    boot3TestImplementation platform('org.springframework.boot:spring-boot-dependencies:3.3.5')
    boot3TestImplementation 'org.springframework.boot:spring-boot-starter-web'
    boot3TestImplementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    boot3TestImplementation 'org.springframework.boot:spring-boot-starter-test'
    boot3TestImplementation 'org.springdoc:springdoc-openapi-starter-webmvc-api:2.5.0'
    boot3TestImplementation platform('org.junit:junit-bom:5.10.3')
    boot3TestImplementation 'org.junit.jupiter:junit-jupiter'
    boot3TestImplementation 'org.assertj:assertj-core:3.26.3'
    boot3TestRuntimeOnly 'org.junit.platform:junit-platform-launcher'
    boot3TestRuntimeOnly 'com.h2database:h2'
}

tasks.register('boot3Test', Test) {
    description = 'Spring Boot 3.x 클래스패스로 스타터 테스트를 돌립니다.'
    group = 'verification'
    testClassesDirs = sourceSets.boot3Test.output.classesDirs
    classpath = sourceSets.boot3Test.runtimeClasspath
    useJUnitPlatform()
    testLogging {
        events 'failed'
        exceptionFormat 'full'
    }
}

tasks.named('check') {
    dependsOn 'boot3Test'
}
```

- [ ] **Step 2: Boot 3 로 테스트를 돌려 통과를 확인한다**

```bash
./gradlew :invalidation-map-spring-boot-starter:boot3Test --console=plain
```

Expected: `BUILD SUCCESSFUL`. 실패하면 실패한 지점이 3.x 와 4.x 에서 갈리는 API 입니다.
그 API 를 양쪽에 다 있는 것으로 바꾸고, 전역 제약의 API 표에 그 사실을 추가하십시오.

- [ ] **Step 3: 전체 검증을 돌린다**

```bash
./gradlew clean check --console=plain
```

Expected: `BUILD SUCCESSFUL`. 코어 테스트, 스타터 Boot 4 테스트, 스타터 Boot 3 테스트가 모두 통과

- [ ] **Step 4: 커밋한다**

이 태스크는 빌드 설정만 바꾸므로 커밋이 하나입니다.

```bash
git add invalidation-map-spring-boot-starter/build.gradle
git commit -m "$(cat <<'MSG'
build(starter): Spring Boot 3.x 호환을 확인하는 boot3Test 태스크 추가

같은 테스트 코드를 Boot 3.3.5 + springdoc 2.5.0 클래스패스로 한 번 더 돌립니다.
"3.x 도 지원한다"는 주장을 테스트가 확인하게 하려는 것입니다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
MSG
)"
```

---

## Task 12: pirl-spring 실제 검증과 README

**Files:**
- Create: `README.md`
- Modify: (pirl-spring 쪽) `settings.gradle`, `build.gradle` — 검증용이며 이 저장소에 커밋하지 않습니다

**Interfaces:**
- Consumes: Task 1~11 전부
- Produces: 실제 프로젝트에서 동작한다는 증거와 사용 문서

**왜 필요한가**

지금까지의 검증은 전부 이 저장소의 픽스처입니다. 픽스처는 만든 사람이 예상한 모양만 담고
있습니다. 엔드포인트 211개, 엔티티 53개, 컨트롤러 45개짜리 실제 프로젝트에서 돌려봐야
설계가 맞았는지 알 수 있습니다.

pirl-spring 은 Spring Boot 3.3.5 라 **Boot 3 경로를 실제 프로젝트로 검증**하는 셈이기도 합니다.

- [ ] **Step 1: composite build 로 pirl-spring 에 물린다**

pirl-spring 워크트리(`/Users/poku/orca/workspaces/pirl-spring/feat-swagger-refresh-hints`)에서
작업합니다. 이 변경은 검증용이므로 커밋하지 않습니다.

`settings.gradle` 에 추가:

```groovy
includeBuild '/Users/poku/projects/toktokhan/spring/spring-invalidation-map'
```

`build.gradle` 의 `dependencies` 에 추가:

```groovy
implementation 'dev.toktokhan.invalidation:invalidation-map-spring-boot-starter'
```

`application-local.yml` 은 gitignore 되어 있으므로 메인 리포에서 복사합니다.

```bash
cp /Users/poku/projects/toktokhan/spring/pirl-spring/src/main/resources/application-local.yml \
   /Users/poku/orca/workspaces/pirl-spring/feat-swagger-refresh-hints/src/main/resources/
```

- [ ] **Step 2: 서버를 띄우고 스펙을 받는다**

```bash
cd /Users/poku/orca/workspaces/pirl-spring/feat-swagger-refresh-hints
./gradlew bootRun --args='--spring.profiles.active=local' &
sleep 40
curl -s localhost:8080/v3/api-docs/user > /tmp/user-api-docs.json
curl -s localhost:8080/v3/api-docs/admin > /tmp/admin-api-docs.json
```

- [ ] **Step 3: 기대 결과를 확인한다**

설계 문서 8.3절의 기대값입니다.

```bash
# POST /v1/runs 의 writes 에 Run 과 배지 관련 엔티티가 있어야 합니다.
jq '.paths."/v1/runs".post."x-entities"' /tmp/user-api-docs.json

# GET /v1/runs/{runId} 의 reads 에 Run, RunPartner, RunLocation 이 있어야 합니다.
jq '.paths."/v1/runs/{runId}".get."x-entities"' /tmp/user-api-docs.json

# 미해결 엔드포인트 목록입니다.
jq -r '.paths | to_entries[] | .key as $p | .value | to_entries[]
       | select(.value."x-entities".resolved == false)
       | "\(.key|ascii_upcase) \($p): \(.value."x-entities".unresolved | join(", "))"' \
   /tmp/user-api-docs.json | sort
```

확인할 것입니다.

- `POST /v1/runs` 의 `writes` 에 `Run`, `BadgeRunContribution`, `CrewMonthlyRunRecord` 가 있는가
- 같은 곳에 이벤트를 지나 도달하는 배지 엔티티가 있는가 (`RunService.createRun` 이
  `RunCompletedEvent` 를 발행하고 `BadgeEventListener` 가 받습니다)
- `GET /v1/runs/{runId}` 의 `reads` 에 `Run`, `RunPartner`, `RunLocation` 이 있는가
- 예약 슬롯 관련 write 가 `SlotInstance` 를 잡는가 (`SlotInstanceRepositoryImpl.upsert` 의
  네이티브 SQL 이 `slot_instance` 테이블명으로 역매핑되어야 합니다)
- 미해결 목록에 무엇이 있는가

미해결 목록에는 인증·헬스체크·프리사인드 URL 처럼 엔티티를 정말 건드리지 않는 엔드포인트가
나올 것으로 예상합니다 (DI 그래프 측정에서 컨트롤러 5개가 엔티티 0개였습니다). 그 밖의 것이
나오면 분석의 구멍이므로 사유를 보고 원인을 찾으십시오.

**첫 실행에서 기대와 다르면 계획이 아니라 코드를 고칩니다.** 어떤 판정이 왜 틀렸는지 코드
주석에 남기고, 그 경우를 재현하는 테스트를 코어에 추가한 뒤 고칩니다. 픽스처가 놓친 실제
패턴이므로 회귀 방지 가치가 높습니다.

- [ ] **Step 4: pirl-spring 의 검증용 변경을 되돌린다**

```bash
cd /Users/poku/orca/workspaces/pirl-spring/feat-swagger-refresh-hints
git checkout -- settings.gradle build.gradle
```

`application-local.yml` 은 gitignore 대상이라 그대로 두어도 됩니다.

- [ ] **Step 5: README 를 작성한다**

`README.md` 에 다음을 담습니다.

- **무엇을 하는가** — 엔드포인트마다 읽고 쓰는 JPA 엔티티를 자동으로 계산해 OpenAPI 의
  `x-entities` 확장으로 노출합니다
- **왜 짝짓기가 아니라 엔티티 집합인가** — 설계 문서 2.1절 요약. 소비자가 교집합을 계산합니다
- **설치** — 의존성 한 줄
- **소비자 예시** — `writes ∩ reads ≠ ∅` 로 react-query `invalidateQueries` 를 만드는 코드
- **출력 형식** — `reads`, `writes`, `resolved`, `unresolved` 의 의미와 `resolved: false` 를
  보수적으로 다루라는 지침
- **설정** — 프로퍼티 표
- **탈출구 어노테이션** — `@ReadsEntities`, `@WritesEntities`, `@InvalidationMapIgnore` 사용법
- **정확도와 한계** — 다음 네 가지를 명시합니다
  - 과잉 보고는 재조회 한 번이고 누락은 오래된 값이므로, 불확실하면 과잉 쪽으로 기울입니다
  - 비동기 쓰기(`@Async`, `@TransactionalEventListener(AFTER_COMMIT)`)는 `writes` 에 함께
    들어가지만 HTTP 응답 이후에 반영됩니다. 응답 직후 재조회하면 아직 안 바뀐 값을 읽을 수
    있습니다
  - MyBatis, 외부 캐시, 복잡한 네이티브 SQL 은 지원하지 않으며 미해결로 표시됩니다
  - 지원 범위는 Spring Boot 3.x 와 4.x, JVM 17 과 21 입니다
- **동작 원리** — Spring 메타데이터로 신원과 엔티티 대응을 받고 ASM 으로 호출 사슬만 분석한다는
  한 문단

- [ ] **Step 6: 커밋한다**

```bash
cd /Users/poku/projects/toktokhan/spring/spring-invalidation-map
git add README.md
git commit -m "$(cat <<'MSG'
docs: README 추가

무엇을 하는지, 엔티티 집합을 노출하는 이유, 설치와 소비자 예시, 출력 형식,
설정, 탈출구 어노테이션, 정확도와 한계를 담았습니다.

한계에는 비동기 쓰기가 응답 이후에 반영된다는 점과, MyBatis 같은 미지원 경로가
미해결로 표시된다는 점을 명시했습니다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
MSG
)"
```

---
