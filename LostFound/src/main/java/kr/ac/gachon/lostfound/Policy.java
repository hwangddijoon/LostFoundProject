package kr.ac.gachon.lostfound;

import kr.ac.gachon.lostfound.Models.FoundItem;
import kr.ac.gachon.lostfound.Models.OwnerView;
import kr.ac.gachon.lostfound.Models.PublicView;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** DB와 외부 API 없이 판단할 수 있는 규칙들 (단위 테스트 대상: PolicyTest). */
public final class Policy {
    private Policy() {
    }

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 보관소는 한 곳 (개발 문서 1.2, 6.2). 이름과 위치는 설정값으로 둔다.
    public static final String OFFICE_NAME = env("OFFICE_NAME", "학생지원팀");
    public static final String OFFICE_LOCATION = env("OFFICE_LOCATION", "학생회관 302호");

    /** R9: 보관 7일이 지나면 담당자가 다시 보고 공개 설명을 추가한다. */
    public static final int REVIEW_AFTER_DAYS = 7;
    /** R10: 보관 한 달(30일)이 지나면 관할 파출소로 인계한다. */
    public static final int TRANSFER_AFTER_DAYS = 30;
    public static final String POLICE_NAME = env("POLICE_NAME", "복정파출소");

    // R5 매칭 결과 표시 기준
    public static final int SHOW_MIN_SCORE = 70;
    public static final int HIGH_SCORE = 85;
    public static final int MAX_SHOWN = 3;
    public static final int MAX_CANDIDATES = 20;

    /** R8 계정당 진행 중 신고 수. */
    public static final int MAX_ACTIVE_REPORTS = 3;

    // R11 로그인
    public static final String SCHOOL_DOMAIN = "@gachon.ac.kr";
    public static final String NOT_SCHOOL_EMAIL = "가천대학교 이메일로 인증해주세요";
    public static final int CODE_VALID_MINUTES = 10;
    public static final int CODE_RESEND_SECONDS = 60;
    public static final int CODE_MAX_ATTEMPTS = 5;
    public static final int PASSWORD_MIN = 8;
    public static final int SESSION_DAYS = 7;
    private static final Pattern SCHOOL_EMAIL = Pattern.compile("^[a-z0-9][a-z0-9._%+-]{0,63}@gachon\\.ac\\.kr$");

    /**
     * R11: 학교 이메일이면 소문자로 바꾼 주소, 아니면 null.
     * 정확히 '@gachon.ac.kr'로 끝나야 한다 (x@gachon.ac.kr.example.com, x@mail.gachon.ac.kr 거절).
     */
    public static String schoolEmail(String text) {
        String e = normalEmail(text);
        return e != null && SCHOOL_EMAIL.matcher(e).matches() ? e : null;
    }

    private static final Pattern ANY_EMAIL = Pattern.compile("^[a-z0-9][a-z0-9._%+-]{0,63}@[a-z0-9-]+(\\.[a-z0-9-]+)+$");

    /** 이메일 형식이면 소문자로 바꾼 주소, 아니면 null (도메인은 보지 않음). */
    public static String normalEmail(String text) {
        String e = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        return e.length() <= 254 && ANY_EMAIL.matcher(e).matches() ? e : null;
    }

