package kr.ac.gachon.lostfound;

import kr.ac.gachon.lostfound.Policy.Scored;
import kr.ac.gachon.lostfound.Models.FoundItem;
import kr.ac.gachon.lostfound.Models.OwnerView;
import kr.ac.gachon.lostfound.Models.PublicView;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DB 없이 돌리는 규칙 테스트. mvn test */
class PolicyTest {

    static FoundItem item(double hoursAgo) {
        return item(hoursAgo, 1, "stored", null, null);
    }

    static FoundItem item(double hoursAgo, int id, String status, OffsetDateTime reviewedAt,
                          OffsetDateTime transferredAt) {
        OffsetDateTime at = Policy.now().minusMinutes(Math.round(hoursAgo * 60));
        return new FoundItem(id, 1, "공과대학1", "1층", at, status, "우산", "검정", "3단", "", "살 휨", "",
                "J.H 각인", reviewedAt, transferredAt, null, null, null, at, at);
    }

    // R3 공개 단계와 R9 보완 필요 (AC4)
    // 기준 시각(Policy.now())은 물건을 만든 뒤에 잰다
    @Test
    void needsReviewAfter7Days() {
        assertFalse(Policy.needsReview(item(1), Policy.now()));
        assertFalse(Policy.needsReview(item(6 * 24 + 23), Policy.now()));
        assertTrue(Policy.needsReview(item(7 * 24), Policy.now()));
        assertTrue(Policy.needsReview(item(40 * 24), Policy.now()));
    }

    @Test
    void reviewedOrNotStoredItemsDoNotNeedReview() {
        assertFalse(Policy.needsReview(item(240, 1, "stored", Policy.now(), null), Policy.now()));
        assertFalse(Policy.needsReview(item(240, 1, "returned", null, null), Policy.now()));
        assertFalse(Policy.needsReview(item(240, 1, "waiting", null, null), Policy.now()));
    }

    @Test
    void publicViewBeforeAndAfterReview() {
        PublicView before = Policy.publicView(item(240));
        PublicView after = Policy.publicView(item(240, 1, "stored", Policy.now(), null));
        assertEquals(1, before.stage());
        assertNull(before.color());
        assertNull(before.featBrand());
        assertNull(before.featDamage());
        assertEquals(2, after.stage());
        assertEquals("검정", after.color());
        assertEquals("1층", after.placeDetail());
        assertEquals("3단", after.featBrand());     // 2단계: 특징 네 칸 공개 (빈 칸은 빠짐)
        assertEquals("살 휨", after.featDamage());
        assertNull(after.featAttached());
        for (PublicView v : List.of(before, after)) {
            assertFalse(v.toString().contains("J.H"));   // 확인용 특징은 공개하지 않음
            assertEquals(Policy.OFFICE_NAME, v.office());
        }
    }

    @Test
    void unknownFoundTimeUsesCreatedAt() {
        OffsetDateTime created = Policy.now().minusDays(9);
        FoundItem it = new FoundItem(1, null, null, "", null, "stored", "우산", "검정", "", "", "", "",
                "비밀", null, null, null, null, null, created, created);
        assertEquals(9, Policy.daysKept(it, Policy.now()));
        assertTrue(Policy.needsReview(it, Policy.now()));
        assertNull(Policy.publicView(it).foundDate());
        assertEquals("모름", Policy.publicView(it).building());
    }

    // R10 경찰 인계 (AC20~AC22)
    @Test
    void needsTransferAfter30Days() {
        assertFalse(Policy.needsTransfer(item(29 * 24), Policy.now()));
        assertTrue(Policy.needsTransfer(item(30 * 24), Policy.now()));
        assertTrue(Policy.needsTransfer(item(60 * 24), Policy.now()));
    }

    @Test
    void transferredItemPointsToPolice() {
        FoundItem it = item(31 * 24, 1, "transferred", null, Policy.now());
        assertFalse(Policy.needsTransfer(it, Policy.now()));
        assertFalse(Policy.needsReview(it, Policy.now()));
        OwnerView view = Policy.ownerView(it, 90);
        assertEquals(Policy.POLICE_NAME, view.office());
        assertTrue(view.officeLocation().contains("인계됨"));
        assertEquals(Policy.POLICE_NAME, Policy.publicView(it).office());
        assertEquals(Policy.OFFICE_NAME, Policy.ownerView(item(1), 90).office());
    }

    // R9, R8 공개 설명에 확인용 특징 포함 여부 (AC19)
    @Test
    void noteRevealsSecret() {
        assertTrue(Policy.noteRevealsSecret("손잡이에 J.H 각인 있음", "J.H 각인"));
        assertTrue(Policy.noteRevealsSecret("이니셜jh각인", "J.H 각인")); // 공백·기호·대소문자 무시
        assertFalse(Policy.noteRevealsSecret("3단 접이식, 노란 리본", "J.H 각인"));
        assertFalse(Policy.noteRevealsSecret("아무 설명", ""));
        // 바꿔 쓴 표현이라도 숫자·이니셜 같은 식별 표시가 들어가면 거절 (P5)
        assertTrue(Policy.noteRevealsSecret("바닥에 17이라고 적혀 있음", "바닥에 매직으로 쓴 숫자 17"));
        assertTrue(Policy.noteRevealsSecret("손잡이에 jh라고 새겨짐", "손잡이 끝에 이니셜 J.H 각인"));
        assertFalse(Policy.noteRevealsSecret("은색 텀블러, 축구공 스티커", "바닥에 매직으로 쓴 숫자 17"));
        assertFalse(Policy.noteRevealsSecret("3단 접이식 검정 우산", "손잡이 끝에 이니셜 J.H 각인"));
    }

