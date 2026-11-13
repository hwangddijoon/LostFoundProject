package kr.ac.gachon.lostfound;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 요청 입력 형식. 각 record의 생성자에서 공백을 정리하고 검사한다.
 * 검사에 걸리면 ApiException(422)이 나고, 잘못된 값으로는 객체가 만들어지지 않는다.
 */
public final class Requests {
    private Requests() {
    }

    static final int SHORT = 100;
    private static final Pattern PHONE = Pattern.compile("^[0-9\\-]{9,15}$");

    static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    static String optional(String value, int max, String label) {
        String v = clean(value);
        if (v.length() > max) throw ApiException.invalid(label + "은(는) " + max + "자 이하로 입력해 주세요.");
        return v;
    }

    static String required(String value, int min, int max, String label) {
        String v = clean(value);
        if (v.length() < min) throw ApiException.invalid(label + "을(를) 입력해 주세요.");
        if (v.length() > max) throw ApiException.invalid(label + "은(는) " + max + "자 이하로 입력해 주세요.");
        return v;
    }

    static void notFuture(OffsetDateTime at, String label) {
        if (at != null && at.isAfter(Policy.now())) throw ApiException.invalid(label + "가 현재보다 나중입니다.");
    }

    /** 특징 네 칸. 분실 신고, 담당자 입력, 보완에서 같이 쓴다. */
    public record Features(String brand, String attached, String damage, String contents) {
        public Features {
            brand = optional(brand, SHORT, "브랜드·모델");
            attached = optional(attached, SHORT, "부착물·장식");
            damage = optional(damage, SHORT, "손상·흠집");
            contents = optional(contents, SHORT, "내용물·기타");
        }

        public Map<String, String> asMap() {
            return Map.of("brand", brand, "attached", attached, "damage", damage, "contents", contents);
        }
    }

    /** R1 습득자 등록: 장소·시간만. 보관소는 한 곳이라 고르지 않는다. */
    public record FinderItem(Integer buildingId, String placeDetail,
                             @JsonDeserialize(using = KstDateTime.class) OffsetDateTime foundAt) {
        public FinderItem {
            if (buildingId == null) throw ApiException.invalid("습득 건물을 골라 주세요.");
            placeDetail = optional(placeDetail, SHORT, "세부 장소");
            if (foundAt == null) throw ApiException.invalid("습득 일시를 입력해 주세요.");
            notFuture(foundAt, "습득 일시");
        }
    }

    /**
     * R2 담당자가 실물을 보며 입력하는 세부 사항. 입고 대기 물건에 입력할 때(store)와
     * 습득자 등록 없이 맡겨진 물건을 직접 등록할 때 같이 쓴다. 직접 등록에서 장소·시간은 모르면 비워 둔다.
     */
    public record ItemDetails(String category, String color, String featBrand, String featAttached,
                              String featDamage, String featContents, String secretFeature,
                              Integer buildingId, String placeDetail,
                              @JsonDeserialize(using = KstDateTime.class) OffsetDateTime foundAt) {
        public ItemDetails {
            category = required(category, 1, 30, "종류");
            color = required(color, 1, 30, "색상");
            Features f = new Features(featBrand, featAttached, featDamage, featContents);
            featBrand = f.brand();
            featAttached = f.attached();
            featDamage = f.damage();
            featContents = f.contents();
            secretFeature = required(secretFeature, 1, SHORT, "확인용 특징");
            placeDetail = optional(placeDetail, SHORT, "세부 장소");
            notFuture(foundAt, "습득 일시");
        }
    }

    /**
     * R9 특징 보완: 담당자가 물건을 다시 보고 특징 네 칸을 고쳐 쓴다. 저장하면 이 네 칸이 2단계에서 공개된다.
     * 비운 칸은 비운 채로 저장된다. 네 칸이 모두 비면 공개할 것이 없으므로 거절.
     */
    public record Review(String featBrand, String featAttached, String featDamage, String featContents) {
        public Review {
            Features f = new Features(featBrand, featAttached, featDamage, featContents);
            featBrand = f.brand();
            featAttached = f.attached();
            featDamage = f.damage();
            featContents = f.contents();
            if ((featBrand + featAttached + featDamage + featContents).isEmpty()) {
                throw ApiException.invalid("공개할 특징을 한 칸 이상 입력해 주세요.");
            }
        }

        public Features features() {
            return new Features(featBrand, featAttached, featDamage, featContents);
        }
    }

    /** R10 경찰 인계: 물건을 받은 경찰관 이름은 필수. */
    public record Transfer(String officerName, String note) {
        public Transfer {
            officerName = required(officerName, 1, 30, "받은 경찰관 이름");
            note = optional(note, 200, "메모");
        }
    }

