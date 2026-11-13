# 찾아줘 — 가천대학교 캠퍼스 분실물 AI 매칭

가천대학교 캠퍼스 분실물 서비스. 학교 이메일(@gachon.ac.kr)로 인증하고 로그인해서 쓴다. 습득자는 주운 장소·시간만 등록하고 보관소에 맡긴다. 담당자가 실물을 보고 특징을 입력하면, 분실자가 쓴 설명과 Gemini가 비교해 일치 가능성이 높은 물건을 알려준다.

- 구성: Spring Boot 3.5 (Java 21) 앱 + PostgreSQL, Docker Compose로 실행
- PC에 Java·Maven이 없어도 된다. Docker 이미지를 만들 때 컨테이너 안에서 Maven이 빌드하고 단위 테스트를 돌린다 (테스트가 실패하면 이미지가 만들어지지 않음)
- 자세한 요구사항·설계·시험 기록: 개발 문서(PDF)

## 1. 내 컴퓨터에서 실행

준비물: Docker Desktop (Windows·macOS) 또는 Docker Engine + Compose (Linux)

```bash
cp .env.example .env            # Windows PowerShell: copy .env.example .env
# .env 를 열어 STAFF_EMAILS(담당자 이메일)와 GEMINI_API_KEY 를 채운다 
docker compose up --build       # 처음에는 Maven이 라이브러리를 내려받아 몇 분 걸린다
```

- 접속: http://127.0.0.1:8080 → "회원가입"에서 학교 이메일로 인증번호를 받아 가입
- 메일 설정(아래 2번)을 안 했으면 인증번호는 메일 대신 앱 로그에 찍힌다: `docker compose logs app | findstr 인증번호` (macOS·Linux: `grep 인증번호`)
- 종료: `Ctrl+C` 후 `docker compose down` (데이터는 남음)
- 처음부터 다시: `docker compose down -v` 후 `docker compose up` (시드 데이터부터 다시 시작)

## 2. 인증 메일 보내기 (선택)

`MAIL_HOST`를 채우면 SMTP 서버로 실제 메일을 보낸다. 가장 쉬운 방법은 Gmail 계정 하나를 보내는 용도로 쓰는 것이다.

1. 보내는 데 쓸 Google 계정에서 2단계 인증을 켠다.
2. Google 계정 → 보안 → 앱 비밀번호에서 16자리 앱 비밀번호를 만든다.
3. `.env`에 넣는다:
   ```
   MAIL_HOST=smtp.gmail.com
   MAIL_PORT=587
   MAIL_USERNAME=보내는주소@gmail.com
   MAIL_PASSWORD=앱비밀번호16자리
   ```
4. `docker compose up -d`로 다시 시작. `/health`의 `"mail"`이 `"smtp"`면 적용된 것.

참고: 학교 메일 서버가 처음 보는 Gmail 주소를 스팸함으로 보낼 수 있다. 메일이 안 보이면 스팸함을 확인한다. 앱 비밀번호는 `.env`에만 두고 저장소에 올리지 않는다.

## 3. 보관소 담당자 계정

`.env`의 `STAFF_EMAILS`에 담당자 이메일을 적는다 (예: `STAFF_EMAILS=hwangtaejoon@gachon.ac.kr, hwangtaejoon@gmail.com`). 그 이메일로 가입·로그인하면 메뉴에 "보관소 관리"가 보인다. 나머지 계정은 보관소 기능을 쓸 수 없다 (403).

학생 계정은 `@gachon.ac.kr` 이메일만 되지만, `STAFF_EMAILS`에 적은 주소는 Gmail처럼 다른 도메인이어도 가입·로그인할 수 있다 (예외는 이 목록뿐). 목록에서 빼면 그 외부 이메일 계정은 더 이상 로그인할 수 없다.

## 4. AWS EC2에서 실행

1. **인스턴스 만들기**: Ubuntu 24.04, `t3.small` 이상 (메모리 2GB. Maven 빌드에 메모리가 필요하다). 키 페어(.pem)를 내려받는다.
2. **보안 그룹 인바운드 규칙**: SSH 22번(내 IP), 사용자 정의 TCP 8080번(0.0.0.0/0 또는 접속할 IP). DB 포트(5432)는 열지 않는다.
3. **접속**: `ssh -i 키파일.pem ubuntu@<퍼블릭 IP>`
4. **Docker 설치**:
   ```bash
   sudo apt update && sudo apt install -y docker.io docker-compose-v2 unzip git
   sudo usermod -aG docker ubuntu && exit     # 다시 ssh 접속해야 그룹이 적용됨
   ```
