"""Output-format tests for live_eval.py. No server, PDF or API key needed.

    python -m unittest discover -s scripts -p "test_*.py"
"""

import csv
import io
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import live_eval  # noqa: E402


class LiveEvalOutputTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.out = self.dir.name

    def read(self, name, encoding="utf-8"):
        with io.open(os.path.join(self.out, name), encoding=encoding, newline="") as file:
            return file.read()

    def test_dry_run_writes_markdown_csv_and_summary(self):
        self.assertEqual(live_eval.main(["--dry-run", "--out", self.out]), 0)
        markdown = self.read("dry-run-sample.v2.md")
        self.assertIn("## 1. 같은 프로세스의 스레드들이 공유하지 않는 것은?", markdown)
        self.assertIn("3. 스택 영역 ✅ (정답)", markdown)
        self.assertIn("태그: `thread` · 난이도: MEDIUM · 출처 페이지: 3-4", markdown)
        self.assertIn("출처 페이지: 7\n", markdown)

        raw = self.read("dry-run-sample.v2.csv", "utf-8")
        self.assertTrue(raw.startswith("﻿"), "Excel needs the BOM for Korean text")
        rows = list(csv.reader(io.StringIO(raw.lstrip("﻿"))))
        self.assertEqual(rows[0], live_eval.CSV_HEADER)
        self.assertEqual(len(rows), 3)
        row = dict(zip(rows[0], rows[1]))
        self.assertEqual(row["correct_option"], "3")
        self.assertEqual(row["source_pages"], "3-4")
        self.assertEqual(row["prompt_version"], "v2")
        self.assertEqual(row["explanation"], '스레드는 각자 스택을 가진다, "힙"은 공유한다.')
        self.assertEqual((row["usable(Y/N)"], row["tag_correct(Y/N)"], row["memo"]), ("", "", ""))

        summary = self.read("summary.md")
        self.assertIn(
            "| dry-run-sample.pdf | LIVE | v2 | partial | 1/1/2 | 2 | 3.3 | 3100 | 620 | 3 | 100.0% | PROVIDER_HTTP_529×1 |",
            summary,
        )
        self.assertIn("| dry-run-sample.pdf | v2 | 1/0/1/0 | 1/2 | 0 |", summary)

    def test_runs_accumulate_so_prompt_versions_compare_side_by_side(self):
        job, questions = live_eval.sample()
        live_eval.save(self.out, "lecture.pdf", dict(job, promptVersion="v1"), questions)
        live_eval.save(self.out, "lecture.pdf", job, questions)
        self.assertTrue(os.path.exists(os.path.join(self.out, "lecture.v1.csv")))
        self.assertTrue(os.path.exists(os.path.join(self.out, "lecture.v2.csv")))
        records = json.loads(self.read("runs.json"))
        self.assertEqual([r["promptVersion"] for r in records], ["v1", "v2"])
        summary = self.read("summary.md")
        self.assertIn("| lecture.pdf | LIVE | v1 |", summary)
        self.assertIn("| lecture.pdf | LIVE | v2 |", summary)

    def test_quality_metrics(self):
        base = {"text": "프로세스란 무엇인가?", "options": ["가", "나나", "다", "라"], "correctIndex": 1}
        metrics = live_eval.quality_metrics(
            [base, dict(base, text="프로세스란 무엇인가 ?"), dict(base, text="전혀 다른 질문", correctIndex=0)]
        )
        self.assertEqual(metrics["positions"], [1, 2, 0, 0])
        self.assertEqual(metrics["correctIsLongest"], 2)
        self.assertEqual(metrics["nearDuplicatePairs"], 1)

    def test_demo_job_without_metrics_or_pages(self):
        job = {"id": "j", "mode": "DEMO", "promptVersion": None, "status": "succeeded",
               "totalChunks": 1, "completedChunks": 1, "failedChunks": 0,
               "chunks": [{"index": 0, "status": "succeeded", "error": None, "attempts": 1,
                           "durationMs": 3, "inputTokens": 0, "outputTokens": 0,
                           "parseAttempts": 0, "parseSuccess": False}]}  # fmt: skip
        question = {"id": "q", "jobId": "j", "promptVersion": None, "tagId": "process",
                    "difficulty": "EASY", "sourceStartPage": None, "sourceEndPage": None,
                    "correctIndex": 0, "text": "[샘플] 문제", "options": ["a", "b", "c", "d"],
                    "explanation": "해설"}  # fmt: skip
        self.assertEqual(live_eval.save(self.out, "demo", job, [question]), "demo")
        self.assertIn("출처 페이지: -", self.read("demo.md"))
        self.assertIn("| demo | DEMO | - | succeeded | 1/0/1 | 1 | 0.0 | 0 | 0 | 0 | - | - |", self.read("summary.md"))

    def test_requires_an_input(self):
        with self.assertRaises(SystemExit):
            live_eval.main(["--out", self.out])


if __name__ == "__main__":
    unittest.main()
