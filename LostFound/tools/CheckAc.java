import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 실행 중인 서비스에 HTTP 요청을 보내 완료 기준(AC, 개발 문서 2.2)을 점검한다. 라이브러리 없이 JDK만 사용.
 *
 * 사용법 (docker compose up 이후, JDK 21이 있는 PC에서):
 *   java tools/CheckAc.java --staff 담당자@gachon.ac.kr --student 학생@gachon.ac.kr
 *   java tools/CheckAc.java --base http://EC2퍼블릭IP:8080 --staff ... --student ...
 *
 * - --staff 이메일은 .env의 STAFF_EMAILS에 들어 있어야 한다 (Gmail 같은 외부 주소도 됨).
 *   --student 는 @gachon.ac.kr 이메일.
 * - 처음 실행하면 두 계정에 인증번호를 보내고, 입력을 기다린다.
 *   MAIL_HOST를 비워 두었다면 인증번호는 'docker compose logs app'에 찍힌다.
 *   두 번째 실행부터는 --password 비밀번호로 바로 로그인한다.
 * - 여러 번 실행해도 되도록 시작할 때 두 계정의 진행 중 신고를 취소하고, 특징에 실행마다 다른 값을 붙인다.
 * - AI 판단(AC5)은 실행마다 조금씩 다를 수 있어, 점수가 아니라 순위와 표시 여부만 본다.
 */
public class CheckAc {
    static final List<Boolean> RESULTS = new ArrayList<>();
    static final BufferedReader IN = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    static String base = "http://127.0.0.1:8080";

    /** 계정 하나 = 쿠키를 따로 가진 HTTP 클라이언트 하나. */
    static final class Client {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager())
                .connectTimeout(Duration.ofSeconds(5)).build();

