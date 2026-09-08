package dev.toktokhan.invalidation.springboot;

import static org.assertj.core.api.Assertions.assertThat;

import dev.toktokhan.invalidation.core.Endpoint;
import dev.toktokhan.invalidation.core.MethodRef;
import dev.toktokhan.invalidation.core.MethodRefs;
import dev.toktokhan.invalidation.springboot.app.DeepEntity;
import dev.toktokhan.invalidation.springboot.app.DeepFragment;
import dev.toktokhan.invalidation.springboot.app.Note;
import dev.toktokhan.invalidation.springboot.app.NoteController;
import dev.toktokhan.invalidation.springboot.app.NoteCreatedEvent;
import dev.toktokhan.invalidation.springboot.app.NoteEventListener;
import dev.toktokhan.invalidation.springboot.app.NoteJpaRepository;
import dev.toktokhan.invalidation.springboot.app.NoteMetadata;
import dev.toktokhan.invalidation.springboot.app.NotePort;
import dev.toktokhan.invalidation.springboot.app.NotePortAdapter;
import dev.toktokhan.invalidation.springboot.app.NoteRepositoryCustom;
import dev.toktokhan.invalidation.springboot.app.NoteRepositoryCustomImpl;
import dev.toktokhan.invalidation.springboot.app.NoteTag;
import dev.toktokhan.invalidation.springboot.app.SlotInstance;
import dev.toktokhan.invalidation.springboot.app.SlotInstanceRepositoryCustom;
import dev.toktokhan.invalidation.springboot.app.TestApplication;
import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 픽스처 애플리케이션({@code app} 패키지)을 실제로 띄워 {@link SpringProgramModel} 이
 * Spring 이 이미 아는 사실만으로 {@code ProgramModel} 을 정확히 채우는지 확인합니다.
 */
@SpringBootTest(classes = TestApplication.class)
class SpringProgramModelTest {

    @Autowired
    private ConfigurableApplicationContext context;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private SpringProgramModel model;

    @BeforeEach
    void setUp() {
        model = new SpringProgramModel(context.getBeanFactory(), handlerMapping, entityManagerFactory,
            getClass().getClassLoader());
    }

    @Test
    void endpoints_restController_reportsHandlerMethodRefs() {
        List<MethodRef> handlers = model.endpoints().stream().map(Endpoint::handler).toList();

        assertThat(handlers).contains(
            methodRefOf("list"),
            methodRefOf("create", NoteController.NoteRequest.class),
            methodRefOf("update", Long.class, NoteController.NoteRequest.class),
            methodRefOf("health"));
    }

    @Test
    void endpoints_getMapping_reportsHttpMethodAndPath() {
        MethodRef listHandler = methodRefOf("list");
        Endpoint listEndpoint = model.endpoints().stream()
            .filter(endpoint -> endpoint.handler().equals(listHandler))
            .findFirst()
            .orElseThrow();

        assertThat(listEndpoint.httpMethod()).isEqualTo("GET");
        assertThat(listEndpoint.path()).isEqualTo("/notes");
    }

    @Test
    void entityFor_springDataRepository_resolvesEntity() {
        assertThat(model.entityFor(MethodRefs.internalNameOf(NoteJpaRepository.class)))
            .contains(MethodRefs.internalNameOf(Note.class));
    }

    @Test
    void entityFor_repositoryFragmentInterface_resolvesEntity() {
        assertThat(model.entityFor(MethodRefs.internalNameOf(NoteRepositoryCustom.class)))
            .contains(MethodRefs.internalNameOf(Note.class));
    }

    /**
     * pirl-spring 이 실제로 쓰는 리포지토리 인터페이스 이름 규칙(레거시:
     * {@code SlotInstanceRepositoryImpl implements SlotInstanceRepositoryCustom}, 리포지토리
     * 인터페이스는 {@link dev.toktokhan.invalidation.springboot.app.SlotInstanceRepository})
     * 입니다. 이 규칙에서는 {@code getFragments()} 의 signatureContributor 가 구현체 클래스를
     * 돌려주므로, {@code buildRepositoryIndex} 가 리포지토리 인터페이스의 직접 선언 인터페이스도
     * 훑어야만 통과합니다(리뷰 라운드 1, I1).
     */
    @Test
    void entityFor_legacyNamedFragmentInterface_resolvesEntity() {
        assertThat(model.entityFor(MethodRefs.internalNameOf(SlotInstanceRepositoryCustom.class)))
            .contains(MethodRefs.internalNameOf(SlotInstance.class));
    }

