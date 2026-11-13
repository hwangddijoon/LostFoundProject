package kr.ac.gachon.lostfound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * 찾아줘 — 가천대학교 캠퍼스 분실물 AI 매칭 서비스 (Spring Boot). 프로그램 시작점.
 * 요청 흐름: ApiController → AuthService / LostFoundService → Policy · Db · GeminiClient · Mailer
 */
@SpringBootApplication
public class LostFoundApplication {
    private static final Logger LOG = LoggerFactory.getLogger(LostFoundApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(LostFoundApplication.class, args);
    }

    /** 환경 변수 ENABLE_AI, GEMINI_API_KEY, GEMINI_MODEL, GEMINI_BASE_URL로 Gemini 설정을 만든다. */
    @Bean
    public GeminiClient geminiClient(@Value("${ENABLE_AI:0}") String enable,
                                     @Value("${GEMINI_API_KEY:}") String key,
                                     @Value("${GEMINI_BASE_URL:}") String baseUrl,
                                     @Value("${GEMINI_MODEL:}") String model) {
        return new GeminiClient(new GeminiClient.Settings("1".equals(enable.strip()), key, baseUrl, model));
    }

    /** 시작할 때 테이블과 시드 데이터를 준비한다. DB 구조가 예전 버전이면 여기서 멈춘다 (P4). */
    @Bean
    public ApplicationRunner startup(Db db, GeminiClient ai, Mailer mailer) {
        return args -> {
            db.initialize();
            String problem = ai.keyProblem();
            if (problem != null) LOG.warn(problem);
            if (mailer.logOnly()) LOG.warn("MAIL_HOST 가 비어 있어 인증 메일을 보내지 않고 이 로그에 인증번호를 찍습니다 (시연용).");
        };
    }
}