    // R4, R8 구체성 규칙 (AC7)
    static Map<String, String> feats(String brand, String attached) {
        return Map.of("brand", brand, "attached", attached, "damage", "", "contents", "");
    }

    @Test
    void vagueReportRejected() {
        assertFalse(Policy.specificityProblems("흰색", feats("", "")).isEmpty());     // 색상만
        assertFalse(Policy.specificityProblems("모름", feats("이어폰", "")).isEmpty()); // 특징 하나
        assertFalse(Policy.specificityProblems("흰색", feats("폰", "")).isEmpty());   // 글자 수 부족
        assertTrue(Policy.specificityProblems("흰색", feats("", "")).size() <= 2);
    }

    @Test
    void specificReportPasses() {
        assertTrue(Policy.specificityProblems("흰색", feats("", "고양이 스티커")).isEmpty());
        assertTrue(Policy.specificityProblems("모름", feats("에어팟 프로", "스티커")).isEmpty());
    }

    // R5 표시 규칙
    @Test
    void gradeAndShownMatches() {
        assertEquals("높음", Policy.grade(100));
        assertEquals("높음", Policy.grade(85));
        assertEquals("보통", Policy.grade(84));
        assertEquals("보통", Policy.grade(70));
        assertNull(Policy.grade(69));
        int[] scores = {60, 90, 75, 88, 71};
        List<Scored> pairs = new java.util.ArrayList<>();
        for (int i = 0; i < scores.length; i++) pairs.add(new Scored(item(1, i, "stored", null, null), scores[i]));
        assertEquals(List.of(90, 88, 75), Policy.shownMatches(pairs).stream().map(Scored::score).toList());
    }

    @Test
    void ownerViewHidesDetails() {
        OwnerView view = Policy.ownerView(item(1, 7, "stored", null, null), 90);
        assertEquals(new OwnerView("F0007", "높음", "우산", "학생지원팀", "학생회관 302호"), view);
    }

    @Test
    void numberParsing() {
        assertEquals(12, Policy.parseNo("F0012", 'F'));
        assertEquals(12, Policy.parseNo("f12", 'F'));
        assertEquals(3, Policy.parseNo(" 3 ", 'L'));
        assertNull(Policy.parseNo("X1", 'F'));
        assertNull(Policy.parseNo("F0", 'F'));
        assertNull(Policy.parseNo("", 'F'));
        assertNull(Policy.parseNo("F99999999999", 'F'));
    }

    // R11 학교 이메일만 허용 (AC23)
    @Test
    void onlyGachonEmail() {
        assertEquals("kim123@gachon.ac.kr", Policy.schoolEmail(" Kim123@Gachon.ac.kr "));
        assertEquals("a.b-c@gachon.ac.kr", Policy.schoolEmail("a.b-c@gachon.ac.kr"));
        assertNull(Policy.schoolEmail("kim@gmail.com"));
        assertNull(Policy.schoolEmail("kim@gachon.ac.kr.example.com"));
        assertNull(Policy.schoolEmail("kim@mail.gachon.ac.kr"));
        assertNull(Policy.schoolEmail("@gachon.ac.kr"));
        assertNull(Policy.schoolEmail("김가천@gachon.ac.kr"));
        assertNull(Policy.schoolEmail(null));
    }

    // R11 담당자로 등록한 외부 이메일만 예외 (AC27)
    @Test
    void staffEmailMayUseOtherDomain() {
        java.util.Set<String> staff = java.util.Set.of("office.test@gmail.com");
        assertEquals("office.test@gmail.com", Policy.allowedEmail("Office.Test@gmail.com", staff));
        assertEquals("kim@gachon.ac.kr", Policy.allowedEmail("kim@gachon.ac.kr", staff));
        assertNull(Policy.allowedEmail("someone@gmail.com", staff));
        assertNull(Policy.allowedEmail("someone@gmail.com", java.util.Set.of()));
        assertNull(Policy.allowedEmail("not-an-email", staff));
    }

    // R9 보완: 공개할 특징 칸이 확인용 특징을 드러내면 그 칸 이름을 알려 준다 (AC19)
    @Test
    void revealingFeaturesNamesTheField() {
        Map<String, String> f = Map.of("brand", "장우산", "attached", "손잡이에 J.H 각인", "damage", "", "contents", "");
        assertEquals(List.of("부착물·장식"), Policy.revealingFeatures(f, "손잡이 끝에 이니셜 J.H 각인"));
        assertEquals(List.of(), Policy.revealingFeatures(Map.of("brand", "장우산", "attached", "노란 리본",
                "damage", "", "contents", ""), "손잡이 끝에 이니셜 J.H 각인"));
    }
}
