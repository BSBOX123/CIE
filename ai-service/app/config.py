"""환경 설정. 키는 절대 코드에 두지 않는다 (SPEC 11.4)."""

from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    public_data_api_key: str = ""

    # Google AI Studio (Gemini). SDK가 GEMINI_API_KEY 를 자동으로 읽지만,
    # 설정 유무를 /health 에서 알리기 위해 여기서도 받는다.
    gemini_api_key: str = ""
    llm_model_id: str = "gemini-3.8-flash"

    # 무료 티어는 분당 요청 수(RPM)와 일일 요청 수(RPD)가 모두 제한된다.
    # 정확한 값은 계정마다 다르므로 AI Studio 의 Rate limit 페이지에서 확인해
    # 여기에 맞춰야 한다. 기본값은 보수적으로 잡았다.
    gemini_rpm: int = 10
    #: 한 번의 태깅 요청에서 처리할 최대 음식 수. 일일 한도를 넘지 않게 나눠 돈다.
    tagging_chunk_size: int = 50

    nutrition_base_url: str = (
        "https://apis.data.go.kr/1471000/FoodNtrCpntDbInfo02/getFoodNtrCpntDbInq02"
    )
    request_timeout_seconds: float = 30.0


settings = Settings()