    /**
     * R11 로그인할 수 있는 이메일: 학교 이메일, 또는 STAFF_EMAILS에 등록된 담당자 이메일(도메인 무관).
     * 그 밖의 주소는 null → '가천대학교 이메일로 인증해주세요'로 거절한다.
     */
    public static String allowedEmail(String text, Set<String> staffEmails) {
        String e = normalEmail(text);
        if (e == null) return null;
        return SCHOOL_EMAIL.matcher(e).matches() || staffEmails.contains(e) ? e : null;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    public static OffsetDateTime now() {
        return OffsetDateTime.now(KST);
    }

    public static String receiptNo(int itemId) {
        return String.format("F%04d", itemId);
    }

    public static String reportNo(int reportId) {
        return String.format("L%04d", reportId);
    }

    /** "F0012", "f12", "12" 모두 12로 읽는다. 형식이 틀리면 null. */
    public static Integer parseNo(String text, char prefix) {
        String t = text == null ? "" : text.strip().toUpperCase(Locale.ROOT);
        if (!t.isEmpty() && t.charAt(0) == prefix) {
            t = t.substring(1);
        }
        if (t.isEmpty() || t.length() > 9 || !t.chars().allMatch(Character::isDigit)) {
            return null;
        }
        int n = Integer.parseInt(t);
        return n > 0 ? n : null;
    }

    /** 보관 경과일: 습득 시각 기준, 모르면 등록 시각 기준. */
    public static long daysKept(FoundItem item, OffsetDateTime at) {
        OffsetDateTime base = item.foundAt() != null ? item.foundAt() : item.createdAt();
        return Duration.between(base, at).toDays();
    }

    /** R9 '보완 필요': 보관 중이고, 7일 이상 지났고, 아직 보완하지 않은 물건. */
    public static boolean needsReview(FoundItem item, OffsetDateTime at) {
        return item.isStored() && item.reviewedAt() == null && daysKept(item, at) >= REVIEW_AFTER_DAYS;
    }

    /** R10 '인계 대상': 보관 중이고 30일 이상 지난 물건. */
    public static boolean needsTransfer(FoundItem item, OffsetDateTime at) {
        return item.isStored() && daysKept(item, at) >= TRANSFER_AFTER_DAYS;
    }

    /** 물건이 지금 있는 곳 [이름, 위치]. 경찰에 인계한 물건은 파출소로 안내한다 (R10). */
    public static String[] whereNow(FoundItem item) {
        if (FoundItem.TRANSFERRED.equals(item.status())) {
            String day = item.transferredAt().atZoneSameInstant(KST).toLocalDate().toString();
            return new String[]{POLICE_NAME, POLICE_NAME + "에 인계됨 (" + day + ")"};
        }
        return new String[]{OFFICE_NAME, OFFICE_LOCATION};
    }

    /** R3: 담당자 보완(R9)을 마친 물건만 2단계, 나머지는 1단계. */
    public static int disclosureStage(FoundItem item) {
        return item.reviewedAt() != null ? 2 : 1;
    }

    /** 공개 목록용으로 줄인 습득물. 1단계는 특징을 넣지 않고, 2단계는 특징 네 칸까지. 확인용 특징은 어느 단계에도 넣지 않는다. */
    public static PublicView publicView(FoundItem item) {
        int stage = disclosureStage(item);
        String[] where = whereNow(item);
        String foundDate = item.foundAt() == null ? null
                : item.foundAt().atZoneSameInstant(KST).toLocalDate().toString();
        String building = item.building() == null ? "모름" : item.building();
        if (stage == 1) {
            return new PublicView(receiptNo(item.id()), 1, item.category(), building, foundDate,
                    where[0], where[1], null, null, null, null, null, null);
        }
        return new PublicView(receiptNo(item.id()), 2, item.category(), building, foundDate,
                where[0], where[1], item.color(), blankToNull(item.placeDetail()), blankToNull(item.featBrand()),
                blankToNull(item.featAttached()), blankToNull(item.featDamage()), blankToNull(item.featContents()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern INITIAL_SEP = Pattern.compile("(?<=[A-Za-z])[.\\-·](?=[A-Za-z])");
    private static final Pattern ID_MARK = Pattern.compile("\\d{2,}|[A-Za-z]{2,}");

    static String normalize(String text) {
        return NOT_WORD.matcher(text == null ? "" : text.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    /** 숫자 2자리 이상, 영문 2자 이상(J.H처럼 기호가 섞인 이니셜 포함) 같은 식별 표시. */
    static Set<String> idMarks(String text) {
        String compact = INITIAL_SEP.matcher(text == null ? "" : text).replaceAll("");
        Set<String> marks = new HashSet<>();
        Matcher m = ID_MARK.matcher(compact);
        while (m.find()) {
            marks.add(m.group().toLowerCase(Locale.ROOT));
        }
        return marks;
    }

    /** 특징 칸 이름 (화면·오류 문장용). */
    public static final Map<String, String> FEATURE_LABELS = Map.of(
            "brand", "브랜드·모델", "attached", "부착물·장식", "damage", "손상·흠집", "contents", "내용물·기타");

    /** R9: 2단계에 공개할 특징 칸 중 확인용 특징을 드러내는 칸의 이름들 (없으면 빈 목록). */
    public static List<String> revealingFeatures(Map<String, String> features, String secret) {
        List<String> bad = new ArrayList<>();
        for (String key : List.of("brand", "attached", "damage", "contents")) {
            String v = features.getOrDefault(key, "");
            if (v != null && !v.isBlank() && noteRevealsSecret(v, secret)) bad.add(FEATURE_LABELS.get(key));
        }
        return bad;
    }

    /**
     * R9·R8: 공개할 글이 확인용 특징을 드러내는지.
     * 1) 확인용 특징 문구가 그대로 들어 있음 (공백·기호·대소문자 무시)
     * 2) 확인용 특징에 있는 숫자·영문 이니셜 같은 식별 표시가 공개 설명에 들어 있음
     * 한국어로 바꿔 쓴 표현까지는 잡지 못하므로, 보완 화면에 확인용 특징을 '공개 금지'로 함께 보여준다.
     */
    public static boolean noteRevealsSecret(String note, String secret) {
        String n = normalize(note);
        String s = normalize(secret);
        if (!s.isEmpty() && n.contains(s)) {
            return true;
        }
        Set<String> common = idMarks(secret);
        common.retainAll(idMarks(note));
        return !common.isEmpty();
    }

    /**
     * 규칙 기반 구체성 검사 (Gemini를 쓸 수 없을 때도 적용).
     * 색상, 브랜드·모델, 부착물·장식, 손상·흠집, 내용물·기타 중 2가지 이상이 있어야 하고,
     * 특징 칸 글자 수의 합이 4자 이상이어야 한다. 부족하면 보완 질문 목록(최대 2개)을 돌려준다.
     *
     * @param features 키: brand, attached, damage, contents
     */
    public static List<String> specificityProblems(String color, Map<String, String> features) {
        List<String> filled = new ArrayList<>();
        int totalChars = 0;
        for (String key : List.of("brand", "attached", "damage", "contents")) {
            String v = features.getOrDefault(key, "");
            if (v != null && !v.strip().isEmpty()) {
                filled.add(key);
                totalChars += v.strip().length();
            }
        }
        int signals = filled.size() + (color != null && !color.equals("모름") ? 1 : 0);
        if (signals >= 2 && totalChars >= 4) {
            return List.of();
        }
        List<String> questions = new ArrayList<>();
        if (!filled.contains("brand")) questions.add("브랜드나 모델, 형태를 아시나요? (예: 에어팟 프로, 반지갑)");
        if (!filled.contains("attached")) questions.add("붙어 있거나 달려 있던 것이 있나요? (스티커, 키링, 케이스)");
        if (!filled.contains("damage")) questions.add("흠집이나 깨진 곳, 각인이 있었나요?");
        if (!filled.contains("contents")) questions.add("안에 들어 있던 것이 있나요?");
        return questions.subList(0, Math.min(2, questions.size()));
    }

    public static String grade(int score) {
        if (score >= HIGH_SCORE) return "높음";
        if (score >= SHOW_MIN_SCORE) return "보통";
        return null;
    }

    public record Scored(FoundItem item, int score) {
    }

    /** 70점 이상을 점수 순으로 최대 3건. */
    public static List<Scored> shownMatches(List<Scored> scored) {
        return scored.stream()
                .filter(s -> s.score() >= SHOW_MIN_SCORE)
                .sorted(Comparator.comparingInt(Scored::score).reversed())
                .limit(MAX_SHOWN)
                .toList();
    }

    /** 분실자 화면: 등급, 보관소, 접수번호만. 점수 근거와 세부 특징은 넣지 않는다 (R8). */
    public static OwnerView ownerView(FoundItem item, int score) {
        String[] where = whereNow(item);
        return new OwnerView(receiptNo(item.id()), grade(score), item.category(), where[0], where[1]);
    }
}