5. **코드 올리기**: `git clone <저장소 주소>` 또는 내 PC에서 lostfound 폴더를 압축한 뒤 `scp -i <키파일>.pem lostfound.zip ubuntu@<퍼블릭 IP>:~` → EC2에서 `unzip lostfound.zip`
6. **설정**: `cd lostfound && cp .env.example .env && nano .env` → `APP_BIND=0.0.0.0`, `STAFF_EMAILS`, `GEMINI_API_KEY`, (메일) `MAIL_*`
7. **실행**: `docker compose up -d --build` → `docker compose ps`로 app·db가 running인지 확인 → `curl http://127.0.0.1:8080/health`
8. **접속**: 아무 컴퓨터의 브라우저에서 `http://<퍼블릭 IP>:8080`

참고
- 인스턴스를 멈췄다 켜면 퍼블릭 IP가 바뀐다 (학교 AWS 실습 계정은 세션이 끝나면 멈춘다). 연결할 때마다 IP를 다시 확인한다. 데이터는 Docker volume에 남는다.
- `http`라서 로그인 비밀번호가 암호화되지 않고 전송된다. 실제 서비스라면 HTTPS(도메인 + 인증서)를 붙이고 `COOKIE_SECURE=1`로 바꾼다.
- 로그 보기: `docker compose logs -f app` (Gemini 호출 결과는 `provider=gemini ...` 한 줄로 남는다)

## 5. Docker Hub로 이미지 주고받기 (EC2에서 빌드하지 않는 방법)

내 PC에서 이미지를 만들어 Docker Hub에 올리고(push), EC2는 받아서(pull) 실행만 한다. EC2에서 Maven 빌드를 하지 않으니 빠르고 메모리도 적게 든다. EC2에는 소스 코드 없이 `compose.yaml`과 `.env`만 있으면 된다.

**내 PC (Docker Desktop에 Docker Hub 계정으로 로그인한 상태)**

1. `.env`에 `APP_IMAGE=도커허브아이디/lostfound:v6`
2. 빌드하고 올리기:
   ```
   docker compose build app
   docker compose push app
   ```
3. Docker Hub 웹에서 `lostfound` 저장소가 생겼는지 확인. 기본은 공개(public)다. 비공개로 하려면 저장소 Settings에서 Private로 바꾼다 (무료 계정은 비공개 1개). 이미지에는 `.env`가 들어가지 않는다 (`.dockerignore`)

**EC2 (소스 코드 없이 `.env`와 `deploy/update.sh` 두 파일만)**

1. Docker 설치: `sudo apt update && sudo apt install -y docker.io` → `sudo usermod -aG docker ubuntu` → 접속을 끊고 다시 접속
2. 폴더를 만들고 두 파일을 보낸다:
   ```
   # EC2에서
   mkdir -p ~/lostfound
   # 내 PC PowerShell에서 (lostfound 폴더 안)
   scp -i $env:USERPROFILE\.ssh\키.pem .env deploy/update.sh ubuntu@<퍼블릭 IP>:~/lostfound/
   # 키.pem은 실제 키 파일 경로로 바꾼다
   ```
   EC2에서 `nano ~/lostfound/.env`로 `APP_BIND=0.0.0.0`과 `APP_IMAGE={도커허브아이디}/lostfound:v6`를 확인한다.
3. 비공개 저장소라면 `docker login -u 도커허브아이디` (비밀번호 자리에 Docker Hub → Account settings → Personal access tokens에서 만든 Read 토큰)
4. 처음 실행: `chmod +x ~/lostfound/update.sh && ~/lostfound/update.sh` → 네트워크·볼륨·DB를 만들고 앱을 띄운다. `docker ps`로 app·db가 Up인지, `curl http://127.0.0.1:8080/health`로 확인
5. 자동 업데이트: `crontab -e`(편집기는 1번 nano)로 맨 아래에 한 줄 추가 → `Ctrl+O`, `Enter`, `Ctrl+X`
   ```
   */5 * * * * /home/ubuntu/lostfound/update.sh >> /home/ubuntu/lostfound/update.log 2>&1
   ```

