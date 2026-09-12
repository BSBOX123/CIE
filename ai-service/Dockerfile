FROM python:3.12-slim
WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

# 의존성 먼저 설치해 소스 변경 시 재설치를 피한다.
COPY pyproject.toml ./
RUN pip install --no-cache-dir --upgrade pip && pip install --no-cache-dir .

COPY app app

RUN useradd --create-home --shell /bin/false app && chown -R app:app /app
USER app

EXPOSE 8000
# 태깅은 요청당 수 분이 걸릴 수 있어(무료 티어 RPM 제한) 워커를 여럿 두지 않는다.
# 백엔드 배치만 호출하는 내부 서비스라 동시성이 필요 없다.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
