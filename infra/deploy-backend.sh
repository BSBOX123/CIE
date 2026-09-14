#!/bin/bash
# 백엔드 배포: 로컬에서 jar 빌드 → S3 → 서버가 내려받아 재기동.
#
# 서버(1GB)에서 Gradle 을 돌리면 메모리를 다 먹어 sshd 까지 죽는 일이 있었다.
# 빌드는 개발자 PC 에서 하고 서버는 결과물만 받는다.
#
# 서버 접속은 SSM 을 쓴다. 학교·회사처럼 22번이 막힌 곳에서도 된다.
set -euo pipefail

REGION=ap-northeast-2
INSTANCE=i-0bd8ffad33886895e
BUCKET=cie-deploy-929862311742
KEY="backend/app-$(date +%Y%m%d-%H%M%S).jar"

cd "$(dirname "$0")/.."

echo "① 로컬 빌드"
(cd backend && ./gradlew clean bootJar -x test -q)
JAR=$(ls -t backend/build/libs/*.jar | head -1)
echo "   $JAR ($(du -h "$JAR" | cut -f1))"

echo "② S3 업로드"
aws s3 cp "$JAR" "s3://$BUCKET/$KEY" --region "$REGION" --only-show-errors
echo "   s3://$BUCKET/$KEY"

echo "③ 서버에서 내려받아 재기동"
CID=$(aws ssm send-command --region "$REGION" --instance-ids "$INSTANCE" \
  --document-name AWS-RunShellScript --timeout-seconds 900 \
  --parameters "commands=[\"set -e\",\"runuser -u ubuntu -- git -C /home/ubuntu/CIE pull -q\",\"runuser -u ubuntu -- aws s3 cp s3://$BUCKET/$KEY /home/ubuntu/CIE/backend/app.jar --region $REGION\",\"cd /home/ubuntu/CIE && docker compose -f docker-compose.prod.yml up -d --build backend\",\"sleep 25\",\"cd /home/ubuntu/CIE && docker compose -f docker-compose.prod.yml ps --format '{{.Service}}: {{.State}}'\"]" \
  --query 'Command.CommandId' --output text)

for _ in $(seq 1 90); do
  S=$(aws ssm get-command-invocation --region "$REGION" --command-id "$CID" --instance-id "$INSTANCE" --query 'Status' --output text 2>/dev/null || echo Pending)
  [[ "$S" == "Success" || "$S" == "Failed" ]] && break
  sleep 5
done
echo "   상태: $S"
aws ssm get-command-invocation --region "$REGION" --command-id "$CID" --instance-id "$INSTANCE" --query 'StandardOutputContent' --output text | sed 's/^/   /'
[[ "$S" == "Failed" ]] && aws ssm get-command-invocation --region "$REGION" --command-id "$CID" --instance-id "$INSTANCE" --query 'StandardErrorContent' --output text | sed 's/^/   ERR /'

echo "④ 확인"
for _ in $(seq 1 20); do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 https://api.mukeodo.site/api/reports/options || true)
  [[ "$code" == "200" ]] && { echo "   ✅ https://api.mukeodo.site 200"; exit 0; }
  sleep 5
done
echo "   🔴 서비스가 응답하지 않습니다"; exit 1
