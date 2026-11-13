package kr.ac.gachon.lostfound;

import java.util.List;

/**
 * 사용자에게 보여줄 문장이 있는 거절. ApiController가 {"detail": {"message": ..., "questions": [...]}} 로 바꾼다.
 * 상태 코드: 400 목록에 없는 값, 401 로그인 필요·실패, 403 담당자 전용, 404 없는 번호,
 * 409 지금 상태에서 할 수 없음, 422 입력 형식·내용, 429 너무 잦은 요청, 503 메일·DB 문제.
 */
public class ApiException extends RuntimeException {
    private final int status;
    private final transient List<String> questions;

    public ApiException(int status, String message) {
        this(status, message, List.of());
    }

    public ApiException(int status, String message, List<String> questions) {
        super(message);
        this.status = status;
        this.questions = questions;
    }

    public int status() {
        return status;
    }

    public List<String> questions() {
        return questions;
    }

    public static ApiException invalid(String message) {
        return new ApiException(422, message);
    }
}
