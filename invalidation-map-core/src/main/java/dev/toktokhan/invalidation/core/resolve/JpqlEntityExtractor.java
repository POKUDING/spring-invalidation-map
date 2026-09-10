package dev.toktokhan.invalidation.core.resolve;

import dev.toktokhan.invalidation.core.AccessKind;
import dev.toktokhan.invalidation.core.index.ClassRepository;
import dev.toktokhan.invalidation.core.index.EntityIndex;
import java.util.Collections;
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
            if (!isTarget) {
                continue;
            }
            int targetIndex = i + 1;
            if (keyword.equals("join") && targetIndex < tokens.size()
                && tokens.get(targetIndex).equalsIgnoreCase("fetch")) {
                // JOIN FETCH t.legs l — FETCH 는 즉시 로딩 힌트일 뿐 조인 대상이 아닙니다.
                // 건너뛰지 않으면 target 이 "fetch" 가 되어 조인 대상을 통째로 놓칩니다.
                targetIndex++;
            }
            if (targetIndex >= tokens.size()) {
                continue;
            }
            String target = tokens.get(targetIndex);
            String alias = aliasAfter(tokens, targetIndex + 1);

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
