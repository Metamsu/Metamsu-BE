package dev.recall;

import static dev.recall.Models.*;
import static dev.recall.Questions.fields;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class Reviews {
  final JdbcTemplate db;
  final TransactionTemplate tx;
  final Questions questions;
  final Json json;
  final Clock clock;
  final double targetUsable, targetTag;

  Reviews(
      JdbcTemplate db,
      TransactionTemplate tx,
      Questions questions,
      Json json,
      Clock clock,
      @Value("${recall.target-usable-rate}") double targetUsable,
      @Value("${recall.target-tag-rate}") double targetTag) {
    this.db = db;
    this.tx = tx;
    this.questions = questions;
    this.json = json;
    this.clock = clock;
    this.targetUsable = targetUsable;
    this.targetTag = targetTag;
  }

  Map<String, Object> save(String learner, String id, ReviewRequest request) {
    if (request.usable() && request.issueType() != null)
      throw ApiException.bad("사용 가능한 문항에는 문제 유형을 지정할 수 없습니다.");
    String memo = request.memo() == null || request.memo().isBlank() ? null : request.memo().strip();
    return tx.execute(
        status -> {
          questions.lock(learner);
          var q = questions.get(learner, id, "manage");
          if (!q.get("version").equals(request.version()))
            throw ApiException.conflict("문제가 변경되었습니다. 새로고침해 주세요.");
          db.update("DELETE FROM question_reviews WHERE question_id=?", id);
          db.update(
              "INSERT INTO question_reviews(question_id,question_version,usable,tag_correct,issue_type,memo,updated_at) VALUES (?,?,?,?,?,?,?)",
              id,
              request.version(),
              request.usable(),
              request.tagCorrect(),
              request.issueType() == null ? null : request.issueType().name(),
              memo,
              Instant.now(clock).toString());
          return questions.get(learner, id, "manage");
        });
  }

  void delete(String learner, String id) {
    tx.executeWithoutResult(
        status -> {
          questions.lock(learner);
          questions.get(learner, id, "manage");
          db.update("DELETE FROM question_reviews WHERE question_id=?", id);
        });
  }

  private static final String REVIEWED =
      " FROM questions q LEFT JOIN question_reviews r ON r.question_id=q.id AND r.question_version=q.version";

  Map<String, Object> summary(String learner, String jobId, String promptVersion) {
    String sql =
        "SELECT q.tag_id,q.difficulty,r.usable,r.tag_correct,r.issue_type"
            + REVIEWED
            + " WHERE q.learner_id=? AND q.deleted=FALSE";
    List<Object> args = new ArrayList<>();
    args.add(learner);
    if (jobId != null) {
      sql += " AND q.job_id=?";
      args.add(jobId);
    }
    if (promptVersion != null) {
      sql += " AND q.prompt_version=?";
      args.add(promptVersion);
    }
    long[] total = new long[4];
    Map<String, Long> issues = new LinkedHashMap<>();
    for (var type : IssueType.values()) issues.put(type.name(), 0L);
    Map<String, long[]> byTag = new LinkedHashMap<>(), byDifficulty = new LinkedHashMap<>();
    var tags = questions.tags();
    for (var tag : tags) byTag.put((String) tag.get("id"), new long[4]);
    for (var difficulty : Difficulty.values()) byDifficulty.put(difficulty.name(), new long[4]);
    db.query(
        sql,
        r -> {
          Boolean usable = (Boolean) r.getObject("usable");
          boolean tagCorrect = usable != null && r.getBoolean("tag_correct");
          for (long[] group :
              List.of(
                  total,
                  byTag.get(r.getString("tag_id")),
                  byDifficulty.get(r.getString("difficulty")))) {
            group[0]++;
            if (usable == null) continue;
            group[1]++;
            if (usable) group[2]++;
            if (tagCorrect) group[3]++;
          }
          if (r.getString("issue_type") != null) issues.merge(r.getString("issue_type"), 1L, Long::sum);
        },
        args.toArray());
    var result = counts(total);
    result.put("issues", issues);
    result.put(
        "byTag",
        tags.stream()
            .map(
                tag -> {
                  var row = fields("tagId", tag.get("id"), "tagName", tag.get("name"));
                  row.putAll(counts(byTag.get((String) tag.get("id"))));
                  return row;
                })
            .toList());
    result.put(
        "byDifficulty",
        byDifficulty.entrySet().stream()
            .map(
                entry -> {
                  var row = fields("difficulty", entry.getKey());
                  row.putAll(counts(entry.getValue()));
                  return row;
                })
            .toList());
    result.put("targetUsableRate", targetUsable);
    result.put("targetTagCorrectRate", targetTag);
    result.put("usableTargetMet", total[1] == 0 ? null : rate(total[1], total[2]) >= targetUsable);
    result.put("tagTargetMet", total[1] == 0 ? null : rate(total[1], total[3]) >= targetTag);
    return result;
  }

  // counts: {questions, reviewed, usable, tagCorrect}; rates are percentages of reviewed questions.
  private Map<String, Object> counts(long[] c) {
    return fields(
        "questionCount",
        c[0],
        "reviewedCount",
        c[1],
        "usableCount",
        c[2],
        "usableRate",
        rate(c[1], c[2]),
        "tagCorrectCount",
        c[3],
        "tagCorrectRate",
        rate(c[1], c[3]));
  }

  private double rate(long total, long part) {
    return total == 0 ? 0 : Math.round(10000.0 * part / total) / 100.0;
  }

  byte[] csv(String learner) {
    StringBuilder out = new StringBuilder("﻿");
    row(
        out,
        "question_id",
        "job_id",
        "mode",
        "prompt_version",
        "document",
        "source_start_page",
        "source_end_page",
        "tag_id",
        "difficulty",
        "text",
        "option_1",
        "option_2",
        "option_3",
        "option_4",
        "correct_option",
        "explanation",
        "usable",
        "tag_correct",
        "issue_type",
        "memo",
        "reviewed_at");
    for (var q : questions.list(learner, "manage", null)) {
      JsonNode options = (JsonNode) q.get("options");
      @SuppressWarnings("unchecked")
      Map<String, Object> review = (Map<String, Object>) q.get("review");
      row(
          out,
          q.get("id"),
          q.get("jobId"),
          q.get("mode"),
          q.get("promptVersion"),
          q.get("documentName"),
          q.get("sourceStartPage"),
          q.get("sourceEndPage"),
          q.get("tagId"),
          q.get("difficulty"),
          q.get("text"),
          options.get(0).asText(),
          options.get(1).asText(),
          options.get(2).asText(),
          options.get(3).asText(),
          (int) q.get("correctIndex") + 1,
          q.get("explanation"),
          review == null ? null : yn(review.get("usable")),
          review == null ? null : yn(review.get("tagCorrect")),
          review == null ? null : review.get("issueType"),
          review == null ? null : review.get("memo"),
          review == null ? null : review.get("updatedAt"));
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static String yn(Object value) {
    return Boolean.TRUE.equals(value) ? "Y" : "N";
  }

  private static void row(StringBuilder out, Object... cells) {
    for (int i = 0; i < cells.length; i++) {
      if (i > 0) out.append(',');
      String cell = cells[i] == null ? "" : cells[i].toString();
      // Question text comes from PDFs and the model; keep spreadsheets from running it as a formula.
      if (!cell.isEmpty() && "=+-@\t\r".indexOf(cell.charAt(0)) >= 0) cell = "'" + cell;
      out.append('"').append(cell.replace("\"", "\"\"")).append('"');
    }
    out.append("\r\n");
  }
}
