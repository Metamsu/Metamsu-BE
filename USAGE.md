# ReCall 종합 사용법

강의 PDF로 객관식 문제를 만들고, 풀이 결과에 따라 복습 일정을 잡아 주는 서비스입니다.
이 문서는 백엔드(`Metamsu-BE`), 프론트엔드(`Metamsu-FE`), 평가 스크립트를 한 번에 다룹니다.
세부 API 명세는 각 저장소의 `README.md`를 보세요.

## 1. 준비물

| 도구 | 버전 | 용도 |
|---|---|---|
| Java (JDK) | 21 | 백엔드 실행·테스트 |
| Node.js | 22 이상 (README 권장 24) | 프론트엔드 |
| Python | 3.10 이상 | 평가 스크립트 (표준 라이브러리만 사용) |
| PostgreSQL | 선택 | 운영 프로필, `pgtest` 테스트. 로컬 개발은 없어도 됩니다 |
| Claude API 키 | 선택 | 실제 문제 생성(LIVE)에만 필요. 없으면 DEMO로 동작합니다 |

두 저장소를 같은 폴더에 받습니다.

```bash
git clone https://github.com/Metamsu/Metamsu-BE.git
git clone https://github.com/Metamsu/Metamsu-FE.git
```

## 2. 실행하기 (키 없이, 비용 없음)

터미널 두 개를 엽니다. 아래 명령은 Windows PowerShell 기준입니다.

**백엔드** (포트 8080, `dev` 프로필은 파일형 H2 DB를 `data/`에 만듭니다)

```powershell
cd Metamsu-BE
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

**프론트엔드** (포트 5173, `/api` 요청을 8080으로 넘깁니다)

```powershell
cd Metamsu-FE
npm ci
npm run dev
```

브라우저에서 `http://localhost:5173`을 엽니다.

## 3. 화면 사용 흐름

1. **PDF · 문제 생성** 화면에서 **고정 데모 문제 만들기**를 누르면 샘플 3문제가 생깁니다. 키가 없어도 됩니다.
2. **퀴즈 풀기**에서 답을 제출하면 서버가 채점하고 정답·해설·다음 복습일을 보여 줍니다.
3. **오늘의 복습**에는 복습일이 된 문제만 나옵니다.
   - 첫 답은 맞든 틀리든 1단계(내일 복습)입니다.
   - 이후 맞히면 한 단계씩 올라가고(최대 5단계), 틀리면 1단계로 돌아갑니다.
   - 단계별 간격은 1 / 3 / 7 / 14 / 30일입니다.
4. **문제 보관함**에서 문제를 수정·삭제하고 품질을 평가합니다(5장).
5. **학습 대시보드**에서 전체와 개념별 정답률을 봅니다.

학습 기록은 브라우저에 저장된 익명 토큰에 묶여 있습니다. 브라우저 저장소를 지우면 기록에 다시 접근할 수 없습니다.

## 4. 실제 AI 문제 생성 (LIVE, 유료)

백엔드를 띄우기 **전에** 같은 터미널에서 환경변수를 설정합니다. 키는 코드나 커밋에 넣지 않습니다.

```powershell
$env:LIVE_ENABLED="true"
$env:CLAUDE_API_KEY="<본인 키>"
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

그다음 화면에서 PDF를 올리고 **PDF로 AI 문제 생성**을 누릅니다.

- PDF는 텍스트가 있는 파일만 됩니다(10MB, 80쪽 이하). 스캔본은 거부됩니다.
- 12,000자 단위 청크마다 최대 3문제, 작업당 최대 30문제를 만듭니다.
- 프롬프트와 태그 10개가 **운영체제 과목에 맞춰져 있습니다.** 다른 과목 PDF는 태그가 맞지 않습니다.

### 주요 환경변수

| 변수 | 기본값 | 설명 |
|---|---|---|
| `LIVE_ENABLED` | `false` | `true`이고 키가 있어야 LIVE가 켜집니다 |
| `CLAUDE_API_KEY` | 없음 | Claude API 키 |
| `CLAUDE_MODEL` | `claude-haiku-4-5-20251001` | 사용할 모델 |
| `PROMPT_VERSION` | `v2` | `v1`은 원래 프롬프트(기준선), `v2`는 품질 개선 프롬프트 |
| `STRUCTURED_OUTPUTS` | `true` | v2에서 JSON 스키마를 API의 구조화 출력으로 보냅니다. `false`면 프롬프트에 넣습니다 |
| `TARGET_USABLE_RATE` / `TARGET_TAG_RATE` | `60` / `80` | 품질 평가 요약의 목표 비율(%) |
| `ANTHROPIC_BASE_URL` | `https://api.anthropic.com` | 요청을 보낼 주소. 로컬 모델을 쓸 때 바꿉니다 |
| `PROVIDER_TIMEOUT_SECONDS` | `45` | 청크 하나의 응답 제한 시간(1~1800초) |
| `DISABLE_THINKING` | `false` | 모델의 생각 단계를 끕니다. Ollama에서 필요합니다 |

