# EC2 배포 안내

전제: Ubuntu 22.04/24.04, 프리티어(1GB) 기준. 도메인 `mukeodo.site` 보유.
백엔드는 `api.mukeodo.site` 로 서비스한다.

---

## 0. 배포 전 체크

- [ ] 로컬 변경사항을 전부 push 했는가 (`git status` 가 깨끗한가)
- [ ] `gradle-wrapper.jar` 가 저장소에 있는가 — 없으면 서버에서 `./gradlew` 가 죽는다
      ```bash
      git ls-files backend/gradle/wrapper/gradle-wrapper.jar
      ```
- [ ] 탄력적 IP(Elastic IP)를 할당했는가 — 없으면 재부팅할 때마다 IP 가 바뀌어
      DNS 를 다시 잡아야 한다

### 보안 그룹 (인바운드)

| 포트 | 소스 | 용도 |
|---|---|---|
| 22 | **내 IP 만** | SSH |
| 80 | 0.0.0.0/0 | certbot 인증 + https 리다이렉트 |
| 443 | 0.0.0.0/0 | API |

**8080 · 8000 · 3307 은 절대 열지 않는다.** 컨테이너가 `127.0.0.1` 에만 묶여
있지만, 보안 그룹까지 열면 의미가 없어진다.

---

## 1. 서버 준비

### 스왑 (1GB 서버는 필수)

없으면 Gradle 빌드가 OOM 으로 죽는다.

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h        # Swap 2.0Gi 확인
```

### 도커

```bash
sudo apt update && sudo apt install -y docker.io docker-compose-v2 git nginx
sudo usermod -aG docker $USER
newgrp docker   # 또는 로그아웃 후 재접속
docker ps       # 권한 확인
```

---

## 2. 코드와 설정

```bash
git clone https://github.com/BSBOX123/CIE.git
cd CIE
```

`.env` 를 만든다. **저장소에 없으므로 직접 작성해야 한다.**

```bash
cp .env.example .env
nano .env
```

채울 값:

```bash
# 새로 생성 — 로컬 값과 달라도 된다(데이터를 안 옮긴다면)
MEOGEODO_ENCRYPTION_KEY=   # openssl rand -base64 32
JWT_SECRET=                # openssl rand -base64 48
DB_PASSWORD=               # openssl rand -base64 24
DB_ROOT_PASSWORD=          # openssl rand -base64 24
DB_USERNAME=meogeodo

# 기존 키 그대로
GEMINI_API_KEY=
PUBLIC_DATA_API_KEY=
LLM_MODEL_ID=gemini-3.5-flash-lite
GEMINI_RPM=10

# 배포 주소
CORS_ALLOWED_ORIGINS=https://mukeodo.site
PUBLIC_BASE_URL=https://api.mukeodo.site
```

> ⚠️ **`MEOGEODO_ENCRYPTION_KEY` 는 잃으면 사용자 건강정보를 영영 복호화할 수
> 없다.** 생성 직후 안전한 곳에 따로 보관할 것. 로컬 DB 를 서버로 옮길
> 계획이면 **로컬과 같은 키**를 써야 한다.

> `DB_PASSWORD` 는 `.env.example`·`docs/DB.md` 의 `meogeodo` 를 절대 그대로
> 쓰지 말 것. 저장소가 public 이라 누구나 읽을 수 있다.

```bash
chmod 600 .env
```

---

## 3. 실행

```bash
docker compose -f docker-compose.prod.yml up -d --build
```

첫 빌드는 **10~20분** 걸린다(1GB + 스왑). Gradle 이 의존성을 받고 컴파일한다.

```bash
docker compose -f docker-compose.prod.yml ps        # 세 개 다 Up 인지
docker compose -f docker-compose.prod.yml logs -f backend
```

`Started MeogeodoApplication` 이 보이면 뜬 것이다. Flyway 가 V1~V6 을 자동으로
적용한다.

```bash
curl -s localhost:8080/api/reports/options | head -c 200   # 인증 없이 200
```

---

## 4. HTTPS

**DNS 먼저.** `api.mukeodo.site` A 레코드를 탄력적 IP 로 지정하고, 전파를
확인한 뒤 certbot 을 돌린다. 전파 전에 돌리면 인증이 실패한다.

```bash
dig +short api.mukeodo.site     # 탄력적 IP 가 나와야 함
```

```bash
sudo cp infra/nginx-api.conf /etc/nginx/sites-available/api.mukeodo.site
sudo ln -s /etc/nginx/sites-available/api.mukeodo.site /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx

sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d api.mukeodo.site
```

certbot 이 443 블록과 http→https 리다이렉트를 자동으로 넣는다. 갱신은
`certbot.timer` 가 알아서 한다.

```bash
curl -s https://api.mukeodo.site/api/reports/options | head -c 200
```

> **프론트가 https 인데 백엔드가 http 면 브라우저가 전부 차단한다**(mixed
> content). 이 단계를 건너뛸 수 없다.

---

## 5. 데이터 넣기

배포 직후 DB 는 비어 있다. 강릉 데이터를 넣어야 검색에 뭔가 나온다.

**로컬에서 SSH 터널로 넣는 방법** (서버에 테스트 도구가 필요 없다)

```bash
# 터미널 1 — 터널
ssh -i <키> -L 3308:127.0.0.1:3307 ubuntu@api.mukeodo.site

# 터미널 2 — 로컬에서 실행
cd backend
set -a; source .env; set +a
export DB_URL="jdbc:mysql://127.0.0.1:3308/meogeodo?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC"
export DB_PASSWORD=<서버 .env 의 DB_PASSWORD>
export PUBLIC_DATA_API_KEY=$(grep PUBLIC_DATA_API_KEY ../ai-service/.env | cut -d= -f2)
SEED_GANGNEUNG=1 ./gradlew test --tests '*SeedGangneungTest'
```

→ 식당 100 / 메뉴 416 / 음식 348

> **태깅은 여전히 0건이다.** Gemini 지출 한도가 풀리기 전까지 판정(`seal`)이
> 전부 `null` 로 나간다. 프론트는 이 경우 배지를 그리지 않고 "분석 전" 으로
> 표시해야 한다.

---

## 6. 프론트에 넘길 것

```
API 서버     https://api.mukeodo.site
Swagger UI   https://api.mukeodo.site/swagger-ui.html
OpenAPI      https://api.mukeodo.site/v3/api-docs
허용 출처     https://mukeodo.site
```

프론트를 로컬에서 띄워 붙일 때는 서버 `.env` 에 그 주소를 추가하고 백엔드만
재시작한다.

```bash
CORS_ALLOWED_ORIGINS=https://mukeodo.site,http://localhost:5173
docker compose -f docker-compose.prod.yml up -d backend
```

---

## 7. 다시 배포할 때

```bash
cd CIE
git pull
docker compose -f docker-compose.prod.yml up -d --build
```

`.env` 와 DB 볼륨은 그대로 유지된다.

---

## 문제가 생기면

| 증상 | 확인 |
|---|---|
| 빌드가 멈추거나 죽음 | 스왑이 잡혔는지 (`free -h`). 없으면 1단계로 |
| backend 가 계속 재시작 | `logs backend`. 대개 `.env` 키 누락 또는 DB 미기동 |
| `Unknown database` | db 컨테이너가 healthy 인지. 첫 기동은 40초쯤 걸린다 |
| 프론트에서 CORS 오류 | `CORS_ALLOWED_ORIGINS` 가 프론트 주소와 **정확히** 같은지. `www` 와 `http` 는 별개 출처다 |
| nginx 502 | backend 컨테이너가 떠 있는지. `curl localhost:8080/api/reports/options` |
| Swagger 가 http 로 호출 | `PUBLIC_BASE_URL` 이 https 인지, nginx 가 `X-Forwarded-Proto` 를 넘기는지 |
| 메모리 부족 | `docker stats`. mem_limit 은 db 420m / backend 380m / ai 220m |

```bash
# DB 들여다보기
docker exec -it meogeodo-db mysql --default-character-set=utf8mb4 -umeogeodo -p meogeodo
```