        Res call(String method, String path, Object body) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(120)); // 분실 신고는 Gemini를 최대 100초 가까이 기다릴 수 있다 (문제 9, 10)
            if (body != null) {
                b.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Res(r.statusCode(), r.body().isEmpty() ? null : Json.parse(r.body()), r.body());
        }

        Res get(String path) throws Exception {
            return call("GET", path, null);
        }

        Res post(String path, Object body) throws Exception {
            return call("POST", path, body == null ? Map.of() : body);
        }
    }

    record Res(int status, Object json, String raw) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map() {
            return json instanceof Map ? (Map<String, Object>) json : Map.of();
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list() {
            return json instanceof List ? (List<Map<String, Object>>) json : List.of();
        }

        String message() {
            Object d = map().get("detail");
            return d instanceof Map<?, ?> m ? String.valueOf(m.get("message")) : "";
        }
    }

    static void check(String name, boolean ok, Object detail) {
        RESULTS.add(ok);
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  -> " + detail));
    }

    static String ask(String prompt) throws Exception {
        System.out.print(prompt);
        System.out.flush();
        String line = IN.readLine();
        return line == null ? "" : line.strip();
    }

    /** 로그인. 안 되면 인증번호를 받아 가입(또는 비밀번호 재설정)한다. 처음 가입할 때만 AC26(틀린 인증번호)을 본다. */
    static Client login(String email, String password) throws Exception {
        Client c = new Client();
        if (c.post("/api/auth/login", Map.of("email", email, "password", password)).status() == 200) return c;
        Res sent = c.post("/api/auth/code", Map.of("email", email));
        if (sent.status() != 200 && sent.status() != 429) throw new IllegalStateException(email + " 인증번호 요청 실패: " + sent.raw());
        String code = ask(email + " 인증번호 6자리 (메일함 또는 docker compose logs app): ");
        String wrong = String.format("%06d", (Integer.parseInt(code) + 1) % 1_000_000);
        Res bad = c.post("/api/auth/verify", Map.of("email", email, "code", wrong, "password", password));
        check("AC25 틀린 인증번호 거절", bad.status() == 400, bad.raw());
        Res ok = c.post("/api/auth/verify", Map.of("email", email, "code", code, "password", password));
        if (ok.status() != 200) throw new IllegalStateException(email + " 인증 실패: " + ok.raw());
        return c;
    }

    static void cancelActive(Client c) throws Exception {
        for (Map<String, Object> r : c.get("/api/reports/mine").list()) {
            if ("active".equals(r.get("status"))) c.post("/api/reports/" + r.get("report_no") + "/cancel", null);
        }
    }

    public static void main(String[] args) throws Exception {
        String staffEmail = null, studentEmail = null, password = "check-pass-2026";
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--base" -> base = args[i + 1].replaceAll("/+$", "");
                case "--staff" -> staffEmail = args[i + 1];
                case "--student" -> studentEmail = args[i + 1];
                case "--password" -> password = args[i + 1];
                default -> throw new IllegalArgumentException("모르는 옵션: " + args[i]);
            }
        }
        if (staffEmail == null || studentEmail == null) {
            System.out.println("사용법: java tools/CheckAc.java --staff 담당자@gachon.ac.kr --student 학생@gachon.ac.kr [--base URL] [--password 비밀번호]");
            return;
        }
        String tag = String.valueOf(1000 + new Random().nextInt(9000));
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

        Client guest = new Client();
        Res health = guest.get("/health");
        check("서비스 응답", health.status() == 200, health.raw());
        boolean aiOn = Boolean.TRUE.equals(health.map().get("ai"));
        System.out.println("AI 사용: " + aiOn + ", 메일: " + health.map().get("mail"));
        Map<String, Integer> bid = new LinkedHashMap<>();
        for (Object b : (List<?>) guest.get("/api/options").map().get("buildings")) {
            Map<?, ?> m = (Map<?, ?>) b;
            bid.put((String) m.get("name"), ((Number) m.get("id")).intValue());
        }

        // R11 로그인 (AC23~AC26)
        Res r23 = guest.post("/api/auth/code", Map.of("email", "someone@gmail.com"));
        check("AC23 학교 이메일이 아니면 거절", r23.status() == 422 && r23.message().equals("가천대학교 이메일로 인증해주세요"), r23.raw());
        Res r24 = guest.get("/api/items");
        check("AC24 로그인하지 않으면 목록 거절", r24.status() == 401, r24.status());
        Client staff = login(staffEmail, password);
        Client student = login(studentEmail, password);
        boolean isStaff = Boolean.TRUE.equals(staff.get("/api/auth/me").map().get("staff"));
        check("담당자 계정 확인 (STAFF_EMAILS)", isStaff, staffEmail + " 이(가) .env의 STAFF_EMAILS에 있는지 확인하세요");
        if (!staffEmail.toLowerCase().endsWith("@gachon.ac.kr")) {
            check("AC26 STAFF_EMAILS에 등록된 외부 이메일 로그인", isStaff, staffEmail);
        }
        Res r25 = student.get("/api/office/items?status=waiting");
        check("AC13 학생 계정은 보관소 거절", r25.status() == 403, r25.status());
        cancelActive(staff);
        cancelActive(student);

        // AC1 습득자 등록 → 입고 대기, 목록 변화 없음
        int before = student.get("/api/items").list().size();
        Res r1 = student.post("/api/items", Map.of("building_id", bid.get("공과대학1"), "place_detail", "1층 현관",
                "found_at", now.minusMinutes(20).format(f)));
        check("AC1 습득자 등록", r1.status() == 201 && "waiting".equals(r1.map().get("status"))
                && student.get("/api/items").list().size() == before, r1.raw());
        String receipt = (String) r1.map().get("receipt_no");

        // AC2 미래 시각 거절
        Res r2 = student.post("/api/items", Map.of("building_id", bid.get("공과대학1"), "found_at", now.plusDays(1).format(f)));
        check("AC2 미래 습득 시각 거절", r2.status() == 422, r2.status());

        // AC3 담당자 세부 사항 입력 → 보관 중
        String attached = "토끼 키링 " + tag;
        Res r3 = staff.post("/api/office/items/" + receipt + "/store", Map.of("category", "우산", "color", "검정",
                "feat_brand", "장우산", "feat_attached", attached, "secret_feature", "손잡이 테이프 " + tag));
        check("AC3 세부 사항 입력 후 보관 중", r3.status() == 200 && "stored".equals(r3.map().get("status"))
                && student.get("/api/items").list().size() == before + 1, r3.raw());

        // AC4 공개 단계와 비공개 특징
        Res items = student.get("/api/items");
        boolean stage1 = items.list().stream().anyMatch(i -> ((Number) i.get("stage")).intValue() == 1);
        check("AC4 공개 단계와 비공개 특징", !items.raw().contains("손잡이 테이프 " + tag) && !items.raw().contains("secret") && stage1, "");

        // AC17~AC19 R9 특징 보완 → 공개 2단계 (색상·상세 장소·특징 네 칸)
        List<Map<String, Object>> review = staff.get("/api/office/items?status=review").list();
        if (review.isEmpty()) {
            check("AC17 보완 대상(보관 7일 이상) 물건 있음", false, "시드 데이터를 초기화하세요: docker compose down -v");
        } else {
            Map<String, Object> target = review.get(0);
            String no = (String) target.get("receipt_no");
            Res r19 = staff.post("/api/office/items/" + no + "/review", Map.of("feat_attached", "보완 " + target.get("secret_feature")));
            check("AC19 확인용 특징이 든 특징 칸 거절", r19.status() == 422, r19.status());
            String feature = "보완한 특징 " + tag;
            Res r17 = staff.post("/api/office/items/" + no + "/review", Map.of("feat_contents", feature));
            Map<String, Object> shown = student.get("/api/items").list().stream()
                    .filter(i -> no.equals(i.get("receipt_no"))).findFirst().orElse(Map.of());
            check("AC17 보완 후 공개 목록 2단계와 특징 공개", r17.status() == 200
                    && Integer.valueOf(2).equals(num(shown.get("stage"))) && feature.equals(shown.get("feat_contents"))
                    && shown.get("color") != null && !String.valueOf(shown).contains("secret"), shown);
        }
        Res r18 = staff.post("/api/office/items/" + receipt + "/review", Map.of("feat_contents", "새로 들어온 우산"));
        check("AC18 보관 7일 전 보완 거절", r18.status() == 409, r18.status());

        // AC20~AC21 R10 경찰 인계
        Res r21 = staff.post("/api/office/items/" + receipt + "/transfer", Map.of("officer_name", "김경장"));
        check("AC21 보관 30일 전 인계 거절", r21.status() == 409, r21.status());
        List<Map<String, Object>> due = staff.get("/api/office/items?status=transfer").list();
        if (!due.isEmpty()) {
            String no = (String) due.get(0).get("receipt_no");
            Res r20 = staff.post("/api/office/items/" + no + "/transfer", Map.of("officer_name", "김경장", "note", "인계증 " + tag));
            boolean listed = student.get("/api/items").list().stream().anyMatch(i -> no.equals(i.get("receipt_no")));
            check("AC20 인계 후 공개 목록에서 빠짐", r20.status() == 200 && "transferred".equals(r20.map().get("status")) && !listed, r20.raw());
        } else {
            check("AC20 인계 기록 있음 (이전 실행에서 인계)", !staff.get("/api/office/items?status=transferred").list().isEmpty(),
                    "인계 대상도 인계 기록도 없음: docker compose down -v");
        }

        // 분실 신고
        Map<String, Object> earbuds = new LinkedHashMap<>(Map.of("category", "무선이어폰", "color", "흰색",
                "feat_brand", "에어팟 프로2", "feat_attached", "먼치킨 고양이 스티커", "feat_damage", "", "feat_contents", "",
                "building_ids", List.of(bid.get("중앙도서관"), bid.get("AI관")),
                "lost_from", now.minusDays(30).format(f), "lost_to", now.format(f)));
        // 시드 F0001의 습득 시각은 DB를 처음 만든 때 기준(3시간 전)이라, DB가 오래되면 최근 몇 시간 범위 밖이 된다 (문제 10)

        // AC7 추상적 신고 거절
        Map<String, Object> vague = new LinkedHashMap<>(earbuds);
        vague.put("feat_brand", "");
        vague.put("feat_attached", "");
        Res r7 = student.post("/api/reports", vague);
        Object questions = r7.map().get("detail") instanceof Map<?, ?> d ? d.get("questions") : null;
        check("AC7 추상적 신고 거절과 보완 질문", r7.status() == 422 && questions instanceof List<?> q && !q.isEmpty(), r7.raw());

        // AC5 / AC9 매칭 (담당자 계정으로 신고: 학생 계정은 AC12에 쓴다)
        Res r5 = staff.post("/api/reports", earbuds);
        if (aiOn) {
            List<?> matches = (List<?>) r5.map().getOrDefault("matches", List.of());
            Object top = matches.isEmpty() ? null : ((Map<?, ?>) matches.get(0)).get("receipt_no");
            check("AC5 고양이 스티커 이어폰이 1순위", r5.status() == 201 && "F0001".equals(top), r5.raw());
            check("AC5 분실자 화면에 근거·확인용 특징 없음", !r5.raw().contains("reason") && !r5.raw().contains("secret"), r5.raw());
        } else {
            Object fb = r5.map().get("fallback");
            check("AC9 AI 꺼짐 → 조건 목록 대체", r5.status() == 201 && "unavailable".equals(r5.map().get("ai_status"))
                    && fb instanceof List<?> l && !l.isEmpty(), r5.raw());
        }

        // AC12 진행 중 신고 3건 제한
        List<Integer> codes = new ArrayList<>();
        for (int i = 0; i < 4; i++) codes.add(student.post("/api/reports", earbuds).status());
        check("AC12 네 번째 신고 거절", codes.equals(List.of(201, 201, 201, 409)), codes);
        cancelActive(student);

        // AC16 인적사항 빈칸 거절, AC10 수령, AC11 재수령 거절
        Res rr = staff.post("/api/reports", Map.of("category", "우산", "color", "검정", "feat_brand", "장우산",
                "feat_attached", attached, "building_ids", List.of(), "lost_from", now.minusHours(2).format(f), "lost_to", now.format(f)));
        Map<String, Object> person = new LinkedHashMap<>(Map.of("report_no", String.valueOf(rr.map().get("report_no")),
                "receiver_name", "김가천", "receiver_no", "2023" + tag, "receiver_phone", "010-1234-5678", "id_type", "학생증"));
        Map<String, Object> noPhone = new LinkedHashMap<>(person);
        noPhone.put("receiver_phone", "");
        Res r16 = staff.post("/api/office/items/" + receipt + "/return", noPhone);
        check("AC16 연락처 빈칸 거절", r16.status() == 422, r16.status());
        Res r10 = staff.post("/api/office/items/" + receipt + "/return", person);
        Map<String, Object> saved = staff.get("/api/office/items?status=returned").list().stream()
                .filter(i -> receipt.equals(i.get("receipt_no"))).findFirst().orElse(Map.of());
        boolean stillListed = student.get("/api/items").list().stream().anyMatch(i -> receipt.equals(i.get("receipt_no")));
        check("AC10 수령 완료와 인적사항 기록", r10.status() == 200 && "김가천".equals(saved.get("receiver_name"))
                && staffEmail.equalsIgnoreCase(String.valueOf(saved.get("handled_by"))) && !stillListed, r10.raw());
        Res r11 = staff.post("/api/office/items/" + receipt + "/return", person);
        check("AC11 재수령 거절", r11.status() == 409, r11.status());
        cancelActive(staff);

        long passed = RESULTS.stream().filter(b -> b).count();
        System.out.println("\n" + passed + "/" + RESULTS.size() + " 통과. AC14(재실행 후 유지)는 docker compose down → up 후 목록을 다시 확인하세요.");
    }

    static Integer num(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }

    /** 이 점검에 필요한 만큼만 다루는 작은 JSON 읽기·쓰기. */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String text) {
            Json j = new Json(text);
            Object v = j.value();
            j.ws();
            if (j.i != j.s.length()) throw new IllegalArgumentException("JSON 뒤에 남은 글자");
            return v;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') {
                Map<String, Object> m = new LinkedHashMap<>();
                i++;
                ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws();
                    String k = string();
                    ws();
                    i++; // :
                    m.put(k, value());
                    ws();
                    if (s.charAt(i++) == '}') return m;
                }
            }
            if (c == '[') {
                List<Object> l = new ArrayList<>();
                i++;
                ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    l.add(value());
                    ws();
                    if (s.charAt(i++) == ']') return l;
                }
            }
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return true; }
            if (s.startsWith("false", i)) { i += 5; return false; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(start, i);
            return n.contains(".") || n.contains("e") || n.contains("E") ? (Object) Double.parseDouble(n) : (Object) Long.parseLong(n);
        }

        private String string() {
            StringBuilder b = new StringBuilder();
            i++; // "
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> b.append('\n');
                        case 't' -> b.append('\t');
                        case 'r' -> b.append('\r');
                        case 'b' -> b.append('\b');
                        case 'f' -> b.append('\f');
                        case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        default -> b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
        }

        static String write(Object v) {
            if (v == null) return "null";
            if (v instanceof String str) {
                StringBuilder b = new StringBuilder("\"");
                for (char c : str.toCharArray()) {
                    if (c == '"' || c == '\\') b.append('\\').append(c);
                    else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
                return b.append('"').toString();
            }
            if (v instanceof Map<?, ?> m) {
                List<String> parts = new ArrayList<>();
                m.forEach((k, val) -> parts.add(write(String.valueOf(k)) + ":" + write(val)));
                return "{" + String.join(",", parts) + "}";
            }
            if (v instanceof List<?> l) {
                List<String> parts = new ArrayList<>();
                l.forEach(x -> parts.add(write(x)));
                return "[" + String.join(",", parts) + "]";
            }
            return String.valueOf(v);
        }
    }
}
