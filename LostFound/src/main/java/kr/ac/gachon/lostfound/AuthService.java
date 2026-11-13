package kr.ac.gachon.lostfound;

import kr.ac.gachon.lostfound.Models.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * R11 로그인: 학교 이메일로 받은 인증번호 6자리를 확인하고 비밀번호를 정한다. 이후에는 이메일 + 비밀번호로 로그인.
 * 로그인하면 무작위 토큰을 쿠키로 주고, DB에는 토큰의 해시만 남긴다.
 * 비밀번호는 PBKDF2(Java 기본 기능)로 해시해 저장한다.
 */
@Service
public class AuthService {
    private static final SecureRandom RANDOM = new SecureRandom();
    static final int PBKDF2_ITERATIONS = 120_000;

    private final Db db;
    private final Mailer mailer;
    private final Set<String> staffEmails;

    public AuthService(Db db, Mailer mailer, @Value("${STAFF_EMAILS:}") String staffEmails) {
        this.db = db;
        this.mailer = mailer;
        this.staffEmails = parseStaff(staffEmails);
    }

    /** STAFF_EMAILS="a@gachon.ac.kr,office@gmail.com" → 담당자 목록 (도메인 무관). 이메일 형식이 아닌 값은 무시한다. */
    static Set<String> parseStaff(String text) {
        return Arrays.stream(text == null ? new String[0] : text.split(","))
                .map(Policy::normalEmail).filter(e -> e != null).collect(Collectors.toUnmodifiableSet());
    }

    /** 학교 이메일도 등록된 담당자 이메일도 아니면 422 '가천대학교 이메일로 인증해주세요'. */
    private void requireAllowed(String email) {
        if (Policy.allowedEmail(email, staffEmails) == null) throw ApiException.invalid(Policy.NOT_SCHOOL_EMAIL);
    }

    public boolean isStaff(String email) {
        return staffEmails.contains(email);
    }

    // ---------- 1. 인증번호 받기 ----------

    @Transactional
    public void sendCode(Requests.CodeRequest body) {
        requireAllowed(body.email());
        OffsetDateTime now = Policy.now();
        Db.EmailCode old = db.emailCode(body.email());
        if (old != null && old.sentAt().plusSeconds(Policy.CODE_RESEND_SECONDS).isAfter(now)) {
            throw new ApiException(429, "인증번호는 " + Policy.CODE_RESEND_SECONDS + "초에 한 번 받을 수 있습니다. 메일함을 먼저 확인해 주세요.");
        }
        String code = newCode();
        db.saveEmailCode(body.email(), sha256(body.email() + ":" + code), now,
                now.plusMinutes(Policy.CODE_VALID_MINUTES));
        mailer.sendCode(body.email(), code); // 실패하면 503, 트랜잭션이 취소되어 저장한 번호도 사라진다
    }

    static String newCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    // ---------- 2. 인증번호 확인 + 비밀번호 정하기 → 로그인 ----------

    /** 맞으면 세션 토큰(쿠키 값)을 돌려준다. 처음이면 가입, 이미 가입했으면 비밀번호 재설정. */
    @Transactional(noRollbackFor = ApiException.class) // 틀린 횟수는 거절해도 남겨야 한다
    public String verify(Requests.VerifyRequest body) {
        requireAllowed(body.email());
        Db.EmailCode saved = db.emailCode(body.email());
        if (saved == null) throw new ApiException(400, "인증번호를 먼저 받아 주세요.");
        if (saved.expiresAt().isBefore(Policy.now())) {
            db.deleteEmailCode(body.email());
            throw new ApiException(400, "인증번호가 만료되었습니다. 다시 받아 주세요.");
        }
        if (!sameText(saved.codeHash(), sha256(body.email() + ":" + body.code()))) {
            int left = Policy.CODE_MAX_ATTEMPTS - saved.attempts() - 1;
            if (left <= 0) {
                db.deleteEmailCode(body.email());
                throw new ApiException(400, "인증번호를 " + Policy.CODE_MAX_ATTEMPTS + "번 틀렸습니다. 다시 받아 주세요.");
            }
            db.countCodeAttempt(body.email());
            throw new ApiException(400, "인증번호가 맞지 않습니다. (남은 시도 " + left + "번)");
        }
        db.deleteEmailCode(body.email());
        db.saveUser(body.email(), hashPassword(body.password()));
        db.deleteSessionsOf(body.email());
        return startSession(body.email());
    }

    // ---------- 3. 로그인 / 로그아웃 / 현재 사용자 ----------

    @Transactional
    public String login(Requests.LoginRequest body) {
        requireAllowed(body.email());
        String hash = db.passwordHash(body.email());
        if (hash == null || !checkPassword(body.password(), hash)) {
            throw new ApiException(401, "이메일 또는 비밀번호가 맞지 않습니다. 처음이면 '회원가입'에서 인증해 주세요.");
        }
        return startSession(body.email());
    }

    private String startSession(String email) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        db.saveSession(sha256(token), email, Policy.now().plusDays(Policy.SESSION_DAYS));
        return token;
    }

    public void logout(String token) {
        if (token != null && !token.isEmpty()) db.deleteSession(sha256(token));
    }

    /** 쿠키의 토큰으로 사용자를 찾는다. 로그인하지 않았거나 만료되면 401. */
    public User currentUser(String token) {
        String email = token == null || token.isEmpty() ? null : db.sessionEmail(sha256(token));
        if (email == null || Policy.allowedEmail(email, staffEmails) == null) {
            throw new ApiException(401, "로그인이 필요합니다."); // STAFF_EMAILS에서 빠진 외부 이메일은 더 쓸 수 없다
        }
        return new User(email, isStaff(email));
    }

    // ---------- 해시 ----------

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean sameText(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** "pbkdf2$반복횟수$salt$hash" (Base64). */
    static String hashPassword(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return "pbkdf2$" + PBKDF2_ITERATIONS + "$" + b64(salt) + "$" + b64(pbkdf2(password, salt, PBKDF2_ITERATIONS));
    }

    static boolean checkPassword(String password, String stored) {
        String[] p = stored.split("\\$");
        if (p.length != 4 || !p[0].equals("pbkdf2")) return false;
        byte[] expected = Base64.getDecoder().decode(p[3]);
        byte[] actual = pbkdf2(password, Base64.getDecoder().decode(p[2]), Integer.parseInt(p[1]));
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
