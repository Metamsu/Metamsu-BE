#!/usr/bin/env python3
"""Run question generation against a ReCall backend and export the result for human review.

    python scripts/live_eval.py --pdf lecture1.pdf lecture2.pdf --out eval-out   # LIVE (paid)
    python scripts/live_eval.py --demo --out eval-out                            # DEMO job, free
    python scripts/live_eval.py --dry-run --out eval-out                         # no server at all

Standard library only. The Claude API key is never read here: the backend holds it.
"""

import argparse
import csv
import difflib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
import uuid

LIVE_HELP = """서버에서 실시간(LIVE) 생성이 꺼져 있습니다.
백엔드를 띄우기 전에 아래 환경변수를 설정한 뒤 다시 실행하세요. (PowerShell 예시)

  $env:LIVE_ENABLED="true"
  $env:CLAUDE_API_KEY="<본인 키>"            # 코드나 커밋에 넣지 마세요
  $env:CLAUDE_MODEL="claude-haiku-4-5-20251001"  # 선택
  $env:PROMPT_VERSION="v1"                   # 선택: v1=기준선, v2=개선 프롬프트(기본값)

키 없이 흐름만 확인하려면 --demo 또는 --dry-run 을 사용하세요."""


class ApiError(Exception):
    def __init__(self, status, code, message):
        super().__init__(f"{status} {code}: {message}")
        self.status, self.code = status, code


class Client:
    def __init__(self, base_url):
        self.base = base_url.rstrip("/")
        self.token = None

    def request(self, method, path, body=None, content_type="application/json"):
        headers = {}
        if self.token:
            headers["X-Learner-Token"] = self.token
        data = None
        if body is not None:
            data = body if isinstance(body, bytes) else json.dumps(body).encode("utf-8")
            headers["Content-Type"] = content_type
        req = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=60) as response:
                raw = response.read()
        except urllib.error.HTTPError as error:
            try:
                detail = json.loads(error.read().decode("utf-8"))
            except ValueError:
                detail = {}
            raise ApiError(error.code, detail.get("code", "HTTP_ERROR"), detail.get("message", ""))
        except urllib.error.URLError as error:
            raise SystemExit(
                f"백엔드({self.base})에 연결할 수 없습니다: {error.reason}\n"
                "먼저 백엔드를 실행하세요: .\\mvnw.cmd spring-boot:run \"-Dspring-boot.run.profiles=dev\""
            )
        return json.loads(raw.decode("utf-8")) if raw else None

    def upload(self, path):
        boundary = uuid.uuid4().hex
        name = os.path.basename(path).replace('"', "")
        with open(path, "rb") as file:
            content = file.read()
        body = (
            f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
            "Content-Type: application/pdf\r\n\r\n"
        ).encode("utf-8") + content + f"\r\n--{boundary}--\r\n".encode("utf-8")
        return self.request(
            "POST", "/api/documents", body, f"multipart/form-data; boundary={boundary}"
        )


def run_job(client, request, timeout, poll=1.0, log=print):
    """Create a job, wait for a terminal status, return (job, its questions)."""
    try:
        job = client.request("POST", "/api/jobs", request)
    except ApiError as error:
        if error.code == "LIVE_DISABLED":
            raise SystemExit(LIVE_HELP)
        raise
    deadline = time.time() + timeout
    while job["status"] in ("queued", "running"):
        if time.time() > deadline:
            raise SystemExit(f"작업 {job['id']} 이(가) {timeout}초 안에 끝나지 않았습니다.")
        time.sleep(poll)
        job = client.request("GET", f"/api/jobs/{job['id']}")
        log(f"  {job['status']}: 청크 {job['completedChunks'] + job['failedChunks']}/{job['totalChunks']}")
    questions = [
        q for q in client.request("GET", "/api/questions?view=manage") if q.get("jobId") == job["id"]
    ]
    return job, questions


def normalize(text):
    return re.sub(r"[\W_]+", "", text.lower())


