package dev.toktokhan.invalidation.core.index;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link EntityIndex} 의 기본 테이블명 규칙 세 가지를 직접 확인합니다. 둘 다 조회 표에
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
    void springPhysicalNamingSnakeCase_lowercaseBeforeUppercaseBeforeLowercase_insertsUnderscore() {
        // 앞뒤가 모두 소문자인 대문자 앞에는 밑줄이 들어갑니다. 아래 "밑줄 없음" 케이스들과
        // 짝을 이뤄, 이 함수가 항상 소문자로만 바꾸는 게 아니라 실제로 밑줄을 삽입하는
        // 분기를 타는지 확인합니다.
        assertThat(EntityIndex.springPhysicalNamingSnakeCase("TripLeg")).isEqualTo("trip_leg");
    }

    @Test
    void springPhysicalNamingSnakeCase_multipleQualifyingBoundaries_insertsUnderscoreAtEach() {
        // 밑줄을 삽입한 뒤에도 반복문의 인덱스가 삽입된 밑줄과 그 다음 대문자를 건너뛰고
        // 정확한 자리에서 재개되는지 확인합니다. 인덱스 계산이 틀리면 O 와 T 사이,
        // 또는 A 와 u 사이 중 하나에서 밑줄이 빠지거나 겹칩니다.
        assertThat(EntityIndex.springPhysicalNamingSnakeCase("OAuthToken")).isEqualTo("oauth_token");
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
    @Test
    void hibernate7SnakeCase_uppercasePrecededByDigit_insertsUnderscore() {
        // Hibernate 6 계열은 앞 글자가 소문자일 때만 밑줄을 넣어 item2box 가 됩니다.
        // Hibernate 7 계열은 숫자도 소문자처럼 취급해 여기서 결과가 갈립니다.
        assertThat(EntityIndex.hibernate7SnakeCase("Item2Box")).isEqualTo("item2_box");
        assertThat(EntityIndex.springPhysicalNamingSnakeCase("Item2Box")).isEqualTo("item2box");
    }

    @Test
    void hibernate7SnakeCase_uppercaseFollowedByDigit_insertsUnderscore() {
        // 뒷 글자 조건에도 숫자가 들어갑니다.
        assertThat(EntityIndex.hibernate7SnakeCase("TripI2")).isEqualTo("trip_i2");
        assertThat(EntityIndex.springPhysicalNamingSnakeCase("TripI2")).isEqualTo("tripi2");
    }

    @Test
    void hibernate7SnakeCase_consecutiveUppercaseThenDigitBoundary_matchesRunningHibernate7() {
        // 두 클래스패스에서 Hibernate 가 실제로 만든 테이블명입니다 —
        // Boot 3.3.5(Hibernate 6.5.3) HTTPCACHE2ENTRY / Boot 4.0.6(Hibernate 7.2.12)
        // HTTPCACHE2_ENTRY. 연속 대문자와 숫자 경계를 함께 가진 이름에서만 갈리므로,
        // 이 조합이 두 규칙을 실제로 구분하는 유일한 형태입니다.
        assertThat(EntityIndex.hibernate7SnakeCase("HTTPCache2Entry")).isEqualTo("httpcache2_entry");
        assertThat(EntityIndex.springPhysicalNamingSnakeCase("HTTPCache2Entry"))
            .isEqualTo("httpcache2entry");
        // 순진한 규칙도 이 이름을 덮지 못합니다 — 그래서 세 번째 규칙이 필요합니다.
        assertThat(EntityIndex.camelToSnake("HTTPCache2Entry")).isEqualTo("h_t_t_p_cache2_entry");
    }

    @Test
    void hibernate7SnakeCase_plainCamelCase_matchesHibernate6Rule() {
        // 숫자가 없으면 두 규칙의 결과가 같아야 합니다. 조건을 잘못 넓히면 여기서 갈립니다.
        for (String name : new String[] {"TripLeg", "HTTPServer", "TripID", "tripleg", "X"}) {
            assertThat(EntityIndex.hibernate7SnakeCase(name))
                .as("숫자가 없는 이름 %s", name)
                .isEqualTo(EntityIndex.springPhysicalNamingSnakeCase(name));
        }
    }
}