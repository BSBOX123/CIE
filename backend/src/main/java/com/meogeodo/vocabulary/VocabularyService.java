package com.meogeodo.vocabulary;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 고정 어휘 (SPEC 2장). DB가 정본이다.
 *
 * <p>질환·주의성분·알레르기 문자열이 백엔드·ai-service·프론트에 각자 하드코딩되면
 * 철자 하나 차이로 교집합이 빈 집합이 되어 판정이 <b>조용히</b> 실패한다.
 *
 * <p>값은 거의 바뀌지 않으므로 첫 조회 때 한 번 읽어 캐시한다. 기동 시점이
 * 아니라 지연 로딩인 이유는, 마이그레이션·초기화 순서에 따라 빈 생성 시점에
 * 테이블이 아직 없을 수 있기 때문이다.
 */
@Service
public class VocabularyService {

  private final JdbcTemplate jdbc;

  private volatile boolean loaded = false;

  private List<String> diseases = List.of();
  private List<String> cares = List.of();
  private List<String> allergens = List.of();
  private Map<String, String> careNotes = Map.of();
  private Map<String, List<String>> diseaseMap = Map.of();
  private List<String> requestPhrases = List.of();
  private Map<String, List<String>> careRequests = Map.of();
  private List<String> feedbackPhrases = List.of();
  private Map<String, String> feedbackFlags = Map.of();

  public VocabularyService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 캐시를 비운다. 어휘를 바꾼 뒤 다시 읽게 할 때 쓴다. */
  public synchronized void reload() {
    loaded = false;
    ensureLoaded();
  }

  private void ensureLoaded() {
    if (loaded) {
      return;
    }
    synchronized (this) {
      if (loaded) {
        return;
      }
      load();
      loaded = true;
    }
  }

  private void load() {
    diseases = jdbc.queryForList(
        "SELECT code FROM vocabulary_disease ORDER BY sort_order", String.class);
    cares = jdbc.queryForList(
        "SELECT code FROM vocabulary_care ORDER BY sort_order", String.class);
    allergens = jdbc.queryForList(
        "SELECT code FROM vocabulary_allergen ORDER BY sort_order", String.class);
    requestPhrases = jdbc.queryForList(
        "SELECT phrase FROM vocabulary_request_phrase ORDER BY sort_order", String.class);

    Map<String, String> notes = new LinkedHashMap<>();
    jdbc.query("SELECT code, note FROM vocabulary_care ORDER BY sort_order",
        rs -> { notes.put(rs.getString(1), rs.getString(2)); });
    careNotes = Map.copyOf(notes);

    diseaseMap = groupPairs(
        "SELECT d.disease, d.care FROM vocabulary_disease_care d"
            + " JOIN vocabulary_care c ON c.code = d.care"
            + " JOIN vocabulary_disease vd ON vd.code = d.disease"
            + " ORDER BY vd.sort_order, c.sort_order");
    careRequests = groupPairs(
        "SELECT care, phrase FROM vocabulary_care_request ORDER BY care, sort_order");

    feedbackPhrases = jdbc.queryForList(
        "SELECT phrase FROM vocabulary_feedback ORDER BY sort_order", String.class);
    Map<String, String> flagMap = new LinkedHashMap<>();
    jdbc.query("SELECT phrase, flag FROM vocabulary_feedback WHERE flag IS NOT NULL",
        rs -> { flagMap.put(rs.getString(1), rs.getString(2)); });
    feedbackFlags = Map.copyOf(flagMap);
  }

  private Map<String, List<String>> groupPairs(String sql) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    jdbc.query(sql, rs -> {
      out.computeIfAbsent(rs.getString(1), k -> new java.util.ArrayList<>()).add(rs.getString(2));
    });
    return Map.copyOf(out);
  }

  public List<String> diseases() {
    ensureLoaded();
    return diseases;
  }

  public List<String> cares() {
    ensureLoaded();
    return cares;
  }

  public List<String> allergens() {
    ensureLoaded();
    return allergens;
  }

  public Map<String, String> careNotes() {
    ensureLoaded();
    return careNotes;
  }

  public Map<String, List<String>> diseaseMap() {
    ensureLoaded();
    return diseaseMap;
  }

  public List<String> requestPhrases() {
    ensureLoaded();
    return requestPhrases;
  }

  public Map<String, List<String>> careRequests() {
    ensureLoaded();
    return careRequests;
  }

  /** 제보에 쓸 수 있는 피드백 문구 (SPEC 6.5). */
  public List<String> feedbackPhrases() {
    ensureLoaded();
    return feedbackPhrases;
  }

  /**
   * 피드백 문구 -> 식당 속성.
   *
   * <p>공공데이터로 채울 수 없는 flag 를 후기에서 도출하는 유일한 경로다.
   * 매핑이 없는 문구는 flag 로 이어지지 않는다.
   */
  public Map<String, String> feedbackFlags() {
    ensureLoaded();
    return feedbackFlags;
  }

  public boolean isKnownFeedback(String phrase) {
    ensureLoaded();
    return feedbackPhrases.contains(phrase);
  }

  /**
   * 질환에서 자동으로 따라오는 주의성분 (SPEC 2.3).
   *
   * <p>회원가입 4단계 진입 시 적용된다. 사용자는 개별 해제·추가할 수 있다.
   */
  public Set<String> autoCaresFor(Set<String> userDiseases) {
    ensureLoaded();
    Set<String> out = new LinkedHashSet<>();
    for (String disease : diseases) { // 정의된 순서를 따른다
      if (userDiseases.contains(disease)) {
        out.addAll(diseaseMap.getOrDefault(disease, List.of()));
      }
    }
    return out;
  }

  /** 주의성분에 대해 실제로 도움이 되는 요청 문구 (SPEC 2.6). */
  public List<String> requestsFor(String care) {
    ensureLoaded();
    return careRequests.getOrDefault(care, List.of());
  }

  public boolean isKnownDisease(String value) {
    ensureLoaded();
    return diseases.contains(value);
  }

  public boolean isKnownCare(String value) {
    ensureLoaded();
    return cares.contains(value);
  }

  public boolean isKnownAllergen(String value) {
    ensureLoaded();
    return allergens.contains(value);
  }
}
