package kr.ac.gachon.lostfound;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R11 로그인에서 DB 없이 확인할 수 있는 부분: 비밀번호 해시, 인증번호, 담당자 목록. */
class AuthTest {

    @Test
    void passwordHashRoundTrip() {
        String stored = AuthService.hashPassword("gachon2026!");
        assertTrue(stored.startsWith("pbkdf2$"));
        assertFalse(stored.contains("gachon2026!"));          // 비밀번호 원문은 저장하지 않음
        assertTrue(AuthService.checkPassword("gachon2026!", stored));
        assertFalse(AuthService.checkPassword("gachon2026?", stored));
        assertFalse(AuthService.hashPassword("gachon2026!").equals(stored)); // salt가 달라 해시도 다름
        assertFalse(AuthService.checkPassword("x", "plain-text"));
    }

    @Test
    void codeIsSixDigits() {
        for (int i = 0; i < 200; i++) {
            assertTrue(AuthService.newCode().matches("\\d{6}"));
        }
    }

    @Test
    void codeHashDependsOnEmail() {
        assertFalse(AuthService.sha256("a@gachon.ac.kr:123456").equals(AuthService.sha256("b@gachon.ac.kr:123456")));
        assertTrue(AuthService.sameText(AuthService.sha256("x"), AuthService.sha256("x")));
    }

    @Test
    void staffListAcceptsAnyValidEmail() {
        assertEquals(Set.of("staff@gachon.ac.kr", "lee@gachon.ac.kr", "office@gmail.com"),
                AuthService.parseStaff(" Staff@gachon.ac.kr, lee@gachon.ac.kr ,Office@gmail.com,,잘못된값"));
        assertEquals(Set.of(), AuthService.parseStaff(""));
        assertEquals(Set.of(), AuthService.parseStaff(null));
    }

    /** P8: MAIL_FROM을 비워 두면 빈 문자열이 넘어와도 MAIL_USERNAME을 보내는 주소로 쓴다. */
    @Test
    void emptyMailFromFallsBackToUsername() {
        assertEquals("sender@gmail.com", Mailer.senderAddress("", "sender@gmail.com"));
        assertEquals("sender@gmail.com", Mailer.senderAddress("  ", " sender@gmail.com "));
        assertEquals("from@gmail.com", Mailer.senderAddress("from@gmail.com", "sender@gmail.com"));
        assertEquals("", Mailer.senderAddress(null, null));
    }
}