**코드를 고친 뒤**: 내 PC에서 `docker compose build app && docker compose push app` → EC2가 5분 안에 새 이미지로 앱만 교체한다 (바로 하려면 `~/lostfound/update.sh`). DB 데이터는 볼륨에 그대로 남는다. 인스턴스를 중지했다 켜도 컨테이너는 자동으로 다시 뜨고, 바뀌는 것은 퍼블릭 IP뿐이다.

## 6. 사용 방법

| 메뉴 | 누가 | 하는 일 |
| --- | --- | --- |
| 보관 중인 물건 | 로그인한 누구나 | 공개 목록 표. 공개 1단계는 종류·건물·습득일만, 보관 7일 뒤 담당자가 특징을 보완하면(2단계) 색상·상세 장소·특징 네 칸까지 (R3, R9). 종류·건물로 거르고, 습득일(최신·오래된)·공개 단계(낮은·높은) 순으로 정렬 |
| 주운 물건 맡기기 | 습득자 | 주운 건물·장소·시각 등록 → 접수번호 → 학생지원팀에 맡김 (R1) |
| 분실 신고 | 분실자 | 특징(아는 칸만)·장소·시간 범위 입력 → 일치 가능성 높은 물건과 접수번호 (R4, R5) |
| 내 신고 | 분실자 | 내 신고 목록, 다시 찾기, 취소 (R8) |
| 보관소 관리 | 담당자 | 입고 대기 물건 세부 입력(R2), 보관 7일 지난 물건 특징 보완(R9), 보관 30일 지난 물건 복정파출소 인계 — 한 건씩 또는 인계 대상 모두 한 번에(R10), 신고 조회와 수령 처리(R6) |

## 7. 파일 구조

| 파일 | 맡는 일 | 요구사항 |
| --- | --- | --- |
| `src/main/java/kr/ac/gachon/lostfound/` | (모든 Java 파일이 이 한 폴더에 있다) | |
| `LostFoundApplication.java` | 프로그램 시작점. Gemini 설정, 시작할 때 DB 준비 | R7 |
| `ApiController.java` | 모든 요청 주소, 로그인·담당자 확인, 오류 응답 모양 | 전체 |
| `AuthService.java` | 인증번호 발급·확인, 비밀번호 해시, 로그인 세션 | R11 |
| `Mailer.java` | 인증번호 메일 보내기 (SMTP, 없으면 로그) | R11 |
| `LostFoundService.java` | 기능별 처리 순서 (메서드 하나가 트랜잭션 하나) | R1~R10 |
| `Policy.java` | DB·외부 API 없이 판단하는 규칙: 로그인 가능한 이메일(학교 이메일 + 등록된 담당자), 보완·인계 시점, 공개 단계, 확인용 특징 노출 검사, 구체성, 등급 | R3~R5, R8~R11 |
| `Requests.java` | 입력 형식 검사. record 생성자에서 검사해 잘못된 값으로는 객체가 만들어지지 않음 | R1, R2, R4, R6, R9~R11 |
| `Models.java` | 데이터 모양: DB 한 행(습득물·신고·사용자)과 화면별 응답 | R3, R8 |
| `Db.java` | 테이블, DB 구조 버전 확인, 시드 데이터, 모든 SQL | R5, R7, R11 |
| `GeminiClient.java` | Gemini 호출 (구체성 판정, 매칭 점수), 응답 검증, 재시도, 실패 처리 | R4, R5, R5-2, R8 |
| `ApiException.java` | 사용자에게 보여줄 문장이 있는 거절 | — |
| `src/main/resources/static/` | 화면 (index.html, app.js, style.css) | 전체 |
| `src/main/resources/seed.json` | 종류·색상·가천대 건물 목록, 시연용 습득물 50건 | — |
| `src/main/resources/application.properties` | 서버 포트, DB·메일 연결 설정 | — |
| `src/test/java/.../` | JUnit 단위 테스트: 규칙, 입력 검사, Gemini 호출(가짜 서버), 로그인 | — |
| `tools/CheckAc.java` | 실행 중인 서비스에 요청을 보내 완료 기준 점검 | — |
| `deploy/update.sh` | EC2 실행·자동 업데이트 (처음엔 DB까지 만들고, 이후 새 이미지가 있으면 앱만 교체) | — |