    /** R4 분실 신고. */
    public record LostReportForm(String category, String color,
                                 String featBrand, String featAttached, String featDamage, String featContents,
                                 List<Integer> buildingIds,
                                 @JsonDeserialize(using = KstDateTime.class) OffsetDateTime lostFrom,
                                 @JsonDeserialize(using = KstDateTime.class) OffsetDateTime lostTo) {
        public LostReportForm {
            category = required(category, 1, 30, "종류");
            color = required(color, 1, 30, "색상");
            Features f = new Features(featBrand, featAttached, featDamage, featContents);
            featBrand = f.brand();
            featAttached = f.attached();
            featDamage = f.damage();
            featContents = f.contents();
            buildingIds = buildingIds == null ? List.of() : List.copyOf(buildingIds);
            if (buildingIds.size() > 10) throw ApiException.invalid("건물은 10곳까지 고를 수 있습니다.");
            if (lostFrom == null || lostTo == null) throw ApiException.invalid("분실 시간 범위를 입력해 주세요.");
            if (lostFrom.isAfter(lostTo)) throw ApiException.invalid("분실 시간 범위의 시작이 끝보다 나중입니다.");
            if (lostTo.isAfter(Policy.now())) throw ApiException.invalid("분실 시간 범위의 끝이 현재보다 나중입니다.");
        }

        public Features features() {
            return new Features(featBrand, featAttached, featDamage, featContents);
        }
    }

    /** R6 수령 처리: 인적사항 모두 필수. 신고번호는 있으면 해결 처리. */
    public record Handover(String reportNo, String receiverName, String receiverNo, String receiverPhone,
                           String idType) {
        public Handover {
            reportNo = optional(reportNo, 10, "신고번호");
            receiverName = required(receiverName, 1, 30, "수령자 이름");
            receiverNo = required(receiverNo, 4, 20, "수령자 학번·번호");
            receiverPhone = clean(receiverPhone);
            if (!PHONE.matcher(receiverPhone).matches()) throw ApiException.invalid("연락처 형식이 맞지 않습니다.");
            idType = required(idType, 1, 20, "신분증 종류");
        }
    }

    // ---------- R11 로그인 ----------

    /** 형식만 검사하고 소문자로 바꾼다. 학교 이메일·담당자 이메일인지는 AuthService가 본다. */
    static String checkEmail(String value) {
        String e = Policy.normalEmail(value);
        if (e == null) throw ApiException.invalid(Policy.NOT_SCHOOL_EMAIL);
        return e;
    }

    /** 인증번호 받기. */
    public record CodeRequest(String email) {
        public CodeRequest {
            email = checkEmail(email);
        }
    }

    /** 인증번호 확인 + 비밀번호 정하기 (처음 가입과 비밀번호 재설정이 같은 흐름). */
    public record VerifyRequest(String email, String code, String password) {
        public VerifyRequest {
            email = checkEmail(email);
            code = clean(code);
            if (!code.matches("\\d{6}")) throw ApiException.invalid("인증번호 6자리를 입력해 주세요.");
            if (password == null || password.length() < Policy.PASSWORD_MIN || password.length() > 100) {
                throw ApiException.invalid("비밀번호는 " + Policy.PASSWORD_MIN + "자 이상으로 정해 주세요.");
            }
        }
    }

    public record LoginRequest(String email, String password) {
        public LoginRequest {
            email = checkEmail(email);
            if (password == null || password.isEmpty()) throw ApiException.invalid("비밀번호를 입력해 주세요.");
        }
    }

    /**
     * 브라우저 datetime-local 값("2026-11-11T09:00")처럼 시간대가 없으면 한국 시간으로 읽는다.
     * "2026-11-11T09:00:00+09:00"처럼 시간대가 있으면 그대로 쓴다.
     */
    public static class KstDateTime extends JsonDeserializer<OffsetDateTime> {
        public static OffsetDateTime parse(String text) {
            String t = text == null ? "" : text.strip();
            if (t.isEmpty()) return null;
            try {
                return OffsetDateTime.parse(t);
            } catch (DateTimeParseException withoutZone) {
                try {
                    return LocalDateTime.parse(t).atZone(Policy.KST).toOffsetDateTime();
                } catch (DateTimeParseException e) {
                    throw ApiException.invalid("날짜·시간 형식이 맞지 않습니다: " + t);
                }
            }
        }

        @Override
        public OffsetDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return parse(parser.getValueAsString());
        }
    }
}
