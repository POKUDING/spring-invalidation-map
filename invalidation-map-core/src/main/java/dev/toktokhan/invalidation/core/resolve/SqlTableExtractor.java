package dev.toktokhan.invalidation.core.resolve;

import dev.toktokhan.invalidation.core.AccessKind;
import dev.toktokhan.invalidation.core.index.EntityIndex;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 네이티브 SQL 문자열에서 테이블명을 뽑아 엔티티로 역매핑합니다.
 *
 * <p>{@code FROM}, {@code INTO}, {@code UPDATE}, {@code JOIN} 뒤의 토큰을 테이블명 후보로 보고
 * {@link EntityIndex#entitiesForTable(String)} 에 물어봅니다. 풀리지 않은 후보는 버립니다.
 * 같은 테이블명 후보가 여러 엔티티와 겹치면 {@code entitiesForTable} 이 전부 돌려주므로,
 * 여기서도 그중 하나만 취하지 않고 전부 결과에 담습니다.
 *
 * <p>{@code FROM} 과 {@code UPDATE} 는 쉼표로 여러 테이블을 나열할 수 있습니다 — {@code FROM} 은
 * 암묵적 조인(예: {@code from a, b}), {@code UPDATE} 는 MySQL 의 다중 테이블 갱신 문법(예:
 * {@code UPDATE a, b SET a.x = b.y}) 입니다. {@code UPDATE} 는 쓰기이므로 둘째 테이블을 놓치면
 * 쓰기 자체가 누락됩니다. 토큰화 단계에서 쉼표는 경계로만 쓰이고 사라지므로, 두 키워드 모두
 * 그 뒤부터 다음 SQL 키워드가 나올 때까지 토큰을 전부 후보로 봅니다. 별칭은 어차피 테이블명으로
 * 안 풀려 저절로 걸러지고, 별칭이 우연히 다른 테이블명과 같아도 과잉이 하나 생길 뿐이라 안전한
 * 방향입니다.
 *
 * <p>{@code JOIN} 은 표준 SQL 문법상 대상이 항상 하나뿐이라 바로 다음 토큰만 봅니다.
 * {@code INTO} 도 바로 다음 토큰만 봅니다 — {@code INSERT INTO a (cols) VALUES ...} 형태에서
 * 괄호는 토큰화 단계에서 사라지므로, {@code UPDATE} 처럼 다음 키워드까지 전부 훑으면 괄호 안
 * 컬럼명이 키워드 경계 없이 테이블 후보로 섞여 들어갑니다. {@code INSERT INTO} 에는 다중 테이블
 * 문법 자체가 없으므로 여기서는 단일 토큰만 보는 편이 더 정확합니다.
 */
public final class SqlTableExtractor {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z_][A-Za-z0-9_.\"`]*");
    private static final Set<String> TABLE_KEYWORDS = Set.of("from", "into", "update", "join");

    /** {@code FROM} 뒤 테이블 목록이 여기서 끝난다고 보는 키워드입니다. */
    private static final Set<String> FROM_LIST_STOP_KEYWORDS = Set.of(
        "where", "on", "group", "order", "having", "union", "limit");

    /** {@code UPDATE} 뒤 테이블 목록이 여기서 끝난다고 보는 키워드입니다. {@code SET} 이 더 있습니다. */
    private static final Set<String> UPDATE_LIST_STOP_KEYWORDS = Set.of(
        "set", "where", "on", "group", "order", "having", "union", "limit");

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
            String keyword = tokens.get(i).toLowerCase(Locale.ROOT);
            if (!TABLE_KEYWORDS.contains(keyword)) {
                continue;
            }
            if (keyword.equals("from")) {
                collectTableList(tokens, i + 1, FROM_LIST_STOP_KEYWORDS, entities, found);
                continue;
            }
            if (keyword.equals("update")) {
                collectTableList(tokens, i + 1, UPDATE_LIST_STOP_KEYWORDS, entities, found);
                continue;
            }
            // INTO / JOIN 은 대상이 하나뿐이라 바로 다음 토큰만 봅니다. 클래스 javadoc 참고.
            found.addAll(entities.entitiesForTable(unquote(tokens.get(i + 1))));
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(found));
    }

    /**
     * {@code start} 부터 {@code stopKeywords} (또는 다른 {@link #TABLE_KEYWORDS}) 를 만날 때까지
     * 토큰을 전부 테이블명 후보로 봅니다. {@code FROM} 과 {@code UPDATE} 가 공유하는 로직입니다.
     */
    private static void collectTableList(List<String> tokens, int start, Set<String> stopKeywords,
        EntityIndex entities, Set<String> found) {
        for (int j = start; j < tokens.size(); j++) {
            String candidate = tokens.get(j).toLowerCase(Locale.ROOT);
            if (TABLE_KEYWORDS.contains(candidate) || stopKeywords.contains(candidate)) {
                break;
            }
            found.addAll(entities.entitiesForTable(unquote(tokens.get(j))));
        }
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
