package kr.ac.gachon.lostfound;

import kr.ac.gachon.lostfound.Models.MatchResponse;
import kr.ac.gachon.lostfound.Models.OfficeItemView;
import kr.ac.gachon.lostfound.Models.PublicView;
import kr.ac.gachon.lostfound.Models.ReportView;
import kr.ac.gachon.lostfound.Models.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP 주소 전부. 요청을 받아 로그인을 확인하고 AuthService / LostFoundService로 넘긴다.
 * - 로그인 없이: /health, /ready, /api/options, /api/auth/*
 * - 로그인 필요(R11): 나머지 /api/*
 * - 보관소 담당자만: /api/office/*
 * 오류는 아래 @ExceptionHandler에서 {"detail": {"message": ..., "questions": [...]}} 한 가지 모양으로 바꾼다.
 */
@RestController
public class ApiController {
    private static final Logger LOG = LoggerFactory.getLogger(ApiController.class);
    static final String COOKIE = "LF_SESSION";

    private final LostFoundService service;
    private final AuthService auth;
    private final Db db;
    private final GeminiClient ai;
    private final Mailer mailer;
    private final String version;
    private final boolean secureCookie;

    public ApiController(LostFoundService service, AuthService auth, Db db, GeminiClient ai, Mailer mailer,
                         @Value("${app.version}") String version,
                         @Value("${COOKIE_SECURE:0}") String secureCookie) {
        this.service = service;
        this.auth = auth;
        this.db = db;
        this.ai = ai;
        this.mailer = mailer;
        this.version = version;
        this.secureCookie = "1".equals(secureCookie.strip());
    }

    private User user(String token) {
        return auth.currentUser(token);
    }

    private User staff(String token) {
        User u = auth.currentUser(token);
        if (!u.staff()) throw new ApiException(403, "보관소 담당자만 사용할 수 있습니다.");
        return u;
    }

