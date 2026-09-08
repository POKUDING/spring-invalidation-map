package dev.toktokhan.invalidation.springboot.app.extra;

import dev.toktokhan.invalidation.springboot.app.SlotInstanceRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code x-entities} 의 정렬 계약을 검증하기 위한 전용 엔드포인트입니다. {@link AlphaEntity}
 * javadoc 에 적은 이유로, 정렬 유무가 실제로 관찰 가능하려면 서로 다른 패키지의, 연관이 없는
 * 엔티티 조합이 필요합니다.
 */
@RestController
@RequestMapping("/sort-check")
public class SortOrderController {

    private final SlotInstanceRepository slots;
    private final AlphaEntityRepository alphas;

    public SortOrderController(SlotInstanceRepository slots, AlphaEntityRepository alphas) {
        this.slots = slots;
        this.alphas = alphas;
    }

    @GetMapping
    public List<Object> combined() {
        List<Object> result = new ArrayList<>();
        result.addAll(slots.findAll());
        result.addAll(alphas.findAll());
        return result;
    }
}
