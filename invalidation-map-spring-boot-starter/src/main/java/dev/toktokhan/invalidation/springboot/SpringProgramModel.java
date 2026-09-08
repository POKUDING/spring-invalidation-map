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
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.support.RepositoryFragment;
import org.springframework.data.repository.support.Repositories;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
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
            // 반환값 자체를 이름으로 정렬해 고정합니다. fragmentImplementations 의 저장
            // 순서는 도메인 타입 정렬을 따르지만, getBeanDefinitionNames() 의 스캔 순서까지
            // JVM 재시작에 걸쳐 결정적이라는 보장은 없습니다.
            List<String> sorted = new ArrayList<>(found);
            sorted.sort(Comparator.naturalOrder());
            return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
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
     * <p>{@link #implementationsOf} 가 빈 팩토리 스캔과 별개로 이 색인도 함께 보는 이유는,
     * 프래그먼트 구현체가 항상 빈 팩토리에서 찾아지리라는 보장이 없기 때문입니다. 클래스패스
     * 이름 규칙으로 찾은 구현체는 DI 를 지원하려고 실제로 빈으로도 등록되는 경우가 있음을
     * 확인했지만(spring-data-commons 4.0.5, {@code RepositoryBeanDefinitionBuilder}), 이는
     * 구현 세부사항이지 계약이 아닙니다 — {@code RepositoryFragmentsContributor} 로 프로그램
     * 방식으로 조립된 프래그먼트처럼 빈 등록을 거치지 않는 경로도 있습니다. 이 색인은 빈
     * 팩토리가 못 찾는 경우를 대비한 별도의, 항상 성립하는 경로입니다.
     *
     * <p>{@code Repositories} 의 도메인 타입 순회와 {@code RepositoryInformation.getFragments()}
     * 는 둘 다 순서를 보장하지 않는 컬렉션(해시 기반 순회, {@code Set})을 돌려줍니다. 색인
     * 구축이 JVM 재시작에 걸쳐 같은 결과를 내도록 이름으로 정렬해 순회합니다.
     */
    private void buildRepositoryIndex() {
        Map<String, String> byRepository = new LinkedHashMap<>();
        Map<String, Set<String>> byFragment = new LinkedHashMap<>();
        // 한 번 충돌로 지워진 인터페이스 이름은 다시 채우지 않습니다. 이후에 같은 이름이
        // 또 다른 엔티티로 등장해도 계속 비워 둡니다 — 그러지 않으면 마지막에 등록된 값이
        // 우연히 남아 결국 하나를 찍는 것과 같아집니다.
        Set<String> conflicting = new LinkedHashSet<>();

        Repositories repositories = new Repositories(beanFactory);
        List<Class<?>> domainTypes = new ArrayList<>();
        for (Class<?> domainType : repositories) {
            domainTypes.add(domainType);
        }
        domainTypes.sort(Comparator.comparing(Class::getName));

        for (Class<?> domainType : domainTypes) {
            Optional<RepositoryInformation> information =
                repositories.getRepositoryInformationFor(domainType);
            if (information.isEmpty()) {
                continue;
            }
            RepositoryInformation info = information.get();
            String entity = MethodRefs.internalNameOf(domainType);
            Class<?> repositoryInterface = info.getRepositoryInterface();
            registerEntityMapping(byRepository, conflicting,
                MethodRefs.internalNameOf(repositoryInterface), entity);

            // 리포지토리 인터페이스 이름 규칙(레거시: `XxxRepository extends ..., XxxCustom` 에
            // `XxxRepositoryImpl` 을 붙이는 방식, pirl-spring 의 SlotInstanceRepository 가 이
            // 패턴입니다)에서는 getFragments() 의 signatureContributor 가 프래그먼트
            // 인터페이스가 아니라 구현체 클래스 자체를 돌려줍니다(실측: 리뷰 라운드 1). 그래서
            // 아래 프래그먼트 루프만으로는 이 경우 프래그먼트 인터페이스가 어떤 키로도
            // 등록되지 않아 entityFor 가 계약(ProgramModel.entityFor javadoc)을 어깁니다.
            // 리포지토리 인터페이스가 직접 선언한 인터페이스를 훑어 Spring Data 기반 타입이
            // 아닌 것을 같은 엔티티로 등록해 이 경로를 메웁니다. getInterfaces() 는 클래스
            // 파일에 선언된 순서 그대로를 돌려주므로(컴파일러가 결정, JVMS 상 고정) 정렬이
            // 필요 없습니다 — Set 을 돌려주는 getFragments() 와 다릅니다.
            for (Class<?> declaredInterface : repositoryInterface.getInterfaces()) {
                if (isSpringDataInfrastructureType(declaredInterface)) {
                    continue;
                }
                registerEntityMapping(byRepository, conflicting,
                    MethodRefs.internalNameOf(declaredInterface), entity);
            }

            List<RepositoryFragment<?>> fragments = new ArrayList<>(info.getFragments());
            fragments.sort(Comparator.comparing(
                fragment -> MethodRefs.internalNameOf(fragment.getSignatureContributor())));

            for (RepositoryFragment<?> fragment : fragments) {
                Class<?> contributor = fragment.getSignatureContributor();
                String contributorName = MethodRefs.internalNameOf(contributor);
                registerEntityMapping(byRepository, conflicting, contributorName, entity);

                // getImplementationClass() 는 Spring Data 4.x 에만 있습니다.
                // 3.x 와 4.x 모두에 있는 getImplementation() 을 씁니다.
                fragment.getImplementation().ifPresent(implementation -> byFragment
                    .computeIfAbsent(contributorName, key -> new LinkedHashSet<>())
                    .add(MethodRefs.internalNameOf(
                        ClassUtils.getUserClass(implementation.getClass()))));
            }
        }
        this.repositoryEntities = Collections.unmodifiableMap(new LinkedHashMap<>(byRepository));
        this.fragmentImplementations = Collections.unmodifiableMap(new LinkedHashMap<>(byFragment));
    }

    /**
     * 같은 인터페이스 이름이 서로 다른 엔티티에 대응되면 그 항목을 지도에서 뺍니다.
     *
     * <p>하나의 프래그먼트 인터페이스가 서로 다른 엔티티의 리포지토리 두 곳에 걸리는 경우가
     * 실제로 있습니다. 승자를 하나 찍으면 {@code Repositories} 의 순회 순서(보장 없음)에
     * 결과가 좌우되고, 찍히지 않은 쪽 엔티티는 조용히 누락됩니다(4.4 원칙 위반).
     *
     * <p>대신 빈 값으로 둡니다. {@code JpaRepositoryResolver} 는 {@code entityFor} 가 비면
     * {@code Optional.empty()} 를 돌려주고, 그러면 호출 사슬을 따라가는 워커가
     * {@link #implementationsOf} 로 받은 프래그먼트 구현체의 본문으로 내려가 그 안의 JPQL·
     * 네이티브 SQL 에서 실제 엔티티를 찾습니다. 찍는 것보다 정확하고, 거기서도 못 찾으면
     * unresolved 로 표시되어 이 역시 조용한 누락이 아닙니다.
     *
     * <p>패키지 접근입니다. {@code SpringProgramModelRegisterEntityMappingTest} 가 Spring
     * 컨텍스트 없이 이 로직만 직접 단정합니다 — 충돌 시나리오(서로 무관한 리포지토리 둘이
     * 같은 프래그먼트를 걸고 서로 다른 엔티티로 등록)를 픽스처 애플리케이션에 추가로 심지
     * 않고도 정확하게 검증할 수 있습니다.
     */
    static void registerEntityMapping(Map<String, String> byInterface,
        Set<String> conflicting, String interfaceInternalName, String entity) {
        if (conflicting.contains(interfaceInternalName)) {
            return;
        }
        String existing = byInterface.get(interfaceInternalName);
        if (existing == null) {
            byInterface.put(interfaceInternalName, entity);
            return;
        }
        if (!existing.equals(entity)) {
            byInterface.remove(interfaceInternalName);
            conflicting.add(interfaceInternalName);
        }
    }

    /**
     * {@code JpaRepository}, {@code CrudRepository}, {@code PagingAndSortingRepository} 처럼
     * Spring Data 가 제공하는 리포지토리 기반 타입인지 판별합니다. 이런 타입은 사용자가
     * 정의한 프래그먼트가 아니므로 엔티티 대응 후보에서 뺍니다.
     *
     * <p>패키지 접두어로 판별합니다. 사용자가 직접 선언하는 프래그먼트 인터페이스는 항상
     * 애플리케이션 자신의 패키지에 있고 {@code org.springframework.data} 아래에 있을 수
     * 없으므로, 이 기준은 사용자 프래그먼트를 잘못 걸러내지 않습니다.
     */
    private static boolean isSpringDataInfrastructureType(Class<?> type) {
        String packageName = type.getPackageName();
        return packageName.equals("org.springframework.data")
            || packageName.startsWith("org.springframework.data.");
    }

    /**
     * 엔티티와 임베더블을 모두 넣습니다. @Embedded 값 타입도 응답에 실리기 때문입니다.
     *
     * <p>{@code Metamodel.getEntities()}/{@code getEmbeddables()} 는 JPA 명세상 순회 순서를
     * 보장하지 않는 {@code Set} 을 돌려줍니다. {@code endpoints()}/{@code implementationsOf()}
     * 와 같은 기준(이름 정렬)으로 고정합니다.
     */
    private Set<String> buildEntities() {
        Set<String> found = new LinkedHashSet<>();
        Metamodel metamodel = entityManagerFactory.getMetamodel();
        metamodel.getEntities().forEach(type -> addJavaType(found, type.getJavaType()));
        metamodel.getEmbeddables().forEach(type -> addJavaType(found, type.getJavaType()));
        List<String> sorted = new ArrayList<>(found);
        sorted.sort(Comparator.naturalOrder());
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }

    private static void addJavaType(Set<String> target, Class<?> javaType) {
        if (javaType != null) {
            target.add(MethodRefs.internalNameOf(javaType));
        }
    }

    /**
     * {@code @TransactionalEventListener} 는 {@code @EventListener} 로 메타 어노테이션되어
     * 있으므로 한 번만 검사하면 둘 다 걸립니다.
     *
     * <p>{@code ReflectionUtils.getAllDeclaredMethods} 는 JDK 명세상 {@code Class.getMethods()}/
     * {@code getDeclaredMethods()} 의 순서를 보장하지 않습니다. 오너·이름·디스크립터 순으로
     * 정렬해 고정합니다 — {@code endpoints()} 가 {@link Endpoint} 를 정렬하는 것과 같은
     * 이유입니다.
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
        List<MethodRef> sorted = new ArrayList<>(found);
        sorted.sort(Comparator.comparing(MethodRef::owner)
            .thenComparing(MethodRef::name)
            .thenComparing(MethodRef::descriptor));
        return Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
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
