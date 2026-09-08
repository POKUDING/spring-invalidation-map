package dev.toktokhan.invalidation.core.index;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link EntityIndex} 의 기본 테이블명 규칙 두 가지를 직접 확인합니다. 둘 다 조회 표에
 * 후보로 등록되므로({@code EntityIndex#buildTableIndex}), 어느 한쪽만 맞아도 조회는
 * 성공합니다. 그래서 이 규칙들은 통합 테스트({@code EntityIndexTest})만으로는 서로 다른
 * 결과를 내는지 확인할 수 없고, 함수 하나씩 직접 불러야 합니다.
 */
class TableNameRulesTest {

    @Test
    void camelToSnake_consecutiveUppercase_insertsUnderscoreBeforeEveryUppercase() {
        assertThat(EntityIndex.camelToSnake("HTTPServer")).isEqualTo("h_t_t_p_server");
    }

    @Test
    void camelToSnake_endsWithUppercase_insertsUnderscoreBeforeTrailingUppercase() {
        assertThat(EntityIndex.camelToSnake("TripID")).isEqualTo("trip_i_d");
    }

    @Test
    void camelToSnake_containsDigit_treatsDigitLikeLowercase() {
        assertThat(EntityIndex.camelToSnake("Item2Box")).isEqualTo("item2_box");
    }

    @Test
    void camelToSnake_alreadyLowercase_returnsUnchanged() {
        assertThat(EntityIndex.camelToSnake("tripleg")).isEqualTo("tripleg");
    }

    @Test
    void camelToSnake_singleLetterName_lowercasesWithoutUnderscore() {
        assertThat(EntityIndex.camelToSnake("X")).isEqualTo("x");
    }

    @Test
    void springPhysicalNamingSnakeCase_consecutiveUppercase_insertsNoUnderscoreBetweenThem() {
        // 앞 글자가 소문자이고 뒷 글자도 소문자일 때만 밑줄을 넣습니다. HTTPServer 는
        // 대문자가 네 번 연속이라 그 사이 어디에도 앞뒤 조건을 만족하는 자리가 없습니다.
        assertThat(EntityIndex.springPhysicalNamingSnakeCase("HTTPServer")).isEqualTo("httpserver");
    }

    @Test
    void springPhysicalNamingSnakeCase_endsWithUppercase_insertsNoUnderscoreBeforeTrailingUppercase() {
        // TripID 의 I 는 뒷 글자가 대문자(D)라 조건을 만족하지 않고, D 는 뒷 글자가 없어
        // 아예 판정 대상이 아닙니다.
        assertThat(EntityIndex.springPhysicalNamingSnakeCase("TripID")).isEqualTo("tripid");
    }

    @Test
    void springPhysicalNamingSnakeCase_uppercasePrecededByDigit_insertsNoUnderscore() {
        // 숫자는 소문자가 아니므로, 대문자 앞이 숫자면 밑줄이 들어가지 않습니다.
        assertThat(EntityIndex.springPhysicalNamingSnakeCase("Item2Box")).isEqualTo("item2box");
    }

    @Test
    void springPhysicalNamingSnakeCase_alreadyLowercase_returnsUnchanged() {
        assertThat(EntityIndex.springPhysicalNamingSnakeCase("tripleg")).isEqualTo("tripleg");
    }

    @Test
    void springPhysicalNamingSnakeCase_singleLetterName_lowercasesWithoutUnderscore() {
        assertThat(EntityIndex.springPhysicalNamingSnakeCase("X")).isEqualTo("x");
    }
}
