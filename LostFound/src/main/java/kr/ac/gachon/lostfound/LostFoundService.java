package kr.ac.gachon.lostfound;

import kr.ac.gachon.lostfound.GeminiClient.AIUnavailable;
import kr.ac.gachon.lostfound.GeminiClient.ScoredItem;
import kr.ac.gachon.lostfound.Models.FoundItem;
import kr.ac.gachon.lostfound.Models.HandoverInfo;
import kr.ac.gachon.lostfound.Models.LostReport;
import kr.ac.gachon.lostfound.Models.MatchInfo;
import kr.ac.gachon.lostfound.Models.MatchResponse;
import kr.ac.gachon.lostfound.Models.OfficeItemView;
import kr.ac.gachon.lostfound.Models.OwnerView;
import kr.ac.gachon.lostfound.Models.PublicView;
import kr.ac.gachon.lostfound.Models.ReportView;
import kr.ac.gachon.lostfound.Models.User;
import kr.ac.gachon.lostfound.Policy.Scored;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 요구사항 R1~R10의 처리 순서. 판단 규칙은 Policy, 저장은 Db, Gemini 호출은 GeminiClient에 둔다.
 * 메서드 하나가 트랜잭션 하나라서 중간에 거절되면 그 요청에서 바꾼 것은 모두 취소된다.
 */
@Service
public class LostFoundService {
    private static final Logger LOG = LoggerFactory.getLogger(LostFoundService.class);
    static final String NO_MATCH = "현재 일치하는 물건이 없습니다. 신고는 유지되니 나중에 다시 확인해 주세요.";

    private final Db db;
    private final GeminiClient ai;

    public LostFoundService(Db db, GeminiClient ai) {
        this.db = db;
        this.ai = ai;
    }

    // ---------- 공통 검사 ----------

    static void checkChoice(String value, Collection<String> allowed, String label) {
        if (!allowed.contains(value)) throw new ApiException(400, "목록에 없는 " + label + "입니다.");
    }

    void checkIds(String table, Collection<Integer> ids, String label) {
        List<Integer> list = ids.stream().filter(Objects::nonNull).toList();
        if (list.isEmpty()) return;
        if (db.countExisting(table, list) != new HashSet<>(list).size()) {
            throw new ApiException(400, "목록에 없는 " + label + "입니다.");
        }
    }

    static int itemNumber(String text) {
        Integer id = Policy.parseNo(text, 'F');
        if (id == null) throw new ApiException(404, "접수번호 형식이 맞지 않습니다.");
        return id;
    }

    static int reportNumber(String text) {
        Integer id = Policy.parseNo(text, 'L');
        if (id == null) throw new ApiException(404, "신고번호 형식이 맞지 않습니다.");
        return id;
    }

    private FoundItem existingItem(int id) {
        FoundItem item = db.item(id);
        if (item == null) throw new ApiException(404, "없는 접수번호입니다.");
        return item;
    }