def quality_metrics(questions):
    """Checks that need no human: answer position bias, length giveaway, near duplicates."""
    positions = [0, 0, 0, 0]
    longest = 0
    for q in questions:
        positions[q["correctIndex"]] += 1
        lengths = [len(option) for option in q["options"]]
        if lengths[q["correctIndex"]] == max(lengths) and lengths.count(max(lengths)) == 1:
            longest += 1
    duplicates = 0
    texts = [normalize(q["text"]) for q in questions]
    for i in range(len(texts)):
        for j in range(i + 1, len(texts)):
            if difflib.SequenceMatcher(None, texts[i], texts[j]).ratio() >= 0.85:
                duplicates += 1
    return {"positions": positions, "correctIsLongest": longest, "nearDuplicatePairs": duplicates}


def run_record(name, job, questions):
    chunks = job.get("chunks") or []
    attempted = [c for c in chunks if c.get("parseAttempts", 0) > 0]
    errors = {}
    for chunk in chunks:
        if chunk["status"] == "failed":
            reason = chunk.get("error") or "UNKNOWN"
            errors[reason] = errors.get(reason, 0) + 1
    record = {
        "name": name,
        "jobId": job["id"],
        "mode": job["mode"],
        "promptVersion": job.get("promptVersion"),
        "status": job["status"],
        "totalChunks": job["totalChunks"],
        "succeededChunks": job["completedChunks"],
        "failedChunks": job["failedChunks"],
        "failureReasons": errors,
        "questionCount": len(questions),
        "durationMs": sum(c.get("durationMs") or 0 for c in chunks),
        "inputTokens": sum(c.get("inputTokens") or 0 for c in chunks),
        "outputTokens": sum(c.get("outputTokens") or 0 for c in chunks),
        "retries": sum(max(0, (c.get("attempts") or 0) - 1) for c in chunks),
        "parseSuccessRate": (
            round(100.0 * sum(1 for c in attempted if c.get("parseSuccess")) / len(attempted), 1)
            if attempted
            else None
        ),
        "createdAt": job.get("createdAt"),
    }
    record.update(quality_metrics(questions))
    return record


def pages(q):
    start, end = q.get("sourceStartPage"), q.get("sourceEndPage")
    if start is None:
        return "-"
    return str(start) if end in (None, start) else f"{start}-{end}"


def write_markdown(path, name, record, questions):
    lines = [
        f"# {name}",
        "",
        f"- 모드: {record['mode']} · 프롬프트 버전: {record['promptVersion'] or '-'} · 문항 수: {len(questions)}",
        f"- 작업 ID: {record['jobId']} · 상태: {record['status']}",
        "",
    ]
    for number, q in enumerate(questions, 1):
        lines.append(f"## {number}. {q['text']}")
        lines.append("")
        for index, option in enumerate(q["options"]):
            mark = " ✅ (정답)" if index == q["correctIndex"] else ""
            lines.append(f"{index + 1}. {option}{mark}")
        lines += [
            "",
            f"- 태그: `{q['tagId']}` · 난이도: {q['difficulty']} · 출처 페이지: {pages(q)}",
            f"- 해설: {q.get('explanation', '')}",
            "",
        ]
    with open(path, "w", encoding="utf-8", newline="\n") as file:
        file.write("\n".join(lines))


CSV_HEADER = [
    "no", "question_id", "text", "option_1", "option_2", "option_3", "option_4",
    "correct_option", "tag_id", "difficulty", "explanation", "source_pages", "prompt_version",
    "usable(Y/N)", "tag_correct(Y/N)", "memo",
]  # fmt: skip


def write_csv(path, questions):
    # utf-8-sig so Excel opens Korean text correctly.
    with open(path, "w", encoding="utf-8-sig", newline="") as file:
        writer = csv.writer(file)
        writer.writerow(CSV_HEADER)
        for number, q in enumerate(questions, 1):
            writer.writerow(
                [number, q["id"], q["text"], *q["options"], q["correctIndex"] + 1, q["tagId"],
                 q["difficulty"], q.get("explanation", ""), pages(q), q.get("promptVersion") or "",
                 "", "", ""]
            )  # fmt: skip


