package com.meogeodo.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 어휘 정본 테스트.
 *
 * <p>실제 마이그레이션 파일(V4)을 그대로 실행해 검증하므로, 시드가 바뀌면
 * 여기서 잡힌다.
 */
@SpringBootTest
@ActiveProfiles("test")
class VocabularyServiceTest {

  @Autowired private VocabularyService vocabulary;

  @Test
  @DisplayName("SPEC 2장 어휘 개수가 맞는다")
  void counts() {
    assertThat(vocabulary.diseases()).hasSize(5);
    assertThat(vocabulary.cares()).hasSize(7);
    assertThat(vocabulary.allergens()).hasSize(19);
  }

  @Test
  @DisplayName("알레르기는 식약처 19종 전체다 (D11 확장)")
  void allergensExtended() {
    assertThat(vocabulary.allergens()).contains("아황산류", "잣").hasSize(19);
  }

  @Test
  @DisplayName("모든 주의성분에 안내 문구가 있다")
  void everyCareHasNote() {
    for (String care : vocabulary.cares()) {
      assertThat(vocabulary.careNotes().get(care)).as(care).isNotBlank();
    }
  }

  @Test
  @DisplayName("만성콩팥병은 나트륨·칼륨·단백질량을 모두 부른다")
  void ckdMapping() {
    assertThat(vocabulary.autoCaresFor(Set.of("만성콩팥병")))
        .containsExactlyInAnyOrder("나트륨", "칼륨", "단백질량");
  }

  @Test
  @DisplayName("질환이 여럿이면 주의성분이 합쳐지고 중복은 하나로 모인다")
  void multipleDiseases() {
    assertThat(vocabulary.autoCaresFor(Set.of("당뇨", "고혈압")))
        .containsExactlyInAnyOrder("당류", "정제 탄수화물", "나트륨", "포화지방");
    assertThat(vocabulary.autoCaresFor(Set.of("고혈압", "통풍")))
        .containsExactlyInAnyOrder("나트륨", "포화지방", "퓨린");
  }

  @Test
  @DisplayName("질환 5종이 모두 매핑을 가진다")
  void allDiseasesMapped() {
    for (String disease : vocabulary.diseases()) {
      assertThat(vocabulary.autoCaresFor(Set.of(disease))).as(disease).isNotEmpty();
    }
  }

  @Test
  @DisplayName("매핑된 주의성분은 모두 어휘 안에 있다")
  void mappingStaysInVocabulary() {
    vocabulary.diseaseMap().values().forEach(cares ->
        assertThat(vocabulary.cares()).containsAll(cares));
  }

  @Test
  @DisplayName("주의성분별 권장 요청 문구가 있다")
  void careRequests() {
    assertThat(vocabulary.requestsFor("나트륨"))
        .contains("국물은 따로 담아 주세요", "소금·간장은 반만 넣어 주세요");
    assertThat(vocabulary.requestsFor("칼륨")).contains("채소는 데쳐 주세요");
  }

  @Test
  @DisplayName("기본 요청 문구 6종")
  void requestPhrases() {
    assertThat(vocabulary.requestPhrases()).hasSize(6).contains("국물은 따로 담아 주세요");
  }

  @Test
  @DisplayName("모르는 값은 걸러낸다")
  void rejectsUnknown() {
    assertThat(vocabulary.isKnownDisease("당뇨")).isTrue();
    assertThat(vocabulary.isKnownDisease("당뇨병")).isFalse();
    assertThat(vocabulary.isKnownAllergen("잣")).isTrue();
    assertThat(vocabulary.isKnownCare("나트륨 ")).isFalse();
  }
}