    // ---------- 공통 ----------

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ok");
        m.put("version", version);
        m.put("instance", hostname());
        m.put("ai", ai.enabled());
        m.put("ai_key_problem", ai.keyProblem());
        m.put("mail", mailer.logOnly() ? "log" : "smtp");
        return m;
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }

    @GetMapping("/ready")
    public Map<String, Object> ready() {
        db.ping();
        return Map.of("status", "ready");
    }

    @GetMapping("/api/options")
    public Map<String, Object> options() {
        return service.options(version);
    }

    // ---------- R11 로그인 ----------

    /** 세션 쿠키: 자바스크립트에서 읽을 수 없고(HttpOnly), 다른 사이트에서 보낸 요청에는 실리지 않는다(SameSite=Lax). */
    private ResponseEntity<Map<String, Object>> withSession(String token, User user) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE, token).httpOnly(true).secure(secureCookie)
                .sameSite("Lax").path("/").maxAge(Duration.ofDays(Policy.SESSION_DAYS)).build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(me(user));
    }

    private static Map<String, Object> me(User user) {
        return Map.of("email", user.email(), "staff", user.staff());
    }

    @PostMapping("/api/auth/code")
    public Map<String, Object> sendCode(@RequestBody Requests.CodeRequest body) {
        auth.sendCode(body);
        return Map.of("message", body.email() + "로 인증번호 6자리를 보냈습니다. "
                + Policy.CODE_VALID_MINUTES + "분 안에 입력해 주세요.");
    }

    @PostMapping("/api/auth/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestBody Requests.VerifyRequest body) {
        String token = auth.verify(body);
        return withSession(token, new User(body.email(), auth.isStaff(body.email())));
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Requests.LoginRequest body) {
        String token = auth.login(body);
        return withSession(token, new User(body.email(), auth.isStaff(body.email())));
    }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<Map<String, Object>> logout(@CookieValue(value = COOKIE, required = false) String token) {
        auth.logout(token);
        ResponseCookie gone = ResponseCookie.from(COOKIE, "").httpOnly(true).secure(secureCookie)
                .sameSite("Lax").path("/").maxAge(0).build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, gone.toString()).body(Map.of("status", "logged_out"));
    }

    @GetMapping("/api/auth/me")
    public Map<String, Object> currentUser(@CookieValue(value = COOKIE, required = false) String token) {
        return me(user(token));
    }

    // ---------- R3, R1 습득물 ----------

    @GetMapping("/api/items")
    public List<PublicView> publicItems(@CookieValue(value = COOKIE, required = false) String token,
                                        @RequestParam(defaultValue = "") String category) {
        user(token);
        return service.publicItems(category);
    }

    @PostMapping("/api/items")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> finderRegister(@CookieValue(value = COOKIE, required = false) String token,
                                              @RequestBody Requests.FinderItem body) {
        return service.finderRegister(user(token), body);
    }

    // ---------- R4, R5, R8 분실 신고 ----------

    @PostMapping("/api/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public MatchResponse createReport(@CookieValue(value = COOKIE, required = false) String token,
                                      @RequestBody Requests.LostReportForm body) {
        return service.createReport(user(token), body);
    }

    @GetMapping("/api/reports/mine")
    public List<ReportView> myReports(@CookieValue(value = COOKIE, required = false) String token) {
        return service.myReports(user(token));
    }

    @PostMapping("/api/reports/{reportNo}/match")
    public MatchResponse rematch(@CookieValue(value = COOKIE, required = false) String token,
                                 @PathVariable String reportNo) {
        return service.rematch(user(token), reportNo);
    }

    @PostMapping("/api/reports/{reportNo}/cancel")
    public Map<String, Object> cancel(@CookieValue(value = COOKIE, required = false) String token,
                                      @PathVariable String reportNo) {
        return service.cancelReport(user(token), reportNo);
    }

    // ---------- 보관소: R2, R6, R9, R10 (담당자만) ----------

    @GetMapping("/api/office/items")
    public List<OfficeItemView> officeItems(@CookieValue(value = COOKIE, required = false) String token,
                                            @RequestParam(defaultValue = "stored") String status) {
        staff(token);
        return service.officeItems(status);
    }

    @PostMapping("/api/office/items")
    @ResponseStatus(HttpStatus.CREATED)
    public OfficeItemView officeRegister(@CookieValue(value = COOKIE, required = false) String token,
                                         @RequestBody Requests.ItemDetails body) {
        return service.officeRegister(staff(token), body);
    }

    @PostMapping("/api/office/items/{receiptNo}/store")
    public OfficeItemView store(@CookieValue(value = COOKIE, required = false) String token,
                                @PathVariable String receiptNo, @RequestBody Requests.ItemDetails body) {
        staff(token);
        return service.storeItem(receiptNo, body);
    }

    @PostMapping("/api/office/items/{receiptNo}/review")
    public OfficeItemView review(@CookieValue(value = COOKIE, required = false) String token,
                                 @PathVariable String receiptNo, @RequestBody Requests.Review body) {
        staff(token);
        return service.reviewItem(receiptNo, body);
    }

    @PostMapping("/api/office/items/{receiptNo}/transfer")
    public OfficeItemView transfer(@CookieValue(value = COOKIE, required = false) String token,
                                   @PathVariable String receiptNo, @RequestBody Requests.Transfer body) {
        staff(token);
        return service.transferItem(receiptNo, body);
    }

    @PostMapping("/api/office/transfer-all")
    public Map<String, Object> transferAll(@CookieValue(value = COOKIE, required = false) String token,
                                           @RequestBody Requests.Transfer body) {
        staff(token);
        return service.transferAllDue(body);
    }

    @PostMapping("/api/office/items/{receiptNo}/return")
    public Map<String, Object> handOver(@CookieValue(value = COOKIE, required = false) String token,
                                        @PathVariable String receiptNo, @RequestBody Requests.Handover body) {
        return service.returnItem(staff(token), receiptNo, body);
    }

    @GetMapping("/api/office/reports/{reportNo}")
    public Map<String, Object> officeReport(@CookieValue(value = COOKIE, required = false) String token,
                                            @PathVariable String reportNo) {
        staff(token);
        return service.officeReport(reportNo);
    }

    // ---------- 오류 응답 ----------

    static ResponseEntity<Map<String, Object>> error(int status, String message, List<String> questions) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("message", message);
        if (questions != null && !questions.isEmpty()) detail.put("questions", questions);
        return ResponseEntity.status(status).body(Map.of("detail", detail));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> apiError(ApiException e) {
        return error(e.status(), e.getMessage(), e.questions());
    }

    /** JSON 형식 오류. Requests의 record 생성자에서 난 검사 오류(ApiException)는 원래 문장을 꺼내 보여준다. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ApiException api) return apiError(api);
        }
        return error(422, "입력 형식이 맞지 않습니다. 빠진 칸이나 숫자·날짜 형식을 확인해 주세요.", null);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> database(DataAccessException e) {
        LOG.error("database operation failed: {}", e.getClass().getSimpleName());
        return error(503, "데이터베이스에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.", null);
    }
}