읽는 순서: `ApiController` → `LostFoundService`·`AuthService` → `Policy`·`Db`·`GeminiClient`. IntelliJ IDEA에서는 `pom.xml`을 열면(Open → pom.xml → Open as Project) 바로 Maven 프로젝트로 잡힌다.

## 8. 테스트

```bash
mvn test       # 단위 테스트 (JDK 21 + Maven이 있을 때. 없으면 docker compose build 가 대신 돌림)

# 서비스 실행 후 완료 기준 점검 (JDK 21 필요, 라이브러리 불필요)
java tools/CheckAc.java --staff 담당자@gachon.ac.kr --student 학생@gachon.ac.kr
```

`CheckAc`는 처음 실행할 때 두 계정에 인증번호를 보내고 입력을 기다린다 (메일함 또는 `docker compose logs app`). 두 번째부터는 같은 비밀번호로 바로 로그인한다. `--staff` 이메일은 `STAFF_EMAILS`에 있어야 한다 (Gmail 같은 외부 주소도 됨). AI를 켜면 AC5(고양이 스티커 이어폰이 1순위)를, 끄면 AC9(대체 목록)를 확인한다. AC5·AC17은 시드 데이터 기준이다.

## 9. 문제 해결

| 증상 | 원인과 해결 |
| --- | --- |
| 로그에 "DB 구조를 v5에서 v6로 바꿨습니다" | 정상. 데이터는 그대로 두고 공개 설명 칸만 없앤 것 |
| 앱이 시작하자마자 "DB 구조가 이전 버전입니다" 로그와 함께 멈춤 | 예전 버전의 DB volume이 남아 있음. `docker compose down -v` 후 다시 `up` |
| 인증번호 메일이 안 옴 | `/health`의 `mail`이 `log`면 메일 설정이 없는 것 → 로그에서 인증번호 확인. `smtp`인데 안 오면 스팸함, `MAIL_PASSWORD`(앱 비밀번호) 확인. 로그에 `verification mail failed` |
| 담당자 이메일로 로그인하면 "이메일 또는 비밀번호가 맞지 않습니다" | 아직 가입하지 않은 것. "회원가입"에서 그 이메일로 인증번호를 받아 찾아줘 비밀번호를 정한다 (Gmail 비밀번호·앱 비밀번호와는 별개) |
| "가천대학교 이메일로 인증해주세요" | 아이디가 `@gachon.ac.kr`로 끝나야 한다. 담당자용 외부 이메일이면 `STAFF_EMAILS`에 있는지 확인 후 `docker compose up -d` |
| 보관소 관리 메뉴가 안 보임 | `.env`의 `STAFF_EMAILS`에 그 이메일이 있는지 확인 후 `docker compose up -d` |
| 로그인했는데 바로 로그인 화면으로 돌아감 | `http`로 접속하면서 `COOKIE_SECURE=1`이면 쿠키가 저장되지 않는다. `0`으로 |
| EC2에서 `exec format error` 로 app이 바로 꺼짐 | 내 PC가 ARM(Apple Silicon·Snapdragon)이라 다른 CPU용 이미지가 올라간 것. `compose.yaml`의 `platform: linux/amd64`가 있는지 확인 후 다시 build·push |
| EC2에서 `pull access denied` | 비공개 저장소인데 `docker login`을 안 했거나 `APP_IMAGE` 아이디·이름이 틀림 |
| 외부(EC2 밖)에서 접속 안 됨 | `.env`의 `APP_BIND=0.0.0.0` 확인, 보안 그룹 8080 인바운드 확인 |
| 분실 신고 결과에 "AI 비교를 할 수 없어…" | `ENABLE_AI=1`, `GEMINI_API_KEY` 확인. `docker compose logs app`의 `provider=gemini status=...` 줄로 원인 확인 |
| 시작 로그에 "GEMINI_API_KEY 에 예시 문구나 한글이 들어 있습니다" | `.env`의 키 자리에 실제 키를 붙여 넣었는지 확인. 그동안 매칭은 대체 목록으로 동작 |