    /**
     * {@code @NoRepositoryBean} 베이스 리포지토리 인터페이스({@code DeepBaseRepository})가
     * 프래그먼트 인터페이스({@code DeepFragment})를 직접 선언하고, 실제 리포지토리 인터페이스
     * ({@code DeepRepository})는 그 베이스를 상속만 하는 구조입니다. {@code getInterfaces()}
     * 는 직접 선언만 돌려주므로, 상위 인터페이스까지 전이적으로 훑어야만 통과합니다(리뷰
     * 라운드 2, R1). Spring Data 표준 관용구라 pirl-spring 이 지금 이 패턴을 쓰지 않아도
     * 이 라이브러리의 계약(ProgramModel.entityFor)이 지켜야 하는 경우입니다.
     */
    @Test
    void entityFor_transitivelyInheritedFragmentInterface_resolvesEntity() {
        assertThat(model.entityFor(MethodRefs.internalNameOf(DeepFragment.class)))
            .contains(MethodRefs.internalNameOf(DeepEntity.class));
    }

    @Test
    void implementationsOf_beanAdapter_findsBean() {
        assertThat(model.implementationsOf(MethodRefs.internalNameOf(NotePort.class)))
            .contains(MethodRefs.internalNameOf(NotePortAdapter.class));
    }

    /**
     * Spring Data 는 클래스패스에서 이름으로 찾은 프래그먼트 구현체를 DI 를 위해 실제
     * 애플리케이션 빈으로도 등록합니다({@code NoteRepositoryCustomImpl} 도 확인:
     * {@code noteRepositoryCustomImpl} 이라는 이름의 빈이 실제로 등록됩니다 — spring-data-commons
     * 4.0.5 의 {@code RepositoryBeanDefinitionBuilder.potentiallyRegisterFragmentImplementation}).
     * 그래서 이 픽스처에서는 {@code implementationsOf} 의 빈 팩토리 스캔 절반만으로도 이
     * 단정이 우연히 통과합니다 — {@code fragmentImplementations} 색인을 완전히 비워도 결과가
     * 똑같습니다(뮤테이션으로 확인. 태스크 9 보고서 참고).
     *
     * <p>그래서 이 테스트는 그 빈 정의를 지운 뒤 다시 확인합니다. 리포지토리 프록시는 이미
     * 만들어질 때 프래그먼트 구현체 인스턴스를 붙잡아 뒀으므로({@code RepositoryComposition}),
     * 빈 정의를 지워도 {@code RepositoryInformation.getFragments()} 가 돌려주는 결과는
     * 그대로입니다 — 오직 {@code getBeanDefinitionNames()} 스캔에서만 사라집니다. 이렇게 하면
     * {@code fragmentImplementations} 색인이 실제로 이 결과를 만드는지 진짜로 검증됩니다.
     */
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void implementationsOf_repositoryFragment_findsNonBeanImplementation() {
        String fragmentImplBeanName = beanNameOf(NoteRepositoryCustomImpl.class);
        ((BeanDefinitionRegistry) context.getBeanFactory()).removeBeanDefinition(fragmentImplBeanName);

        SpringProgramModel isolated = new SpringProgramModel(context.getBeanFactory(), handlerMapping,
            entityManagerFactory, getClass().getClassLoader());

        assertThat(isolated.implementationsOf(MethodRefs.internalNameOf(NoteRepositoryCustom.class)))
            .contains(MethodRefs.internalNameOf(NoteRepositoryCustomImpl.class));
    }

    /** 빈 이름 짓기 규칙에 기대지 않도록, 타입으로 실제 등록된 빈 이름을 찾습니다. */
    private String beanNameOf(Class<?> type) {
        for (String beanName : context.getBeanFactory().getBeanDefinitionNames()) {
            Class<?> beanType;
            try {
                beanType = context.getBeanFactory().getType(beanName, false);
            } catch (RuntimeException e) {
                continue;
            }
            if (beanType != null && ClassUtils.getUserClass(beanType).equals(type)) {
                return beanName;
            }
        }
        throw new IllegalStateException("No bean registered for " + type);
    }

    @Test
    void entities_metamodel_includesEntitiesAndEmbeddables() {
        Set<String> entities = model.entities();

        assertThat(entities).contains(
            MethodRefs.internalNameOf(Note.class),
            MethodRefs.internalNameOf(NoteTag.class),
            MethodRefs.internalNameOf(NoteMetadata.class));
    }

    @Test
    void eventListeners_transactionalEventListener_isReported() {
        Method onNoteCreated = ReflectionUtils.findMethod(
            NoteEventListener.class, "onNoteCreated", NoteCreatedEvent.class);

        assertThat(model.eventListeners()).contains(MethodRefs.of(onNoteCreated));
    }

    @Test
    void classBytes_applicationClass_returnsBytes() {
        assertThat(model.classBytes(MethodRefs.internalNameOf(TestApplication.class))).isNotEmpty();
    }

    @Test
    void classBytes_missingClass_returnsEmpty() {
        assertThat(model.classBytes("dev/toktokhan/invalidation/springboot/app/DoesNotExist")).isEmpty();
    }

    private static MethodRef methodRefOf(String name, Class<?>... parameterTypes) {
        Method method = ReflectionUtils.findMethod(NoteController.class, name, parameterTypes);
        return MethodRefs.of(method);
    }
}