    public Map<String, Object> options(String version) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("categories", Db.CATEGORIES);
        m.put("colors", Db.COLORS);
        m.put("buildings", db.buildings());
        m.put("office", Policy.OFFICE_NAME);
        m.put("office_location", Policy.OFFICE_LOCATION);
        m.put("version", version);
        m.put("ai", ai.enabled());
        return m;
    }

    // ---------- R3 공개 목록 ----------

    public List<PublicView> publicItems(String category) {
        return db.publicItems(category).stream().map(Policy::publicView).toList();
    }

    // ---------- R1 습득자 등록 ----------

    @Transactional
    public Map<String, Object> finderRegister(User user, Requests.FinderItem body) {
        checkIds("buildings", List.of(body.buildingId()), "건물");
        int id = db.insertFinderItem(body.buildingId(), body.placeDetail(), body.foundAt(), user.email());
        LOG.info("found item registered: {}", Policy.receiptNo(id));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("receipt_no", Policy.receiptNo(id));
        m.put("status", FoundItem.WAITING);
        m.put("office", Policy.OFFICE_NAME);
        m.put("office_location", Policy.OFFICE_LOCATION);
        return m;
    }

    // ---------- R4, R5 분실 신고와 매칭 ----------

    /** R5: 후보 필터 → Gemini 점수 → 70점 이상 최대 3건. 실패하면 R5-2 대체 목록. */
    MatchResponse runMatch(LostReport report) {
        String no = Policy.reportNo(report.id());
        List<FoundItem> candidates = db.candidatesFor(report, Policy.MAX_CANDIDATES);
        if (candidates.isEmpty()) {
            return new MatchResponse(no, "not_needed", List.of(), null, NO_MATCH);
        }
        Map<Integer, ScoredItem> scored;
        try {
            scored = ai.scoreCandidates(report, candidates);
        } catch (AIUnavailable e) {
            return new MatchResponse(no, "unavailable", List.of(),
                    candidates.stream().map(Policy::publicView).toList(),
                    "AI 비교를 할 수 없어 종류와 시간 조건이 맞는 물건만 보여드립니다.");
        }
        db.replaceMatches(report.id(), scored.values());
        List<Scored> pairs = new ArrayList<>();
        for (FoundItem c : candidates) {
            ScoredItem s = scored.get(c.id());
            pairs.add(new Scored(c, s == null ? 0 : s.score()));
        }
        List<OwnerView> matches = Policy.shownMatches(pairs).stream()
                .map(p -> Policy.ownerView(p.item(), p.score())).toList();
        return new MatchResponse(no, "available", matches, null, matches.isEmpty() ? NO_MATCH : null);
    }

    /**
     * 트랜잭션을 걸지 않는다: Gemini를 기다리는 동안(최대 수십 초) DB 연결을 붙잡지 않도록
     * DB 작업은 하나씩 짧게 끝내고, 여러 줄을 함께 바꾸는 매칭 결과 저장만 Db에서 트랜잭션으로 묶는다 (문제 9).
     */
    public MatchResponse createReport(User user, Requests.LostReportForm body) {
        checkChoice(body.category(), Db.CATEGORIES, "종류");
        List<String> colors = new ArrayList<>(Db.COLORS);
        colors.add("모름");
        checkChoice(body.color(), colors, "색상");
        checkIds("buildings", body.buildingIds(), "건물");
        if (db.activeReports(user.email()) >= Policy.MAX_ACTIVE_REPORTS) {
            throw new ApiException(409, "진행 중인 신고는 " + Policy.MAX_ACTIVE_REPORTS
                    + "건까지 등록할 수 있습니다. 기존 신고를 취소하거나 해결된 뒤 등록해 주세요.");
        }
        Requests.Features f = body.features();
        List<String> questions = Policy.specificityProblems(body.color(), f.asMap());
        if (questions.isEmpty()) {
            try {
                GeminiClient.SpecificityResult judged = ai.checkSpecificity(body.category(), body.color(), f.asMap());
                if (!judged.specific()) {
                    questions = judged.questions().isEmpty()
                            ? List.of("다른 물건과 구별할 수 있는 특징을 하나 더 적어 주세요.") : judged.questions();
                }
            } catch (AIUnavailable e) {
                // 규칙 검사만으로 통과
            }
        }
        if (!questions.isEmpty()) {
            throw new ApiException(422, "설명이 너무 일반적이라 등록할 수 없습니다. 아래 질문을 참고해 보완해 주세요.", questions);
        }
        int id = db.insertReport(user.email(), body.category(), body.color(), f.brand(), f.attached(),
                f.damage(), f.contents(), body.buildingIds(), body.lostFrom(), body.lostTo());
        MatchResponse result = runMatch(db.report(id));
        LOG.info("lost report saved: {} ai_status={} shown={}", result.reportNo(), result.aiStatus(), result.matches().size());
        return result;
    }

    /** 다시 찾기·취소는 신고한 본인만 (R8). */
    private LostReport ownedActiveReport(int reportId, User user) {
        LostReport report = db.report(reportId);
        if (report == null || !report.userEmail().equals(user.email())) {
            throw new ApiException(404, "내 신고 중에 이 신고번호가 없습니다.");
        }
        if (!report.isActive()) throw new ApiException(409, "이미 해결되었거나 취소된 신고입니다.");
        return report;
    }

    /** createReport와 같은 이유로 트랜잭션 없음. */
    public MatchResponse rematch(User user, String reportNo) {
        return runMatch(ownedActiveReport(reportNumber(reportNo), user));
    }

    @Transactional
    public Map<String, Object> cancelReport(User user, String reportNo) {
        LostReport report = ownedActiveReport(reportNumber(reportNo), user);
        db.setReportStatus(report.id(), "cancelled");
        return Map.of("report_no", Policy.reportNo(report.id()), "status", "cancelled");
    }

    public List<ReportView> myReports(User user) {
        return db.reportsOf(user.email()).stream().map(r -> new ReportView(r, Policy.reportNo(r.id()))).toList();
    }

    // ---------- 보관소 (담당자만) ----------

    static OfficeItemView fullItem(FoundItem item, HandoverInfo handover, MatchInfo match) {
        OffsetDateTime current = Policy.now();
        return new OfficeItemView(item, Policy.receiptNo(item.id()), Policy.daysKept(item, current),
                Policy.needsReview(item, current), Policy.needsTransfer(item, current), handover, match);
    }

    static OfficeItemView fullItem(FoundItem item) {
        return fullItem(item, null, null);
    }

    /** 보관소 목록. review는 R9 '보완 필요', transfer는 R10 '인계 대상' 물건 (오래된 순). */
    public List<OfficeItemView> officeItems(String status) {
        checkChoice(status, List.of("waiting", "stored", "review", "transfer", "returned", "transferred"), "상태");
        boolean review = status.equals("review");
        boolean transfer = status.equals("transfer");
        String dbStatus = review || transfer ? FoundItem.STORED : status;
        List<OfficeItemView> items = db.officeItems(dbStatus).stream()
                .map(r -> fullItem(r.item(), r.handover(), null)).toList();
        if (!review && !transfer) return items;
        return items.stream()
                .filter(i -> review ? i.needsReview() : i.needsTransfer())
                .sorted(Comparator.comparingLong(OfficeItemView::daysKept).reversed())
                .toList();
    }

    static void checkDetails(Requests.ItemDetails d) {
        checkChoice(d.category(), Db.CATEGORIES, "종류");
        checkChoice(d.color(), Db.COLORS, "색상");
    }

    /** R2: 입고 대기 물건에 세부 사항을 입력하면 보관 중이 된다. */
    @Transactional
    public OfficeItemView storeItem(String receiptNo, Requests.ItemDetails d) {
        checkDetails(d);
        int id = itemNumber(receiptNo);
        FoundItem item = existingItem(id);
        if (!FoundItem.WAITING.equals(item.status())) throw new ApiException(409, "이미 보관 중이거나 수령된 물건입니다.");
        db.storeItem(id, d.category(), d.color(), d.featBrand(), d.featAttached(), d.featDamage(),
                d.featContents(), d.secretFeature());
        return fullItem(db.item(id));
    }

    /** R2: 습득자 등록 없이 맡겨진 물건. 바로 보관 중. */
    @Transactional
    public OfficeItemView officeRegister(User staff, Requests.ItemDetails d) {
        checkDetails(d);
        List<Integer> building = new ArrayList<>();
        building.add(d.buildingId());
        checkIds("buildings", building, "건물");
        int id = db.insertOfficeItem(d.buildingId(), d.placeDetail(), d.foundAt(), d.category(), d.color(),
                d.featBrand(), d.featAttached(), d.featDamage(), d.featContents(), d.secretFeature(), staff.email());
        return fullItem(db.item(id));
    }

    /** R6 화면: 신고 내용, AI 점수와 칸별 판정, 습득물의 확인용 특징. */
    public Map<String, Object> officeReport(String reportNo) {
        LostReport report = db.report(reportNumber(reportNo));
        if (report == null) throw new ApiException(404, "없는 신고번호입니다.");
        List<OfficeItemView> matches = db.savedMatches(report.id()).stream()
                .map(m -> fullItem(m.item(), null, m.match())).toList();
        List<OfficeItemView> candidates = matches.isEmpty()
                ? db.candidatesFor(report, Policy.MAX_CANDIDATES).stream().map(LostFoundService::fullItem).toList()
                : List.of();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("report", new ReportView(report, Policy.reportNo(report.id())));
        m.put("matches", matches);
        m.put("candidates", candidates);
        return m;
    }

    /** R6: 본인 확인 후 수령 완료. 수령자 인적사항을 handovers에 남긴다. */
    @Transactional
    public Map<String, Object> returnItem(User staff, String receiptNo, Requests.Handover body) {
        int id = itemNumber(receiptNo);
        FoundItem item = existingItem(id);
        if (!item.isStored()) {
            throw new ApiException(409, "보관 중인 물건만 수령 처리할 수 있습니다. 이미 수령·경찰 인계되었거나 아직 입고 대기 상태입니다.");
        }
        Integer reportId = null;
        if (!body.reportNo().isEmpty()) {
            reportId = reportNumber(body.reportNo());
            LostReport report = db.report(reportId);
            if (report == null || !report.isActive()) throw new ApiException(409, "진행 중인 신고번호가 아닙니다.");
            db.setReportStatus(reportId, "resolved");
        }
        db.returnItem(id, reportId, body.receiverName(), body.receiverNo(), body.receiverPhone(), body.idType(),
                staff.email());
        LOG.info("item returned: {}", Policy.receiptNo(id));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("receipt_no", Policy.receiptNo(id));
        m.put("status", FoundItem.RETURNED);
        m.put("report_no", reportId == null ? null : Policy.reportNo(reportId));
        return m;
    }

    /** R9: 보관 7일이 지난 물건을 담당자가 다시 보고 특징 네 칸을 보완한다. 이후 색상·상세 장소·특징이 공개된다(2단계). */
    @Transactional
    public OfficeItemView reviewItem(String receiptNo, Requests.Review body) {
        int id = itemNumber(receiptNo);
        FoundItem item = existingItem(id);
        if (!item.isStored()) throw new ApiException(409, "보관 중인 물건만 보완할 수 있습니다.");
        if (Policy.daysKept(item, Policy.now()) < Policy.REVIEW_AFTER_DAYS) {
            throw new ApiException(409, "보관 " + Policy.REVIEW_AFTER_DAYS + "일 이후에 보완할 수 있습니다.");
        }
        List<String> revealing = Policy.revealingFeatures(body.features().asMap(), item.secretFeature());
        if (!revealing.isEmpty()) {
            throw new ApiException(422, "확인용 특징과 겹치는 내용은 공개할 수 없습니다. 다음 칸에서 빼 주세요: "
                    + String.join(", ", revealing));
        }
        db.reviewItem(id, body.featBrand(), body.featAttached(), body.featDamage(), body.featContents());
        LOG.info("item reviewed: {}", Policy.receiptNo(id));
        return fullItem(db.item(id));
    }


    /** R10: 보관 30일이 지난 물건을 관할 파출소로 인계한다. 이후 공개 목록에서 빠지고, 매칭되면 파출소를 안내한다. */
    @Transactional
    public OfficeItemView transferItem(String receiptNo, Requests.Transfer body) {
        int id = itemNumber(receiptNo);
        FoundItem item = existingItem(id);
        if (!item.isStored()) throw new ApiException(409, "보관 중인 물건만 인계할 수 있습니다.");
        if (Policy.daysKept(item, Policy.now()) < Policy.TRANSFER_AFTER_DAYS) {
            throw new ApiException(409, "보관 " + Policy.TRANSFER_AFTER_DAYS + "일 이후에 인계할 수 있습니다.");
        }
        db.transferItem(id, body.officerName(), body.note());
        LOG.info("item transferred to police: {}", Policy.receiptNo(id));
        return fullItem(db.item(id));
    }

    /** R10: 인계 대상(보관 30일 이상) 물건을 한 번에 인계한다. 받은 경찰관 이름은 모든 물건에 같이 기록. */
    @Transactional
    public Map<String, Object> transferAllDue(Requests.Transfer body) {
        List<String> done = new ArrayList<>();
        OffsetDateTime current = Policy.now();
        for (Db.ItemWithHandover row : db.officeItems(FoundItem.STORED)) {
            if (Policy.needsTransfer(row.item(), current)) {
                db.transferItem(row.item().id(), body.officerName(), body.note());
                done.add(Policy.receiptNo(row.item().id()));
            }
        }
        if (done.isEmpty()) throw new ApiException(409, "인계할 물건(보관 " + Policy.TRANSFER_AFTER_DAYS + "일 이상)이 없습니다.");
        LOG.info("items transferred to police: {}", done.size());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", done.size());
        m.put("receipt_nos", done);
        return m;
    }
}
