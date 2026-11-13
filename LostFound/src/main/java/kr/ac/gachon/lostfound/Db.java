package kr.ac.gachon.lostfound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.ac.gachon.lostfound.Models.FoundItem;
import kr.ac.gachon.lostfound.Models.HandoverInfo;
import kr.ac.gachon.lostfound.Models.LostReport;
import kr.ac.gachon.lostfound.Models.MatchInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * DB에 관한 것 전부: 테이블 생성, 시드 데이터(seed.json), 조회·저장 SQL.
 * R7: 데이터는 PostgreSQL(Docker volume)에 남는다.
 */
@Repository
public class Db {
    private static final Logger LOG = LoggerFactory.getLogger(Db.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    // ---------- seed.json: 선택 목록(종류·색상·건물)과 시연용 습득물 50건 ----------

    static final JsonNode SEED = loadSeed();
    public static final List<String> CATEGORIES = strings("categories");
    public static final List<String> COLORS = strings("colors");
    public static final List<String> BUILDINGS = strings("buildings");

    private static JsonNode loadSeed() {
        try (InputStream in = Db.class.getResourceAsStream("/seed.json")) {
            if (in == null) throw new IllegalStateException("seed.json not found");
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> strings(String key) {
        List<String> out = new ArrayList<>();
        SEED.path(key).forEach(n -> out.add(n.asText()));
        return List.copyOf(out);
    }

    // ---------- 테이블 ----------

    static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS buildings (
                id integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                name text NOT NULL UNIQUE);
            CREATE TABLE IF NOT EXISTS found_items (
                id integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                building_id integer REFERENCES buildings(id),
                place_detail text NOT NULL DEFAULT '',
                found_at timestamptz,
                status text NOT NULL DEFAULT 'waiting' CHECK (status IN ('waiting','stored','returned','transferred')),
                category text, color text,
                feat_brand text NOT NULL DEFAULT '', feat_attached text NOT NULL DEFAULT '',
                feat_damage text NOT NULL DEFAULT '', feat_contents text NOT NULL DEFAULT '',
                secret_feature text,
                reviewed_at timestamptz,
                transferred_at timestamptz, transfer_officer text, transfer_note text,
                registered_by text,
                created_at timestamptz NOT NULL DEFAULT now(),
                stored_at timestamptz,
                CHECK (status = 'waiting' OR (category IS NOT NULL AND color IS NOT NULL AND secret_feature IS NOT NULL)));
            CREATE TABLE IF NOT EXISTS users (
                email text PRIMARY KEY,
                password_hash text NOT NULL,
                created_at timestamptz NOT NULL DEFAULT now());
            CREATE TABLE IF NOT EXISTS email_codes (
                email text PRIMARY KEY,
                code_hash text NOT NULL,
                sent_at timestamptz NOT NULL,
                expires_at timestamptz NOT NULL,
                attempts integer NOT NULL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS sessions (
                token_hash text PRIMARY KEY,
                email text NOT NULL REFERENCES users(email),
                expires_at timestamptz NOT NULL);
            CREATE TABLE IF NOT EXISTS lost_reports (
                id integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                user_email text NOT NULL REFERENCES users(email),
                category text NOT NULL, color text NOT NULL,
                feat_brand text NOT NULL DEFAULT '', feat_attached text NOT NULL DEFAULT '',
                feat_damage text NOT NULL DEFAULT '', feat_contents text NOT NULL DEFAULT '',
                building_ids integer[] NOT NULL DEFAULT '{}',
                lost_from timestamptz NOT NULL, lost_to timestamptz NOT NULL,
                status text NOT NULL DEFAULT 'active' CHECK (status IN ('active','resolved','cancelled')),
                created_at timestamptz NOT NULL DEFAULT now());
            CREATE INDEX IF NOT EXISTS lost_reports_user ON lost_reports(user_email, status);
            CREATE TABLE IF NOT EXISTS match_results (
                report_id integer NOT NULL REFERENCES lost_reports(id),
                item_id integer NOT NULL REFERENCES found_items(id),
                score integer NOT NULL,
                field_verdicts jsonb NOT NULL,
                reason text NOT NULL,
                created_at timestamptz NOT NULL DEFAULT now(),
                PRIMARY KEY (report_id, item_id));
            CREATE TABLE IF NOT EXISTS handovers (
                id integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                item_id integer NOT NULL UNIQUE REFERENCES found_items(id),
                report_id integer REFERENCES lost_reports(id),
                receiver_name text NOT NULL, receiver_no text NOT NULL,
                receiver_phone text NOT NULL, id_type text NOT NULL,
                handled_by text NOT NULL,
                handed_at timestamptz NOT NULL DEFAULT now());
            CREATE TABLE IF NOT EXISTS seed_history (name text PRIMARY KEY);
            """;

    /** DB 구조가 바뀌면 올린다. 예전 구조의 volume이 남아 있으면 시작 단계에서 멈추고 안내한다 (P4). */
    public static final String SCHEMA_VERSION = "6"; // v5: 로그인 테이블, v6: public_note 삭제 (특징 네 칸 공개로 대체)
    static final String OLD_SCHEMA_MESSAGE = "DB 구조가 이전 버전입니다 (필요: v%s, 현재: %s). "
            + "기존 데이터를 지우고 다시 시작하려면 'docker compose down -v' 후 'docker compose up'을 실행하세요.";

    /** 습득물 조회에 건물 이름을 붙이는 공통 SELECT. */
    static final String ITEM_SELECT = "SELECT f.*, b.name AS building FROM found_items f "
            + "LEFT JOIN buildings b ON b.id = f.building_id ";

    private final JdbcTemplate jdbc;

    public Db(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 시작 ----------

    @Transactional
    public void initialize() {
        jdbc.execute("SELECT pg_advisory_xact_lock(42010)"); // 앱을 여러 개 띄워도 시드는 한 번만
        checkSchemaVersion();
        jdbc.execute(SCHEMA);
        jdbc.update("INSERT INTO app_meta VALUES ('schema', ?) ON CONFLICT (key) DO NOTHING", SCHEMA_VERSION);
        if (!jdbc.queryForList("SELECT 1 FROM seed_history WHERE name='initial'").isEmpty()) {
            return;
        }
        seed();
        jdbc.update("INSERT INTO seed_history VALUES ('initial')");
        LOG.info("seed inserted: {} buildings, {} items", BUILDINGS.size(), SEED.path("items").size());
    }

    /** found_items가 이미 있는데 버전 기록이 없거나 다르면 예전 구조로 본다. */
    private void checkSchemaVersion() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS app_meta (key text PRIMARY KEY, value text NOT NULL)");
        List<String> rows = jdbc.queryForList("SELECT value FROM app_meta WHERE key='schema'", String.class);
        Boolean hasItems = jdbc.queryForObject("SELECT to_regclass('found_items') IS NOT NULL", Boolean.class);
        String current = !rows.isEmpty() ? rows.get(0) : (Boolean.TRUE.equals(hasItems) ? "v1 이하" : null);
        if ("5".equals(current)) {
            // v5 → v6: 데이터를 지우지 않고 공개 설명 칸만 없앤다 (보완한 물건은 특징 네 칸이 공개됨)
            jdbc.execute("ALTER TABLE found_items DROP COLUMN IF EXISTS public_note");
            jdbc.update("UPDATE app_meta SET value=? WHERE key='schema'", SCHEMA_VERSION);
            LOG.info("DB 구조를 v5에서 v{}로 바꿨습니다 (public_note 삭제)", SCHEMA_VERSION);
            return;
        }
        if (current != null && !current.equals(SCHEMA_VERSION)) {
            throw new IllegalStateException(String.format(OLD_SCHEMA_MESSAGE, SCHEMA_VERSION, current));
        }
    }

    private void seed() {
        for (String name : BUILDINGS) {
            jdbc.update("INSERT INTO buildings(name) VALUES (?)", name);
        }
        Map<String, Integer> bid = jdbc.query("SELECT id, name FROM buildings", rs -> {
            Map<String, Integer> m = new java.util.HashMap<>();
            while (rs.next()) m.put(rs.getString("name"), rs.getInt("id"));
            return m;
        });
        OffsetDateTime current = Policy.now();
        for (JsonNode it : SEED.path("items")) {
            OffsetDateTime foundAt = current.minusMinutes(Math.round(it.path("hours_ago").asDouble() * 60));
            Integer building = bid.get(it.path("building").asText());
            if ("waiting".equals(it.path("status").asText())) {
                jdbc.update("INSERT INTO found_items(building_id, place_detail, found_at, created_at) VALUES (?,?,?,?)",
                        building, it.path("place_detail").asText(""), foundAt, foundAt);
                continue;
            }
            // reviewed_hours_after가 있는 물건은 담당자가 보완을 마친 상태(공개 2단계)로 넣는다
            OffsetDateTime reviewedAt = !it.has("reviewed_hours_after") ? null
                    : foundAt.plusMinutes(Math.round(it.path("reviewed_hours_after").asDouble() * 60));
            jdbc.update("""
                    INSERT INTO found_items(building_id, place_detail, found_at, status, category, color,
                        feat_brand, feat_attached, feat_damage, feat_contents, secret_feature,
                        reviewed_at, created_at, stored_at)
                    VALUES (?,?,?,'stored',?,?,?,?,?,?,?,?,?,?)""",
                    building, it.path("place_detail").asText(""), foundAt, it.path("category").asText(),
                    it.path("color").asText(), it.path("feat_brand").asText(""), it.path("feat_attached").asText(""),
                    it.path("feat_damage").asText(""), it.path("feat_contents").asText(""),
                    it.path("secret_feature").asText(), reviewedAt, foundAt, foundAt);
        }
    }

    // ---------- 행 → 객체 ----------

    private static OffsetDateTime time(ResultSet rs, String col) throws SQLException {
        return rs.getObject(col, OffsetDateTime.class);
    }

    private static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    static final RowMapper<FoundItem> ITEM = (rs, n) -> new FoundItem(
            rs.getInt("id"), intOrNull(rs, "building_id"), rs.getString("building"), rs.getString("place_detail"),
            time(rs, "found_at"), rs.getString("status"), rs.getString("category"), rs.getString("color"),
            rs.getString("feat_brand"), rs.getString("feat_attached"), rs.getString("feat_damage"),
            rs.getString("feat_contents"), rs.getString("secret_feature"),
            time(rs, "reviewed_at"), time(rs, "transferred_at"), rs.getString("transfer_officer"),
            rs.getString("transfer_note"), rs.getString("registered_by"), time(rs, "created_at"), time(rs, "stored_at"));

    static final RowMapper<LostReport> REPORT = (rs, n) -> {
        Array arr = rs.getArray("building_ids");
        List<Integer> ids = arr == null ? List.of() : Arrays.asList((Integer[]) arr.getArray());
        return new LostReport(rs.getInt("id"), rs.getString("user_email"), rs.getString("category"),
                rs.getString("color"), rs.getString("feat_brand"), rs.getString("feat_attached"),
                rs.getString("feat_damage"), rs.getString("feat_contents"), ids, List.of(),
                time(rs, "lost_from"), time(rs, "lost_to"), rs.getString("status"), time(rs, "created_at"));
    };

    static HandoverInfo handover(ResultSet rs) throws SQLException {
        String name = rs.getString("receiver_name");
        if (name == null) return null;
        return new HandoverInfo(name, rs.getString("receiver_no"), rs.getString("receiver_phone"),
                rs.getString("id_type"), rs.getString("handled_by"), time(rs, "handed_at"));
    }

    static MatchInfo match(ResultSet rs) throws SQLException {
        try {
            return new MatchInfo(rs.getInt("score"), JSON.readTree(rs.getString("field_verdicts")), rs.getString("reason"));
        } catch (IOException e) {
            throw new SQLException("bad field_verdicts", e);
        }
    }

    /** '{1,2}' 꼴 배열 글자. integer[] 칸에 ?::int[] 로 넣는다 (드라이버의 배열 처리에 기대지 않으려고). */
    static String pgArray(Collection<Integer> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
    }

    // ---------- 조회 ----------

    public List<Map<String, Object>> buildings() {
        return jdbc.queryForList("SELECT id, name FROM buildings ORDER BY id");
    }

    public int countExisting(String table, Collection<Integer> ids) {
        // table은 코드 안의 고정 값만 넘긴다 (사용자 입력 아님)
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ANY(?::int[])",
                Integer.class, pgArray(ids));
        return n == null ? 0 : n;
    }

    public FoundItem item(int id) {
        List<FoundItem> rows = jdbc.query(ITEM_SELECT + "WHERE f.id=?", ITEM, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<FoundItem> publicItems(String category) {
        if (category == null || category.isEmpty()) {
            return jdbc.query(ITEM_SELECT + "WHERE f.status='stored' ORDER BY f.found_at DESC NULLS LAST, f.id DESC LIMIT 300", ITEM);
        }
        return jdbc.query(ITEM_SELECT + "WHERE f.status='stored' AND f.category=? "
                + "ORDER BY f.found_at DESC NULLS LAST, f.id DESC LIMIT 300", ITEM, category);
    }

    public record ItemWithHandover(FoundItem item, HandoverInfo handover) {
    }

    public List<ItemWithHandover> officeItems(String status) {
        return jdbc.query("SELECT x.*, h.receiver_name, h.receiver_no, h.receiver_phone, h.id_type, h.handled_by, h.handed_at FROM ("
                        + ITEM_SELECT + "WHERE f.status=?) x LEFT JOIN handovers h ON h.item_id = x.id "
                        + "ORDER BY x.created_at DESC, x.id DESC LIMIT 300",
                (rs, n) -> new ItemWithHandover(ITEM.mapRow(rs, n), handover(rs)), status);
    }

    public LostReport report(int id) {
        List<LostReport> rows = jdbc.query("SELECT * FROM lost_reports WHERE id=?", REPORT, id);
        if (rows.isEmpty()) return null;
        return withBuildingNames(rows.get(0));
    }

    private LostReport withBuildingNames(LostReport r) {
        List<String> names = jdbc.queryForList("SELECT name FROM buildings WHERE id = ANY(?::int[]) ORDER BY id",
                String.class, pgArray(r.buildingIds()));
        return r.withBuildings(names);
    }

    public int activeReports(String email) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM lost_reports WHERE user_email=? AND status='active'",
                Integer.class, email);
        return n == null ? 0 : n;
    }

    /** 내 신고 목록 (최근 것부터 30건). */
    public List<LostReport> reportsOf(String email) {
        return jdbc.query("SELECT * FROM lost_reports WHERE user_email=? ORDER BY id DESC LIMIT 30", REPORT, email)
                .stream().map(this::withBuildingNames).toList();
    }

    /**
     * R5 1단계: 같은 종류, 보관 중 또는 경찰 인계(R10), 분실 시작 이후 습득 (습득 시각 모름은 포함).
     * 장소로는 거르지 않는다. 인계한 물건도 넣어야 주인에게 파출소를 안내할 수 있다.
     */
    public List<FoundItem> candidatesFor(LostReport report, int limit) {
        return jdbc.query(ITEM_SELECT + "WHERE f.status IN ('stored','transferred') AND f.category=? "
                        + "AND (f.found_at IS NULL OR f.found_at >= ?) "
                        + "ORDER BY f.found_at DESC NULLS LAST, f.id DESC LIMIT ?",
                ITEM, report.category(), report.lostFrom(), limit);
    }

    public record MatchRow(FoundItem item, MatchInfo match) {
    }

    public List<MatchRow> savedMatches(int reportId) {
        return jdbc.query("SELECT m.score, m.field_verdicts::text AS field_verdicts, m.reason, x.* FROM match_results m "
                        + "JOIN (" + ITEM_SELECT + ") x ON x.id = m.item_id WHERE m.report_id=? ORDER BY m.score DESC LIMIT 10",
                (rs, n) -> new MatchRow(ITEM.mapRow(rs, n), match(rs)), reportId);
    }

    // ---------- 쓰기 ----------

    public int insertFinderItem(int buildingId, String placeDetail, OffsetDateTime foundAt, String registeredBy) {
        return jdbc.queryForObject("INSERT INTO found_items(building_id, place_detail, found_at, registered_by) "
                + "VALUES (?,?,?,?) RETURNING id", Integer.class, buildingId, placeDetail, foundAt, registeredBy);
    }

    public int insertOfficeItem(Integer buildingId, String placeDetail, OffsetDateTime foundAt, String category,
                                String color, String brand, String attached, String damage, String contents,
                                String secret, String registeredBy) {
        return jdbc.queryForObject("""
                        INSERT INTO found_items(building_id, place_detail, found_at, status, category, color,
                            feat_brand, feat_attached, feat_damage, feat_contents, secret_feature, registered_by, stored_at)
                        VALUES (?,?,?,'stored',?,?,?,?,?,?,?,?,now()) RETURNING id""",
                Integer.class, buildingId, placeDetail, foundAt, category, color, brand, attached, damage, contents,
                secret, registeredBy);
    }

    public void storeItem(int id, String category, String color, String brand, String attached, String damage,
                          String contents, String secret) {
        jdbc.update("""
                        UPDATE found_items SET status='stored', stored_at=now(), category=?, color=?,
                            feat_brand=?, feat_attached=?, feat_damage=?, feat_contents=?, secret_feature=?
                        WHERE id=?""",
                category, color, brand, attached, damage, contents, secret, id);
    }

    public void reviewItem(int id, String brand, String attached, String damage, String contents) {
        jdbc.update("""
                        UPDATE found_items SET reviewed_at=now(),
                            feat_brand=?, feat_attached=?, feat_damage=?, feat_contents=? WHERE id=?""",
                brand, attached, damage, contents, id);
    }

    public void transferItem(int id, String officer, String note) {
        jdbc.update("UPDATE found_items SET status='transferred', transferred_at=now(), transfer_officer=?, "
                + "transfer_note=? WHERE id=?", officer, note, id);
    }

    public int insertReport(String email, String category, String color, String brand, String attached,
                            String damage, String contents, Collection<Integer> buildingIds,
                            OffsetDateTime lostFrom, OffsetDateTime lostTo) {
        List<Integer> ids = new ArrayList<>(new java.util.TreeSet<>(buildingIds));
        return jdbc.queryForObject("""
                        INSERT INTO lost_reports(user_email, category, color, feat_brand, feat_attached, feat_damage,
                            feat_contents, building_ids, lost_from, lost_to)
                        VALUES (?,?,?,?,?,?,?,?::int[],?,?) RETURNING id""",
                Integer.class, email, category, color, brand, attached, damage, contents, pgArray(ids),
                lostFrom, lostTo);
    }

    public void setReportStatus(int id, String status) {
        jdbc.update("UPDATE lost_reports SET status=? WHERE id=?", status, id);
    }

    @Transactional // 지우기와 넣기를 함께 (중간에 실패하면 이전 결과 유지)
    public void replaceMatches(int reportId, Collection<GeminiClient.ScoredItem> scored) {
        jdbc.update("DELETE FROM match_results WHERE report_id=?", reportId);
        for (var s : scored) {
            String verdicts;
            try {
                verdicts = JSON.writeValueAsString(s.fields());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            jdbc.update("INSERT INTO match_results(report_id, item_id, score, field_verdicts, reason) "
                    + "VALUES (?,?,?,?::jsonb,?)", reportId, s.itemId(), s.score(), verdicts, s.reason());
        }
    }

    public void returnItem(int itemId, Integer reportId, String name, String no, String phone, String idType,
                           String staffEmail) {
        jdbc.update("UPDATE found_items SET status='returned' WHERE id=?", itemId);
        jdbc.update("INSERT INTO handovers(item_id, report_id, receiver_name, receiver_no, receiver_phone, id_type, "
                + "handled_by) VALUES (?,?,?,?,?,?,?)", itemId, reportId, name, no, phone, idType, staffEmail);
    }

    // ---------- R11 로그인 ----------

    public record EmailCode(String codeHash, OffsetDateTime sentAt, OffsetDateTime expiresAt, int attempts) {
    }

    public EmailCode emailCode(String email) {
        List<EmailCode> rows = jdbc.query("SELECT * FROM email_codes WHERE email=?", (rs, n) -> new EmailCode(
                rs.getString("code_hash"), time(rs, "sent_at"), time(rs, "expires_at"), rs.getInt("attempts")), email);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 새 인증번호로 바꾼다 (이전 번호와 시도 횟수는 사라짐). */
    public void saveEmailCode(String email, String codeHash, OffsetDateTime sentAt, OffsetDateTime expiresAt) {
        jdbc.update("""
                INSERT INTO email_codes(email, code_hash, sent_at, expires_at, attempts) VALUES (?,?,?,?,0)
                ON CONFLICT (email) DO UPDATE SET code_hash=EXCLUDED.code_hash, sent_at=EXCLUDED.sent_at,
                    expires_at=EXCLUDED.expires_at, attempts=0""", email, codeHash, sentAt, expiresAt);
    }

    public void countCodeAttempt(String email) {
        jdbc.update("UPDATE email_codes SET attempts = attempts + 1 WHERE email=?", email);
    }

    public void deleteEmailCode(String email) {
        jdbc.update("DELETE FROM email_codes WHERE email=?", email);
    }

    public String passwordHash(String email) {
        List<String> rows = jdbc.queryForList("SELECT password_hash FROM users WHERE email=?", String.class, email);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 처음이면 가입, 이미 있으면 비밀번호 재설정. */
    public void saveUser(String email, String passwordHash) {
        jdbc.update("INSERT INTO users(email, password_hash) VALUES (?,?) "
                + "ON CONFLICT (email) DO UPDATE SET password_hash=EXCLUDED.password_hash", email, passwordHash);
    }

    public void saveSession(String tokenHash, String email, OffsetDateTime expiresAt) {
        jdbc.update("DELETE FROM sessions WHERE expires_at < now()");
        jdbc.update("INSERT INTO sessions(token_hash, email, expires_at) VALUES (?,?,?)", tokenHash, email, expiresAt);
    }

    /** 만료되지 않은 세션의 이메일. 없으면 null. */
    public String sessionEmail(String tokenHash) {
        List<String> rows = jdbc.queryForList("SELECT email FROM sessions WHERE token_hash=? AND expires_at > now()",
                String.class, tokenHash);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void deleteSession(String tokenHash) {
        jdbc.update("DELETE FROM sessions WHERE token_hash=?", tokenHash);
    }

    /** 비밀번호를 바꾸면 그 계정의 다른 로그인은 모두 끊는다. */
    public void deleteSessionsOf(String email) {
        jdbc.update("DELETE FROM sessions WHERE email=?", email);
    }

    public void ping() {
        jdbc.queryForObject("SELECT 1", Integer.class);
    }
}
