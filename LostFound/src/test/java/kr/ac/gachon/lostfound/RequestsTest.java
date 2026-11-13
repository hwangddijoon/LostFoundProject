package kr.ac.gachon.lostfound;

import kr.ac.gachon.lostfound.Requests.KstDateTime;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 입력 검사 (AC2, AC16, AC23). */
class RequestsTest {

    @Test
    void futureFoundTimeRejected() {
        OffsetDateTime future = Policy.now().plusDays(1);
        assertThrows(ApiException.class, () -> new Requests.FinderItem(1, "", future));
        new Requests.FinderItem(1, " 1층 ", Policy.now().minusHours(1));
    }

    @Test
    void lostRangeChecked() {
        OffsetDateTime t = Policy.now();
        new Requests.LostReportForm("지갑", "파랑", "반지갑", "", "", "", List.of(),
                t.minusHours(3), t.minusMinutes(1));
        assertThrows(ApiException.class, () -> new Requests.LostReportForm("지갑", "파랑", "", "", "", "",
                List.of(), t.minusHours(1), t.minusHours(2)));
        assertThrows(ApiException.class, () -> new Requests.LostReportForm("", "파랑", "", "", "", "",
                List.of(), t.minusHours(2), t.minusHours(1)));
    }

    @Test
    void reviewNeedsAtLeastOneFeature() {
        new Requests.Review("3단 접이식", null, null, null);
        assertThrows(ApiException.class, () -> new Requests.Review("   ", "", null, null)); // 네 칸 모두 비면 거절
    }

    @Test
    void handoverRequiresPersonalInfo() {
        new Requests.Handover("", "김가천", "202312345", "010-1234-5678", "학생증");
        assertThrows(ApiException.class, () -> new Requests.Handover("", "김가천", "202312345", "", "학생증"));
        assertThrows(ApiException.class, () -> new Requests.Handover("", " ", "202312345", "010-1234-5678", "학생증"));
    }

    @Test
    void browserTimeWithoutZoneIsKst() {
        assertEquals("2026-11-11T09:00+09:00", KstDateTime.parse("2026-11-11T09:00").toString());
        assertEquals("2026-11-11T00:00Z", KstDateTime.parse("2026-11-11T00:00Z").toString());
        assertThrows(ApiException.class, () -> KstDateTime.parse("어제"));
    }

    @Test
    void authRequestsCheckSchoolEmail() {
        ApiException e = assertThrows(ApiException.class, () -> new Requests.CodeRequest("이메일 아님"));
        assertEquals("가천대학교 이메일로 인증해주세요", e.getMessage());
        assertEquals(422, e.status());
        assertEquals("kim@gachon.ac.kr", new Requests.CodeRequest("KIM@gachon.ac.kr").email());
        assertEquals("office@gmail.com", new Requests.CodeRequest(" Office@Gmail.com").email()); // 도메인은 AuthService가 봄
        assertThrows(ApiException.class, () -> new Requests.LoginRequest("kim@gachon.ac.kr", ""));
        assertThrows(ApiException.class, () -> new Requests.VerifyRequest("kim@gachon.ac.kr", "12345", "password1"));
        assertThrows(ApiException.class, () -> new Requests.VerifyRequest("kim@gachon.ac.kr", "123456", "short"));
        new Requests.VerifyRequest("kim@gachon.ac.kr", " 012345 ", "password1");
    }
}
