-- 알레르겐 함유 정도 (SPEC 3.1 개정)
--
-- 간장·된장이 들어간다는 이유로 거의 모든 한국 음식에 '대두'·'밀'이 붙으면
-- 밀 알레르기 사용자에게 대부분의 메뉴가 ✕가 되어 서비스가 쓸모없어진다.
-- 강릉 메뉴 29건 실측에서 19건이 그렇게 나왔다.
--
-- 그렇다고 양념 수준을 빼면 실제로 위험하다. 없애는 대신 두 단계로 나눠
-- 사용자가 판단하게 한다.
--   MAIN  = 주재료. 빼면 그 음식이 아니다.        -> ✕ 알레르기 주의
--   TRACE = 양념·부재료에 미량. 요청할 여지가 있다. -> △ 조절하면 가능
ALTER TABLE dish_tag
    ADD COLUMN amount VARCHAR(10)
        COMMENT '알레르겐 태그에만 쓴다. 주의성분(CARE) 태그에서는 NULL.',
    ADD CONSTRAINT chk_dish_tag_amount
        CHECK (amount IS NULL OR amount IN ('MAIN','TRACE'));
