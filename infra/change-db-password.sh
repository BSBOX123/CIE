#!/bin/bash
# 운영 DB 비밀번호 변경.
#
#   ./infra/change-db-password.sh
#
# 두 곳을 함께 바꿔야 한다. 하나만 바꾸면 백엔드가 DB 에 못 붙는다.
#   1) MySQL 안의 meogeodo 계정 비밀번호
#   2) 서버 ~/CIE/.env 의 DB_PASSWORD
#
# compose 의 MYSQL_PASSWORD 는 DB 를 처음 만들 때만 쓰인다. .env 만 고쳐도
# 이미 만들어진 계정의 비밀번호는 바뀌지 않는다.
#
# 새 비밀번호는 화면에 찍지 않고, SSM 명령문에도 넣지 않는다(명령 이력에 남는다).
# 비공개 S3 버킷을 잠깐 거쳐 서버로 보내고 바로 지운다.
set -euo pipefail
REGION=ap-northeast-2
INSTANCE=i-0bd8ffad33886895e
BUCKET=cie-deploy-929862311742
KEY="tmp/dbpw-$(date +%s)"

command -v mysql >/dev/null || { echo "mysql 클라이언트가 필요합니다"; exit 1; }
nc -z -G 3 127.0.0.1 3308 >/dev/null 2>&1 || {
  echo "🔴 SSM 터널(3308)이 열려 있지 않습니다. 다른 탭에서 먼저 실행하세요:"
  echo "   aws ssm start-session --region $REGION --target $INSTANCE \\"
  echo "     --document-name AWS-StartPortForwardingSessionToRemoteHost \\"
  echo "     --parameters '{\"host\":[\"127.0.0.1\"],\"portNumber\":[\"3307\"],\"localPortNumber\":[\"3308\"]}'"
  exit 1; }

ssm_run() {  # $1 = commands JSON array
  local cid
  cid=$(aws ssm send-command --region "$REGION" --instance-ids "$INSTANCE" \
        --document-name AWS-RunShellScript --timeout-seconds 300 \
        --parameters "commands=$1" --query 'Command.CommandId' --output text)
  local s
  for _ in $(seq 1 60); do
    s=$(aws ssm get-command-invocation --region "$REGION" --command-id "$cid" \
        --instance-id "$INSTANCE" --query 'Status' --output text 2>/dev/null || echo Pending)
    [[ "$s" == "Success" || "$s" == "Failed" ]] && break
    sleep 3
  done
  [[ "$s" == "Success" ]] || { echo "🔴 서버 명령 실패"; aws ssm get-command-invocation --region "$REGION" \
      --command-id "$cid" --instance-id "$INSTANCE" --query 'StandardErrorContent' --output text; exit 1; }
  aws ssm get-command-invocation --region "$REGION" --command-id "$cid" \
      --instance-id "$INSTANCE" --query 'StandardOutputContent' --output text
}

echo "현재 비밀번호가 필요합니다. 서버 .env 에서 읽어옵니다."
OLD=$(ssm_run '["grep ^DB_PASSWORD= /home/ubuntu/CIE/.env | cut -d= -f2-"]' | tr -d '\r\n')
[ -n "$OLD" ] || { echo "🔴 현재 비밀번호를 읽지 못했습니다"; exit 1; }

read -rs -p "새 DB 비밀번호: " NEW; echo
read -rs -p "한 번 더: " NEW2; echo
[ "$NEW" = "$NEW2" ] || { echo "🔴 입력이 다릅니다"; exit 1; }
[ ${#NEW} -ge 12 ] || { echo "🔴 12자 이상으로 하세요"; exit 1; }
case "$NEW" in *\'*|*\\*|*\"*) echo "🔴 따옴표와 역슬래시는 쓰지 마세요"; exit 1;; esac

echo "① MySQL 계정 비밀번호 변경"
mysql -h 127.0.0.1 -P 3308 -umeogeodo -p"$OLD" \
  -e "ALTER USER USER() IDENTIFIED BY '$NEW';" 2>/dev/null
echo "   완료"

echo "② 서버 .env 갱신"
TMP=$(mktemp); printf '%s' "$NEW" > "$TMP"
aws s3 cp "$TMP" "s3://$BUCKET/$KEY" --region "$REGION" --only-show-errors
rm -f "$TMP"
ssm_run "[\"set -e\",\"runuser -u ubuntu -- aws s3 cp s3://$BUCKET/$KEY /tmp/np --region $REGION\",\"python3 -c \\\"import re,pathlib; p=pathlib.Path('/home/ubuntu/CIE/.env'); v=open('/tmp/np').read().strip(); s=p.read_text(); p.write_text(re.sub(r'^DB_PASSWORD=.*$','DB_PASSWORD='+v,s,flags=re.M))\\\"\",\"rm -f /tmp/np\",\"chown ubuntu:ubuntu /home/ubuntu/CIE/.env; chmod 600 /home/ubuntu/CIE/.env\",\"echo ok\"]" >/dev/null
aws s3 rm "s3://$BUCKET/$KEY" --region "$REGION" --only-show-errors
echo "   완료 (S3 임시 파일 삭제됨)"

echo "③ 백엔드 재시작"
ssm_run '["cd /home/ubuntu/CIE && docker compose -f docker-compose.prod.yml up -d --force-recreate backend","sleep 25","docker logs meogeodo-backend 2>&1 | grep -c \"Started Meogeodo\" || true"]' | sed 's/^/   /'

echo "④ 확인"
for _ in $(seq 1 20); do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 https://api.mukeodo.site/api/reports/options || true)
  [[ "$code" == "200" ]] && { echo "   ✅ API 정상 (DB 연결 성공)"; exit 0; }
  sleep 5
done
echo "   🔴 API 가 응답하지 않습니다. 로그를 확인하세요:"
echo "      aws ssm start-session --region $REGION --target $INSTANCE"
exit 1
