# daily-log-bot

> 매일 자정 · 계정 전체의 그날 커밋과 PR 을 TIL 한 장으로 접어 [study-log](https://study-log-n6ez.onrender.com) 에 올리는 봇

**산출물 — [2026-08-19 개발 기록](https://study-log-n6ez.onrender.com/logs/132)** · 배포된 화면이 아니라 매일 쌓이는 기록이 이 도구의 결과물

![study-log 에 올라간 노트](docs/screenshots/note.png)

study-log 를 만들고 남은 문제 — 기록 한 편의 값이 하루를 되짚는 수고. 그 되짚기의 재료는 이미
커밋과 PR 에 전부 있어 자동화 대상. 사람 몫으로 남긴 것은 하나 — 아침에 `end` 를 실제 공부
종료 시각으로 고치는 것. 화면의 `08:20–08:21 · 1분` 이 고치기 전 상태 · `start` 는 첫 커밋
시각에서 딴 실측 · `end` 는 자리만 잡아 둔 값.

study-log 는 한 줄도 고치지 않음 — 이미 뚫려 있는 `/import` 로만 들어가는 쪽.

## 기술 스택

| 구분 | 기술 |
|---|---|
| Language | Java 21 |
| Build | Gradle 9.5.1 · `application` 플러그인으로 `./gradlew run` |
| HTTP · JSON | JDK `HttpClient` · Jackson (프레임워크 없음 · 직접 의존 1개) |
| 외부 | GitHub REST · Gemini `gemini-3.6-flash` 무료 티어 · study-log 폼 로그인과 `/import` |
| 실행 주체 | GitHub Actions `schedule` cron |
| Test | JUnit 5 · 46개 |

## 실행

실행 주체는 러너 — 로컬 실행은 검증 통로 · 시크릿 넷 · 변수 하나가 있어야 뜸.

```bash
git clone https://github.com/minky5004/daily-log-bot.git && cd daily-log-bot
export GH_PAT=ghp_...                  # repo 범위 · 계정 전체 조회
export GEMINI_API_KEY=...
export STUDYLOG_BASE_URL=https://study-log-n6ez.onrender.com
export STUDYLOG_USERNAME=... STUDYLOG_PASSWORD=...
./gradlew run                          # 어제 하루를 수집 → 요약 → 조립 → 업로드
```

넷 중 하나라도 누락 시 수집 전 중단 — 계정 전체를 다 돌고 나서야 키의 부재를 아는 실행은
GitHub 호출만 태우고 끝나는 것.

러너에서 밟는 통로.

```bash
gh workflow run daily-log.yml --ref dev -R minky5004/daily-log-bot
```

## 설계 판단

| 정한 것 | 왜 그 값인가 |
|---|---|
| 대상은 「오늘」 아닌 「어제」 · cron `7 15 * * *` UTC | KST 자정 무렵 발화 · 정각 회피는 매시 정각이 액션 예약의 가장 혼잡한 슬롯인 것 — 지연·드롭이 잦은 자리. 이미 닫힌 하루를 보는 한 몇 분 밀려도 수집 창은 동일 — 오프셋이 올리는 것은 발화 신뢰도뿐. 실측 지연 32분(첫 발화 `15:07` 예약 → `15:39` 시작) |
| 익명화는 `Anonymizer.strip` 한 자리 | 이후 단계가 보는 입력은 `AnonymousDay` 뿐 · 거기에는 private 리포명 · 커밋 메시지 · 파일 경로를 담을 필드 자체가 부재 — 프롬프트를 짤 때 무엇을 뺄지 다시 판단할 자리가 생기지 않는 쪽. 남는 것은 `비공개 저장소 1곳 · 커밋 2건` 같은 수치 · 커밋 시각. 실패 메시지의 리포 경로까지 가리는 이유 — public 리포 · 워크플로 로그가 90일 공개 · 커밋 상세 호출 하나의 429 에 그 로그로 이름이 남는 자리 |
| 중복 판정은 공개 `GET /logs` 의 세션 유무 | 프론트매터를 다시 긁지 않는 이유 — 아침의 `end` 수정에 시각 키가 어긋나는 것. `title` 과 `date` 를 마크다운 밖에도 두어 제목 짓는 규칙이 한 곳 — 두 곳이 되는 순간 같은 하루가 기록 둘. `li.session` 은 온전한 클래스 토큰으로만 셈 — `session-` 접두까지 세는 순간 봇이 제 업로드를 영영 건너뜀 |
| 깨진 요약은 실행 실패 | 빈 요약 · 빈 본문 · 500자 초과 전부 중단 — 잘라 담지 않는 쪽. 커밋 0건인 날은 모델을 부르지도 않음 — 없는 하루를 넘겼을 때 모델은 무엇이든 지어냄 · 빈 기록보다 지어낸 기록이 나쁨 |
| Gemini REST 직접 호출 · SDK 미사용 | 쓰는 기능이 생성 호출 하나뿐 — `com.google.genai` 가 덜어 줄 것은 스키마 빌더 정도 · 대가는 의존 트리 수십 개. 응답 형태는 `responseSchema` 가 잡고 내용은 `Summarizer.brokenReason` 이 보는 분담 |

**감수한 것**

- **재시도 부재** — Gemini 503 · 429 하나에 그날 기록이 사라지고 되살리는 길은 수동
  `workflow_dispatch`. 첫 자동 발화(run `32271358151`)가 정확히 그 503 으로 실패. 되치는 쪽의
  대가는 무료 한도 소진 여지 · 하루 한 번의 빈도에서는 사람이 아침에 되돌리는 비용이 더 싼 쪽
- **study-log 가 깨어 있어야 초록** — Render 무료 티어 콜드 스타트가 요청 상한을 넘겨
  `StudyLogClient` 는 첫 GET 을 120초 × 3회 되침. 그래도 못 깨우는 깊은 잠 실측 — run
  `32331541342` 가 6분을 다 쓰고 `3회 모두 응답 없음`. 사람이 먼저 깨운 뒤의 재실행이 통과.
  **POST 는 되치지 않음** — 임포트가 서버에 닿은 뒤 응답만 늦은 경우 되친 값이 기록 둘
- **private 리포의 내용은 통계로만 잔존** — 그날 작업이 전부 비공개인 날의 본문은 앙상한 채.
  판정은 `AnonymousDay.isEmpty()` 로 첫 커밋 시각의 부재와 같은 말 — private 커밋만 있던
  날도 비어 있지 않음 · 내용 없는 날에도 `start` 는 실재

## 구조

```
collect/      GitHub 조회 · KST 하루를 UTC 구간으로(DayWindow) · 커밋/PR 수집
anonymize/    private 세부 제거 — 이후 단계가 보는 유일한 입력
summarize/    Gemini REST 호출 · 응답 검증(빈 값 · 상한 초과는 실행 실패)
assemble/     프론트매터 마크다운 조립 · 태그 · 시각
upload/       중복 선판정 · 폼 로그인 · `_csrf` 파싱 · multipart /import
```

수집 · 요약 · 업로드 셋 다 얇은 전송(`GitHubClient` · `GeminiClient` · `StudyLogClient`)과 판단
(`ActivityCollector` · `Summarizer` · `Uploader`)이 갈림 — 실제 호출 없는 판단 테스트를 위한 선.

## 검증

| 무엇을 | 어떻게 |
|---|---|
| 단위 테스트 46개 | `./gradlew test --rerun-tasks` 3~4초(3회 실측 3·4·3) · 전부 통과 · 외부 호출은 `SummaryModel` · `StudyLog` 인터페이스로 가른 자리 — 실제 API 없이 |
| 무인 종단 완주 | run `32332182003` 1분 17초 — 그중 수집→요약→업로드가 27초 · `study-log 에 올림` |
| 중복 방어 | 같은 하루를 두 번 실행 — run `32197051522` 생성 · run `32199147371` `이미 있어 건너뜀` |
| 상류 장애의 실제 거동 | Gemini 503 은 요약에서 중단(run `32271358151`) · Render 콜드 스타트는 `GET /logs — 3회 모두 응답 없음` 으로 6분 뒤 중단(run `32331541342`) — 둘 다 재실행이 답 |
| study-log 콜드 스타트 | 유휴 뒤 첫 요청 33.5초 → 이어진 요청 2.5초 · 깊은 잠에서는 120초에도 무응답 |
