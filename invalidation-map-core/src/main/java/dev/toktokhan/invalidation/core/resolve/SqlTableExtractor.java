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
            found.addAll(entities.entitiesForTable(unquote(tokens.get(i + 1))));
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
