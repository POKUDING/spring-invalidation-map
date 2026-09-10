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

    /**
     * Spring Data 리포지토리 타입이 다루는 엔티티입니다. 리포지토리가 아니면 빈 값입니다.
     *
     * <p>구현은 **프래그먼트 인터페이스도 같은 엔티티로 등록해야 합니다.**
     * {@code SlotInstanceRepositoryCustom} 처럼 Spring Data 가 직접 리포지토리로 보지 않는
     * 인터페이스에 대고 호출이 일어나기 때문입니다.
     */
    Optional<String> entityFor(String repositoryInternalName);

    /**
     * 인터페이스를 구현하는 타입입니다.
     *
     * <p>런타임 구현은 두 곳에서 찾아야 합니다. 빈으로 등록된 구현체와, Spring Data 리포지토리
     * 프래그먼트 색인입니다. 클래스패스 이름 규칙으로 찾은 프래그먼트 구현체가 DI 를
     * 지원하려고 실제로 빈 팩토리에도 등록되는 경우가 있음을 확인했지만(spring-data-commons
     * 4.0.5, 프래그먼트 인터페이스 이름 규칙과 리포지토리 인터페이스 이름 규칙(레거시) 둘 다),
     * 이는 구현 세부사항이지 계약이 아닙니다. {@code RepositoryFactoryBeanSupport} 의
     * {@code setRepositoryFragments}(spring-data-commons 3.3.5, 4.0.5 양쪽 모두에 있습니다)처럼
     * 프로그램 방식으로 조립되는 프래그먼트는 클래스패스 이름 규칙 탐지 자체를 거치지 않으므로
     * 빈 등록도 거치지 않습니다. 4.0.5 는 같은 목적의 API 로
     * {@code setRepositoryFragmentsFunction} 과 {@code RepositoryFragmentsContributor} 를
     * 추가로 제공하지만, 둘 다 4.0.5 전용입니다. 빈 팩토리만 보면 이런 경로를 놓칠 수 있습니다.
     */
    Set<String> implementationsOf(String interfaceInternalName);

    /** JPA 가 관리하는 엔티티 전체입니다. */
    Set<String> entities();

    /**
     * {@code @EventListener} 또는 {@code @TransactionalEventListener} 가 붙은 메서드 전체입니다.
     *
     * <p>{@code publishEvent} 호출 지점에서 어떤 리스너로 이어붙일지 판단하려면 리스너를
     * 열거할 수 있어야 합니다. 런타임 구현은 빈 타입을 훑어 모으면 됩니다.
     */
    Set<MethodRef> eventListeners();
}