def write_summary(out, records):
    """Rewrite summary.md from every run recorded in runs.json, so v1 and v2 sit side by side."""
    lines = [
        "# 생성 결과 요약",
        "",
        "같은 PDF를 프롬프트 버전만 바꿔 다시 돌리면 아래 표에 행이 추가되어 나란히 비교할 수 있습니다.",
        "",
        "## 실행 지표",
        "",
        "| 자료 | 모드 | 프롬프트 | 상태 | 청크(성공/실패/전체) | 문항 수 | 소요(초) | 입력 토큰 | 출력 토큰 | 재시도 | 파싱 성공률 | 실패 사유 |",
        "|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for r in records:
        reasons = ", ".join(f"{k}×{v}" for k, v in r["failureReasons"].items()) or "-"
        parse = "-" if r["parseSuccessRate"] is None else f"{r['parseSuccessRate']}%"
        lines.append(
            f"| {r['name']} | {r['mode']} | {r['promptVersion'] or '-'} | {r['status']} | "
            f"{r['succeededChunks']}/{r['failedChunks']}/{r['totalChunks']} | {r['questionCount']} | "
            f"{r['durationMs'] / 1000:.1f} | {r['inputTokens']} | {r['outputTokens']} | {r['retries']} | "
            f"{parse} | {reasons} |"
        )
    lines += [
        "",
        "## 자동 품질 지표 (사람 검토 없이 계산)",
        "",
        "| 자료 | 프롬프트 | 정답 위치 분포(1/2/3/4번) | 정답이 가장 긴 보기 | 유사 문항 쌍 |",
        "|---|---|---|---|---|",
    ]
    for r in records:
        count = r["questionCount"]
        longest = f"{r['correctIsLongest']}/{count}" if count else "-"
        lines.append(
            f"| {r['name']} | {r['promptVersion'] or '-'} | {'/'.join(map(str, r['positions']))} | "
            f"{longest} | {r['nearDuplicatePairs']} |"
        )
    lines += [
        "",
        "- 정답 위치가 한 번호에 몰리거나, 정답이 가장 긴 보기인 비율이 25%보다 크게 높으면 찍어서 맞히기 쉬운 세트입니다.",
        "- 사용 가능 문항 비율과 태그 일치 비율은 각 CSV의 `usable(Y/N)`, `tag_correct(Y/N)` 칸을 채워 계산하세요.",
        "",
    ]
    with open(os.path.join(out, "summary.md"), "w", encoding="utf-8", newline="\n") as file:
        file.write("\n".join(lines))


def save(out, name, job, questions):
    os.makedirs(out, exist_ok=True)
    record = run_record(name, job, questions)
    stem = re.sub(r'[\\/:*?"<>|\s]+', "_", os.path.splitext(name)[0])
    if record["promptVersion"]:
        stem += "." + record["promptVersion"]
    write_markdown(os.path.join(out, stem + ".md"), name, record, questions)
    write_csv(os.path.join(out, stem + ".csv"), questions)
    runs_path = os.path.join(out, "runs.json")
    records = []
    if os.path.exists(runs_path):
        with open(runs_path, encoding="utf-8") as file:
            records = json.load(file)
    records.append(record)
    with open(runs_path, "w", encoding="utf-8") as file:
        json.dump(records, file, ensure_ascii=False, indent=2)
    write_summary(out, records)
    return stem


def sample():
    """Fixed data shaped like the real API responses, for --dry-run and the unit tests."""
    job = {
        "id": "00000000-0000-0000-0000-000000000001", "mode": "LIVE", "promptVersion": "v2",
        "status": "partial", "totalChunks": 2, "completedChunks": 1, "failedChunks": 1,
        "createdAt": "2026-01-01T00:00:00Z",
        "chunks": [
            {"index": 0, "status": "succeeded", "error": None, "attempts": 2, "durationMs": 2400,
             "inputTokens": 3100, "outputTokens": 620, "parseAttempts": 2, "parseSuccess": True},
            {"index": 1, "status": "failed", "error": "PROVIDER_HTTP_529", "attempts": 3,
             "durationMs": 900, "inputTokens": 0, "outputTokens": 0, "parseAttempts": 0,
             "parseSuccess": False},
        ],
    }  # fmt: skip
    questions = [
        {"id": "q-1", "jobId": job["id"], "promptVersion": "v2", "tagId": "thread",
         "difficulty": "MEDIUM", "sourceStartPage": 3, "sourceEndPage": 4, "correctIndex": 2,
         "text": "같은 프로세스의 스레드들이 공유하지 않는 것은?",
         "options": ["코드 영역", "힙 영역", "스택 영역", "열린 파일 목록"],
         "explanation": "스레드는 각자 스택을 가진다, \"힙\"은 공유한다."},
        {"id": "q-2", "jobId": job["id"], "promptVersion": "v2", "tagId": "deadlock",
         "difficulty": "EASY", "sourceStartPage": 7, "sourceEndPage": 7, "correctIndex": 0,
         "text": "교착 상태의 필요 조건이 아닌 것은?",
         "options": ["선점이 가능한 자원 할당", "상호 배제", "점유 대기", "순환 대기"],
         "explanation": "필요 조건은 상호 배제, 점유 대기, 비선점, 순환 대기이다."},
    ]  # fmt: skip
    return job, questions


def main(argv=None):
    parser = argparse.ArgumentParser(description="ReCall 문제 생성 결과를 검토용 파일로 내보냅니다.")
    parser.add_argument("--pdf", nargs="+", default=[], help="LIVE 생성에 쓸 PDF 경로 (여러 개 가능)")
    parser.add_argument("--demo", action="store_true", help="키 없이 DEMO 작업으로 전체 흐름 확인")
    parser.add_argument("--dry-run", action="store_true", help="서버 없이 예시 데이터로 출력 형식만 확인")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--out", default="eval-out", help="결과 폴더 (기본: eval-out)")
    parser.add_argument("--timeout", type=int, default=900, help="작업당 대기 시간(초)")
    args = parser.parse_args(argv)
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")

    if args.dry_run:
        job, questions = sample()
        stem = save(args.out, "dry-run-sample.pdf", job, questions)
        print(f"[dry-run] 서버 호출 없이 예시 결과를 썼습니다: {os.path.join(args.out, stem)}.md/.csv, summary.md")
        return 0
    if not args.pdf and not args.demo:
        parser.error("--pdf, --demo, --dry-run 중 하나가 필요합니다.")
    missing = [path for path in args.pdf if not os.path.isfile(path)]
    if missing:
        parser.error("파일을 찾을 수 없습니다: " + ", ".join(missing))

    client = Client(args.base_url)
    config = client.request("GET", "/api/config")
    if args.pdf and not config.get("liveEnabled"):
        print(LIVE_HELP, file=sys.stderr)
        return 2
    client.token = client.request("POST", "/api/learners", {})["token"]
    if args.demo:
        print("DEMO 작업 실행 (Claude 호출 없음)")
        job, questions = run_job(client, {"mode": "DEMO"}, args.timeout)
        stem = save(args.out, "demo", job, questions)
        print(f"  → {len(questions)}문항: {os.path.join(args.out, stem)}.md/.csv")
    for path in args.pdf:
        print(f"LIVE 생성: {path} (프롬프트 {config.get('promptVersion', '?')})")
        try:
            document = client.upload(path)
        except ApiError as error:
            print(f"  업로드 실패: {error}", file=sys.stderr)
            continue
        job, questions = run_job(
            client, {"mode": "LIVE", "documentId": document["id"]}, args.timeout
        )
        stem = save(args.out, os.path.basename(path), job, questions)
        print(f"  → {job['status']}, {len(questions)}문항: {os.path.join(args.out, stem)}.md/.csv")
    print(f"요약: {os.path.join(args.out, 'summary.md')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
