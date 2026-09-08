package dev.toktokhan.invalidation.springboot.app.extra;

import dev.toktokhan.invalidation.springboot.app.SlotInstanceRepository;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 레거시 프래그먼트 쓰기({@link SlotInstanceRepository#touch}, 네이티브 SQL)와 이미 해결된
 * 다른 읽기({@link AlphaEntityRepository#findAll}) 가 같은 엔드포인트에 함께 있는 경우를
 * 검증하는 전용 컨트롤러입니다.
 *
 * <p>{@code SpringProgramModel.implementationsOf} 가 프래그먼트 구현체를 프래그먼트 계약
 * 이름으로만 색인하고 리포지토리 인터페이스 이름으로는 찾지 못하던 결함(Task 10 리뷰
 * 라운드 1, F1)이 정확히 이 모양에서 조용한 누락으로 드러났습니다 — {@code SlotInstance}
 * 쓰기가 {@code writes} 에도 {@code unresolved} 에도 나타나지 않고, {@code AlphaEntity}
 * 읽기만 있어 엔드포인트 전체가 "완전히 해결됨"으로 보고됐습니다. 이 컨트롤러가 그 회귀를
 * 막습니다.
 */
@RestController
@RequestMapping("/legacy-fragment-mix")
public class LegacyFragmentMixController {

    private final SlotInstanceRepository slots;
    private final AlphaEntityRepository alphas;

    public LegacyFragmentMixController(SlotInstanceRepository slots, AlphaEntityRepository alphas) {
        this.slots = slots;
        this.alphas = alphas;
    }

    @PutMapping("{id}")
    public void touch(@PathVariable Long id) {
        slots.touch(id);
        alphas.findAll();
    }
}
