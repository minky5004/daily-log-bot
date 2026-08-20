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