### v1과 v2의 차이

| | v1 (기준선) | v2 (기본값) |
|---|---|---|
| 프롬프트 | 형식·보안 지시 6줄 | 품질 기준, 난이도 정의, 좋은/나쁜 예시 추가 (`src/main/resources/prompts/question-gen-v2.txt`) |
| 출력 형식 | 스키마를 프롬프트에 포함 | 구조화 출력(`output_config.format`) |
| 출제할 내용이 없는 청크 | 최소 1문제 강제 | 0문제 허용 |
| 잘못된 문항이 섞인 응답 | 청크 전체 실패 후 재시도 | 잘못된 문항만 제외 |
| 청크 간 중복 | 검사 없음 | 이미 낸 문항을 알려 주고 서버에서도 제거 |

v2 요청 형식은 모의 서버로만 검증했습니다. 실제 API에서 요청이 거부되면 `STRUCTURED_OUTPUTS=false`로 실행하세요.

### 무료 대안: Ollama로 로컬 오픈소스 모델 쓰기

[Ollama](https://ollama.com)는 Claude와 같은 형식의 API를 제공하므로, 키와 비용 없이 내 PC의 모델로 LIVE 생성을 돌릴 수 있습니다.

1. 모델을 받고, 문맥 길이를 늘린 사본을 만듭니다. 기본 문맥 길이(4096)로는 12,000자 청크가 잘립니다.

   ```powershell
   ollama pull qwen3.5:9b
   Set-Content Modelfile "FROM qwen3.5:9b`nPARAMETER num_ctx 16384`nPARAMETER temperature 0.3"
   ollama create recall-qwen -f Modelfile
   ```

2. 아래 환경변수로 백엔드를 띄웁니다. `CLAUDE_API_KEY`는 비어 있지만 않으면 됩니다.

   ```powershell
   $env:LIVE_ENABLED="true"
   $env:ANTHROPIC_BASE_URL="http://localhost:11434"
   $env:CLAUDE_API_KEY="ollama"
   $env:CLAUDE_MODEL="recall-qwen"
   $env:STRUCTURED_OUTPUTS="false"
   $env:DISABLE_THINKING="true"
   $env:PROVIDER_TIMEOUT_SECONDS="1800"
   .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
   ```

- 2쪽짜리 짧은 PDF로 3문항 생성까지 확인했습니다(그래픽카드 없는 노트북 CPU에서 약 4분 40초).
- 속도는 PC 사양에 달려 있습니다. 그래픽카드가 없으면 12,000자를 꽉 채운 청크는 20분 이상 걸릴 수 있고, 1800초를 넘기면 `PROVIDER_TIMEOUT`으로 실패합니다.
- 문항 품질은 Claude보다 낮을 수 있습니다. 5장의 품질 평가로 직접 확인하세요.

## 5. 문항 품질 평가

**문제 보관함**의 각 문항 아래에 평가 영역이 있습니다.

1. **사용 가능 / 사용 불가**와 **태그 맞음 / 태그 틀림**을 고릅니다.
2. 사용 불가라면 사유(정답 오류, 모호함, 강의와 무관, 너무 쉬움, 중복, 기타)와 메모를 적을 수 있습니다.
3. **평가 저장**을 누르면 상단 요약이 바로 갱신됩니다.

- 상단 요약은 평가 진행률, 사용 가능 비율, 태그 일치 비율, 목표 달성 여부를 보여 줍니다. 비율은 평가한 문항 기준입니다.
- **아직 평가 안 한 문항만 보기**로 남은 문항만 볼 수 있습니다.
- **CSV 내려받기**로 모든 문항과 평가를 엑셀용 파일로 받습니다.
- 문항을 수정하면 그 문항의 평가는 지워집니다. 내용이 바뀌었기 때문입니다.

## 6. 평가 스크립트 (`scripts/live_eval.py`)

여러 PDF를 한꺼번에 돌리고 결과를 검토용 파일로 뽑습니다. 백엔드가 떠 있어야 합니다(`--dry-run` 제외).

```powershell
cd Metamsu-BE
python scripts/live_eval.py --dry-run --out eval-out                 # 서버 없이 출력 형식만 확인
python scripts/live_eval.py --demo --out eval-out                    # 실제 서버, DEMO 작업 (무료)
python scripts/live_eval.py --pdf 강의1.pdf 강의2.pdf --out eval-out   # LIVE (유료)
```

결과 폴더에 생기는 파일:

| 파일 | 내용 |
|---|---|
| `<PDF이름>.<프롬프트버전>.md` | 문항별 문제, 보기, 정답 표시, 태그, 난이도, 해설, 출처 페이지 |
| `<PDF이름>.<프롬프트버전>.csv` | 같은 내용 + 검토용 빈 칸 `usable(Y/N)`, `tag_correct(Y/N)`, `memo` |
| `summary.md` | 실행 지표(청크 성공/실패, 실패 사유, 문항 수, 소요 시간, 토큰, 재시도, 파싱 성공률)와 자동 품질 지표(정답 위치 분포, 정답이 가장 긴 보기 비율, 유사 문항 수) |
| `runs.json` | 누적 실행 기록. `summary.md`는 이 기록으로 다시 만듭니다 |

`eval-out/`은 강의자료에서 나온 내용이라 git에 올라가지 않게 해 두었습니다.

### v1과 v2 비교하기 (키를 처음 연결할 때의 순서)

기본값이 v2이므로, **첫 실행은 반드시 v1로** 돌려야 개선 전 기준선이 남습니다.

1. `$env:PROMPT_VERSION="v1"`로 백엔드를 띄우고 PDF 2~3개를 스크립트로 돌립니다.
2. 백엔드를 끄고 `$env:PROMPT_VERSION="v2"`로 다시 띄운 뒤, 같은 PDF를 같은 `--out` 폴더로 돌립니다.
3. `summary.md`에서 두 버전을 나란히 비교합니다.
4. 각 CSV의 `usable`, `tag_correct` 칸을 채우거나, 화면의 문제 보관함에서 평가해 사용 가능 비율과 태그 일치 비율을 구합니다.

## 7. 테스트

| 대상 | 명령 | 비고 |
|---|---|---|
| 백엔드 | `.\mvnw.cmd test` | H2 + 모의 Claude 서버. 실제 API 호출 없음 |
| 백엔드 (PostgreSQL) | 아래 참고 | 전용 테스트 DB 필요 |
| 프론트엔드 | `npm test`, `npm run typecheck`, `npm run build` | |
| 스크립트 | `python -m unittest discover -s scripts -p "test_*.py"` | 서버 불필요 |
| 브라우저 e2e | 아래 참고 | 백엔드(8080)와 프론트(5173)를 먼저 띄웁니다 |

**PostgreSQL에서 백엔드 테스트** (테스트가 데이터를 남기므로 실제 DB를 가리키지 마세요)

```powershell
$env:DB_URL="jdbc:postgresql://localhost:5432/recall_test"
$env:DB_USERNAME="recall"; $env:DB_PASSWORD="<테스트 DB 비밀번호>"
.\mvnw.cmd -Ppgtest verify
```

**브라우저 e2e** (`npm run test:e2e`는 POSIX 셸 문법이라 Windows에서는 Git Bash에서 아래처럼 실행합니다)

```bash
cd Metamsu-FE
PLAYWRIGHT_BROWSERS_PATH=.playwright-browsers npx playwright install chromium   # 처음 한 번
PLAYWRIGHT_BROWSERS_PATH=.playwright-browsers npx playwright test
```

## 8. 자주 겪는 문제

| 증상 | 원인과 해결 |
|---|---|
| `java`를 찾을 수 없음 | JDK 21을 설치하고 터미널을 새로 엽니다 |
| 백엔드가 DB 연결 오류로 종료 | `dev` 프로필 없이 실행하면 PostgreSQL에 붙으려 합니다. `-Dspring-boot.run.profiles=dev`를 붙이세요 |
| PDF로 AI 문제 생성을 쓸 수 없음 | LIVE가 꺼져 있습니다. 4장의 환경변수를 설정하고 백엔드를 다시 띄우세요 |
| 스크립트가 "LIVE 생성이 꺼져 있습니다"로 종료 | 위와 같습니다. `.env` 파일은 자동으로 읽지 않습니다 |
| PDF 업로드가 400 | 스캔본(텍스트 없음), 암호화 PDF, 80쪽 초과입니다 |
| 화면이 학습 기록에 접근할 수 없다며 막힘 (401) | DB를 지웠거나 다른 서버에 붙은 경우입니다. 화면의 "확인 후 새 학습자로 시작"을 누르세요 |
| LIVE 작업의 모든 청크가 `PROVIDER_HTTP_400` | 구조화 출력 요청이 거부된 것일 수 있습니다. `STRUCTURED_OUTPUTS=false`로 실행해 보세요 |

## 9. 알려진 한계

- 인증이 없는 로컬 개발용 MVP입니다. 인터넷에 공개하지 마세요.
- v2 요청은 실제 Claude API로 보내 본 적이 없고, v1 대비 품질 효과도 아직 측정하지 않았습니다.
- 정답 위치는 섞지 않습니다. `summary.md`의 정답 위치 분포로 편향을 확인하세요.
- DEMO는 여전히 고정 샘플 3문제입니다.
