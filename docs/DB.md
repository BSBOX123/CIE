# DB 접속·조회 안내

## 접속

**컨테이너 안에서 (권장)**
```bash
docker exec -it meogeodo-db mysql --default-character-set=utf8mb4 -umeogeodo -pmeogeodo meogeodo
```

**호스트에서**
```bash
mysql -h 127.0.0.1 -P 3307 --default-character-set=utf8mb4 -umeogeodo -pmeogeodo meogeodo
```

### 접속 정보

| 항목 | 값 |
|---|---|
| 버전 | MySQL 8.0 (도커) |
| 호스트 | `127.0.0.1` |
| 포트 | **3307** |
| DB | `meogeodo` |
| 계정 | `meogeodo` / `meogeodo` |

### ⚠️ 함정 두 가지

**`localhost` 를 쓰면 안 됩니다.** MySQL 클라이언트는 `-h localhost` 일 때
TCP 가 아니라 유닉스 소켓으로 붙고 **`-P` 를 무시합니다.** 그러면 이 맥에 따로
설치된 MySQL 9.2(3306)로 가버립니다. 에러도 안 나서 알아채기 어렵습니다.
반드시 `127.0.0.1` 을 쓰세요.

**`--default-character-set=utf8mb4` 를 빼면 한글이 `???` 로 보입니다.**
데이터가 깨진 게 아니라 클라이언트 표시 문제입니다.

### 짧게 쓰기

`~/.zshrc` 에 넣어 두면 `meogdb` 한 단어로 붙습니다.

```bash
alias meogdb='docker exec -it meogeodo-db mysql --default-character-set=utf8mb4 -umeogeodo -pmeogeodo meogeodo'
```

## 자주 쓰는 쿼리

접속한 뒤 붙여 넣으면 됩니다.

### 전체 현황
```sql
SELECT '식당' 항목, COUNT(*) 건수 FROM restaurant
UNION ALL SELECT '메뉴', COUNT(*) FROM menu
UNION ALL SELECT '음식', COUNT(*) FROM dish
UNION ALL SELECT '  └ 태깅됨', COUNT(*) FROM dish WHERE tagged_at IS NOT NULL
UNION ALL SELECT '음식 태그', COUNT(*) FROM dish_tag
UNION ALL SELECT '사용자', COUNT(*) FROM app_user
UNION ALL SELECT '제보', COUNT(*) FROM review
UNION ALL SELECT '식당 속성', COUNT(*) FROM restaurant_flag
UNION ALL SELECT '주문카드', COUNT(*) FROM order_card;
```

### 식당과 메뉴
```sql
SELECT r.id, r.name 식당, r.area 지역, r.lat, r.lng, r.open_time 영업
FROM restaurant r LIMIT 10;

SELECT r.name 식당, m.raw_name 메뉴, d.normalized_name 정규화,
       IF(d.tagged_at IS NULL,'미태깅','태깅됨') 상태
FROM menu m
JOIN restaurant r ON r.id = m.restaurant_id
LEFT JOIN dish d ON d.id = m.dish_id
LIMIT 20;
```

### 태깅 결과 (태깅 후에 의미 있음)
```sql
SELECT d.normalized_name 음식, t.tag_type 종류, t.tag_value 값,
       t.amount 정도, t.source 출처, t.confidence 신뢰도
FROM dish_tag t JOIN dish d ON d.id = t.dish_id
ORDER BY d.normalized_name LIMIT 30;

-- 검수가 필요한 음식
SELECT normalized_name, model_id, tagged_at
FROM dish WHERE needs_review = TRUE LIMIT 20;
```

### 제보와 식당 속성
```sql
SELECT v.id, r.name 식당, IF(v.is_ok,'○','✕') 평가, v.note 메모,
       (SELECT GROUP_CONCAT(phrase) FROM review_request q WHERE q.review_id=v.id) 주문방법,
       (SELECT GROUP_CONCAT(phrase) FROM review_feedback f WHERE f.review_id=v.id) 피드백
FROM review v JOIN restaurant r ON r.id = v.restaurant_id;

SELECT r.name 식당, f.flag 속성, f.source 출처
FROM restaurant_flag f JOIN restaurant r ON r.id = f.restaurant_id;
```

### 사용자
```sql
SELECT u.id, u.login_id, p.name, p.birth_year, u.created_at
FROM app_user u LEFT JOIN user_profile p ON p.user_id = u.id;
```

> **건강정보는 조회해도 읽을 수 없습니다.** 질환·주의성분·알레르기·복용약은
> AES-GCM 으로 암호화되어 저장됩니다(개인정보보호법상 민감정보). SQL 로는
> 암호문만 보이며, 복호화는 애플리케이션이 합니다. 이건 정상 동작입니다.
>
> 매번 다른 IV 를 쓰므로 같은 값도 암호문이 매번 다릅니다. 따라서
> **암호화된 컬럼으로는 검색·집계를 할 수 없습니다.**

## 관리

```bash
# 상태 확인
docker compose ps

# 로그
docker compose logs -f db

# 중지 (데이터 유지)
docker compose stop db

# 완전 초기화 — 데이터가 전부 사라집니다
docker compose down -v && docker compose up -d db
```

초기화한 뒤 강릉 데이터를 다시 넣으려면:
```bash
cd backend
set -a; source .env; set +a
export PUBLIC_DATA_API_KEY=$(grep PUBLIC_DATA_API_KEY ../ai-service/.env | cut -d= -f2)
SEED_GANGNEUNG=1 ./gradlew test --tests '*SeedGangneungTest'
```

## GUI 도구 (DBeaver, TablePlus 등)

```
Host      127.0.0.1     ← localhost 아님
Port      3307
Database  meogeodo
User      meogeodo
Password  meogeodo
```
