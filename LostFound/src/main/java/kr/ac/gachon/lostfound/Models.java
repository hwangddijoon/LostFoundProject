package kr.ac.gachon.lostfound;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 데이터 모양 모음. DB 한 행(FoundItem, LostReport, User)과 화면별로 내보내는 응답(…View).
 * JSON 이름은 application.properties의 SNAKE_CASE 설정으로 receipt_no 같은 꼴이 된다.
 */
public final class Models {
    private Models() {
    }

    /** 로그인한 사용자. staff = 보관소 담당자 (STAFF_EMAILS에 있는 이메일). */
    public record User(String email, boolean staff) {
    }

    /**
     * found_items 한 행 + 건물 이름. 상태: waiting(입고 대기), stored(보관 중), returned(수령), transferred(경찰 인계).
     * secretFeature(확인용 특징)는 보관소 화면에서만 내보낸다. 공개 화면은 PublicView를 쓴다.
     */
    public record FoundItem(
            int id, Integer buildingId, String building, String placeDetail, OffsetDateTime foundAt,
            String status, String category, String color,
            String featBrand, String featAttached, String featDamage, String featContents,
            String secretFeature, OffsetDateTime reviewedAt,
            OffsetDateTime transferredAt, String transferOfficer, String transferNote,
            String registeredBy, OffsetDateTime createdAt, OffsetDateTime storedAt) {

        public static final String WAITING = "waiting";
        public static final String STORED = "stored";
        public static final String RETURNED = "returned";
        public static final String TRANSFERRED = "transferred";

        @JsonIgnore
        public boolean isStored() {
            return STORED.equals(status);
        }
    }

    /** lost_reports 한 행 + 고른 건물 이름들. 상태: active(진행 중), resolved(해결), cancelled(취소). */
    public record LostReport(
            int id, String userEmail, String category, String color,
            String featBrand, String featAttached, String featDamage, String featContents,
            List<Integer> buildingIds, List<String> buildings,
            OffsetDateTime lostFrom, OffsetDateTime lostTo, String status, OffsetDateTime createdAt) {

        @JsonIgnore
        public boolean isActive() {
            return "active".equals(status);
        }

        public LostReport withBuildings(List<String> names) {
            return new LostReport(id, userEmail, category, color, featBrand, featAttached, featDamage, featContents,
                    buildingIds, names, lostFrom, lostTo, status, createdAt);
        }
    }

    /**
     * R3 공개 목록 한 줄. 1단계는 종류·건물·습득일만, 2단계(R9 보완 후)는 색상·상세 장소·특징 네 칸까지.
     * 1단계에서는 나머지가 null이라 JSON에서 빠진다. 확인용 특징은 어느 단계에도 없다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PublicView(String receiptNo, int stage, String category, String building, String foundDate,
                             String office, String officeLocation, String color, String placeDetail,
                             String featBrand, String featAttached, String featDamage, String featContents) {
    }

    /** 분실자 화면: 등급, 보관 장소, 접수번호만 (R8). 점수 근거와 세부 특징은 넣지 않는다. */
    public record OwnerView(String receiptNo, String grade, String category, String office, String officeLocation) {
    }

    /** 수령 기록 (R6). handledBy = 처리한 담당자 이메일. */
    public record HandoverInfo(String receiverName, String receiverNo, String receiverPhone, String idType,
                               String handledBy, OffsetDateTime handedAt) {
    }

    /** AI 비교 결과 한 건 (보관소 화면 R6). */
    public record MatchInfo(Integer score, JsonNode fieldVerdicts, String reason) {
    }

    /** 보관소 화면용: 습득물 전체 + 계산 값 + (있으면) 수령 기록·매칭 결과. */
    public record OfficeItemView(@JsonUnwrapped FoundItem item, String receiptNo, long daysKept,
                                 boolean needsReview, boolean needsTransfer,
                                 @JsonUnwrapped HandoverInfo handover, @JsonUnwrapped MatchInfo match) {
    }

    /** 신고 한 건 + 신고번호 (내 신고 목록, 보관소 신고 조회). */
    public record ReportView(@JsonUnwrapped LostReport report, String reportNo) {
    }

    /** 매칭 응답. matches는 분실자 화면용, fallback은 AI를 못 쓸 때의 대체 목록(R5-2). */
    public record MatchResponse(String reportNo, String aiStatus, List<OwnerView> matches,
                                List<PublicView> fallback, String message) {
    }
}
