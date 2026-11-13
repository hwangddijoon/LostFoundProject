package kr.ac.gachon.lostfound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * R11 인증번호 메일 보내기.
 * - MAIL_HOST가 있으면 SMTP로 보낸다 (예: Gmail smtp.gmail.com:587 + 앱 비밀번호)
 * - MAIL_HOST가 비어 있으면 메일을 보내지 않고 서버 로그에 인증번호를 찍는다 (개발·시연용)
 */
@Component
public class Mailer {
    private static final Logger LOG = LoggerFactory.getLogger(Mailer.class);

    private final String host;
    private final String from;
    private final ObjectProvider<JavaMailSender> sender;

    public Mailer(@Value("${MAIL_HOST:}") String host,
                  @Value("${MAIL_FROM:}") String from,
                  @Value("${MAIL_USERNAME:}") String username,
                  ObjectProvider<JavaMailSender> sender) {
        this.host = host.strip();
        this.from = senderAddress(from, username);
        this.sender = sender;
    }

    /**
     * 보내는 주소: MAIL_FROM, 비어 있으면 MAIL_USERNAME.
     * compose.yaml은 비워 둔 변수도 빈 문자열("")로 넘기므로 ${MAIL_FROM:기본값}으로는 기본값이 쓰이지 않는다 (P8).
     */
    static String senderAddress(String from, String username) {
        String f = from == null ? "" : from.strip();
        return f.isEmpty() ? (username == null ? "" : username.strip()) : f;
    }

    public boolean logOnly() {
        return host.isEmpty();
    }

    /** 실패하면 ApiException(503). 메일 내용과 받는 주소는 로그에 남기지 않는다 (로그 모드 제외). */
    public void sendCode(String email, String code) {
        if (logOnly()) {
            LOG.warn("[메일 보내기 생략: MAIL_HOST 없음] {} 인증번호 {} ({}분 유효)", email, code, Policy.CODE_VALID_MINUTES);
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        if (!from.isEmpty()) message.setFrom(from); // 빈 주소를 넣으면 MailParseException (P8)
        message.setTo(email);
        message.setSubject("[찾아줘] 이메일 인증번호");
        message.setText("찾아줘 인증번호: " + code + "\n\n" + Policy.CODE_VALID_MINUTES
                + "분 안에 입력해 주세요. 직접 요청하지 않았다면 이 메일을 무시하세요.");
        try {
            JavaMailSender mail = sender.getIfAvailable();
            if (mail == null) throw new IllegalStateException("JavaMailSender not configured");
            mail.send(message);
            LOG.info("verification mail sent");
        } catch (MailException | IllegalStateException e) {
            // 원인을 알 수 있게 예외 종류와 가장 안쪽 원인의 메시지를 남긴다 (비밀번호는 메시지에 들어가지 않음)
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            LOG.error("verification mail failed: {} / cause: {}: {} (host={}, from={})", e.getClass().getSimpleName(),
                    root.getClass().getSimpleName(), root.getMessage(), host, from.isEmpty() ? "(empty)" : from);
            throw new ApiException(503, "인증 메일을 보내지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }
}
