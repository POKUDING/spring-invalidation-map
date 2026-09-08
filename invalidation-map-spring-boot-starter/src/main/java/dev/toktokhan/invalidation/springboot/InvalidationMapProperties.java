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
