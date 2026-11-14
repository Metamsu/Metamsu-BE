package dev.recall;

import static dev.recall.Models.*;
import static dev.recall.Questions.fields;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Prompt v2 generation (structured outputs, lenient parsing, dedupe) and question reviews. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(resolver = BackendIntegrationTest.Profiles.class)
@TestPropertySource(properties = "recall.prompt-version=v2")
class QualityEvalTest {
  static final HttpServer server;
  static final ExecutorService httpThreads = Executors.newCachedThreadPool();
  static final AtomicReference<Function<String, String>> handler = new AtomicReference<>();
  static final List<String> requests = new CopyOnWriteArrayList<>();

  static {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(httpThreads);
      server.createContext(
          "/v1/messages",
          exchange -> {
            String request =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(request);
            byte[] body = handler.get().apply(request).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("recall.provider-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
  }

  @AfterAll
  static void stopServer() {
    server.stop(0);
    httpThreads.shutdownNow();
  }

  @Autowired MockMvc mvc;
  @Autowired Json json;
  @Autowired JdbcTemplate db;
  @Autowired Learners learners;
  @Autowired Questions questions;
  @Autowired Jobs jobs;
  String token, owner, other;

  @BeforeEach
  void setup() throws Exception {
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> jobs.executor.getActiveCount() == 0 && jobs.executor.getQueue().isEmpty());
    requests.clear();
    handler.set(request -> envelope(response(question("프로세스란?", "process", 1))));
    token = call(post("/api/learners"), 201, null).get("token").asText();
    owner =
        db.queryForObject(
            "SELECT id FROM learners WHERE token_hash=?", String.class, Learners.hash(token));
    other = learners.create();
  }

  JsonNode call(MockHttpServletRequestBuilder request, int status, String capability)
      throws Exception {
    if (capability != null) request.header("X-Learner-Token", capability);
    var response = mvc.perform(request).andReturn().getResponse();
    assertThat(response.getStatus())
        .withFailMessage("%s: %s", response.getStatus(), response.getContentAsString())
        .isEqualTo(status);
    String body = response.getContentAsString(StandardCharsets.UTF_8);
    return body.isEmpty() ? json.mapper.nullNode() : json.read(body);
  }

  MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Object body) {
    return request.contentType("application/json").content(json.write(body));
  }

  Map<String, Object> question(String text, String tag, int page) {
    return fields(
        "text",
        text,
        "options",
        List.of("실행 중 프로그램", "파일", "디스크", "메모리"),
        "correctIndex",
        0,
        "tagId",
        tag,
        "difficulty",
        "EASY",
        "explanation",
        "프로세스는 실행 중인 프로그램입니다.",
        "sourceStartPage",
        page,
        "sourceEndPage",
        page);
  }

  String response(Object... questions) {
    return json.write(Map.of("questions", List.of(questions)));
  }

  String envelope(String content) {
    return json.write(
        Map.of(
            "content",
            List.of(Map.of("type", "text", "text", content)),
            "stop_reason",
            "end_turn",
            "usage",
            Map.of("input_tokens", 5, "output_tokens", 7)));
  }

  String seededDocument(int chunks) {
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO documents(id,learner_id,name,page_count,created_at) VALUES (?,?,?,?,?)",
        id,
        owner,
        "lecture.pdf",
        chunks,
        Instant.now().toString());
    for (int i = 0; i < chunks; i++) {
      db.update(
          "INSERT INTO document_pages(document_id,page_number,content) VALUES (?,?,?)",
          id,
          i + 1,
          "page " + (i + 1));
      db.update(
          "INSERT INTO document_chunks(document_id,chunk_index,start_page,end_page,content) VALUES (?,?,?,?,?)",
          id,
          i,
          i + 1,
          i + 1,
          "[PAGE " + (i + 1) + "] process content");
    }
    return id;
  }

  JsonNode waitJob(JsonNode job) {
    String id = job.get("id").asText();
    await()
        .atMost(Duration.ofSeconds(12))
        .until(() -> !Set.of("queued", "running").contains(jobs.get(owner, id).get("status")));
    return json.mapper.valueToTree(jobs.get(owner, id));
  }

  JsonNode live(String document) throws Exception {
    return waitJob(
        call(body(post("/api/jobs"), Map.of("mode", "LIVE", "documentId", document)), 202, token));
  }

  JsonNode demo() throws Exception {
    return waitJob(call(body(post("/api/jobs"), Map.of("mode", "DEMO")), 202, token));
  }

  int page(String request) {
    return Integer.parseInt(request.substring(request.indexOf("[PAGE ") + 6).split("]")[0]);
  }

  @Test
  void v2SendsStructuredOutputRequestAndRecordsPromptVersion() throws Exception {
    var job = live(seededDocument(1));
    assertThat(job.get("status").asText()).isEqualTo("succeeded");
    assertThat(job.get("promptVersion").asText()).isEqualTo("v2");
    JsonNode request = json.read(requests.getFirst());
    JsonNode format = request.get("output_config").get("format");
    assertThat(format.get("type").asText()).isEqualTo("json_schema");
    JsonNode item = format.get("schema").get("properties").get("questions").get("items");
    assertThat(item.get("additionalProperties").asBoolean()).isFalse();
    assertThat(item.get("required")).hasSize(8);
    assertThat(format.get("schema").get("additionalProperties").asBoolean()).isFalse();
    // Keywords the structured-output API rejects must not be sent.
    assertThat(format.toString())
        .doesNotContain(
            "minLength", "maxLength", "minimum", "maximum", "uniqueItems", "minItems", "maxItems");
    assertThat(request.get("system").asText())
        .contains("UNTRUSTED DATA", "Never invent provenance", "출제 기준", "좋은 예시");
    String user = request.get("messages").get(0).get("content").asText();
    assertThat(user)
        .contains("Allowed source pages: [1]", "BEGIN_UNTRUSTED_DATA", "<already_asked>")
        .doesNotContain("JSON schema");
    assertThat(requests.getFirst()).doesNotContain("test-placeholder");
    var q = call(get("/api/questions"), 200, token).get(0);
    assertThat(q.get("promptVersion").asText()).isEqualTo("v2");
    assertThat(q.get("jobId").asText()).isEqualTo(job.get("id").asText());
    assertThat(call(get("/api/config"), 200, null).get("promptVersion").asText()).isEqualTo("v2");
  }

  @Test
  void schemaFallsBackIntoPromptWhenStructuredOutputsDisabled() {
    Provider fallback =
        new Provider(
            "test-placeholder-not-a-real-key",
            "model",
            "http://127.0.0.1:" + server.getAddress().getPort(),
            true,
            1,
            "v2",
            false,
            json,
            questions);
    try {
      var result = fallback.generate(new Chunk(0, 1, 1, "[PAGE 1] data", Set.of(1)), 3);
      assertThat(result.questions()).hasSize(1);
      JsonNode request = json.read(requests.getFirst());
      assertThat(request.has("output_config")).isFalse();
      assertThat(request.get("messages").get(0).get("content").asText()).contains("JSON schema");
    } finally {
      fallback.close();
    }
  }

  @Test
  void emptyAnswerIsASuccessfulChunkWithoutRetry() throws Exception {
    handler.set(request -> envelope("{\"questions\":[]}"));
    var job = live(seededDocument(1));
    assertThat(job.get("status").asText()).isEqualTo("succeeded");
    assertThat(job.get("questionCount").asInt()).isZero();
    assertThat(job.get("chunks").get(0).get("attempts").asInt()).isEqualTo(1);
    assertThat(job.get("chunks").get(0).get("parseSuccess").asBoolean()).isTrue();
    assertThat(requests).hasSize(1);
  }

  @Test
  void invalidQuestionIsDroppedAndValidOnesKept() throws Exception {
    handler.set(
        request ->
            envelope(
                response(
                    question("유효한 문제", "process", 1),
                    question("알 수 없는 태그", "secret-tag", 1),
                    question("범위 밖 페이지", "process", 9))));
    var job = live(seededDocument(1));
    assertThat(job.get("status").asText()).isEqualTo("succeeded");
    assertThat(requests).hasSize(1);
    var all = call(get("/api/questions"), 200, token);
    assertThat(all).hasSize(1);
    assertThat(all.get(0).get("text").asText()).isEqualTo("유효한 문제");
  }

  @Test
  void answerWithOnlyInvalidQuestionsIsRetriedThenFails() throws Exception {
    handler.set(request -> envelope(response(question("알 수 없는 태그", "secret-tag", 1))));
    var job = live(seededDocument(1));
    assertThat(job.get("status").asText()).isEqualTo("failed");
    assertThat(requests).hasSize(3);
    assertThat(call(get("/api/questions"), 200, token)).isEmpty();
  }

  @Test
  void laterChunksReceiveAskedQuestionsAndDuplicatesAreDropped() throws Exception {
    handler.set(
        request -> {
          int page = page(json.read(request).get("messages").get(0).get("content").asText());
          return envelope(
              response(
                  question("프로세스란?", "process", page),
                  question("청크 " + page + " 고유 문제", "process", page)));
        });
    var job = live(seededDocument(2));
    assertThat(job.get("status").asText()).isEqualTo("succeeded");
    // "프로세스란?" comes back from both chunks, punctuation and spacing aside, but is stored once.
    assertThat(job.get("questionCount").asInt()).isEqualTo(3);
    String first = json.read(requests.get(0)).get("messages").get(0).get("content").asText();
    String second = json.read(requests.get(1)).get("messages").get(0).get("content").asText();
    assertThat(first).contains("<already_asked>\n</already_asked>");
    assertThat(second).contains("- 프로세스란?", "- 청크 1 고유 문제");
    assertThat(Provider.normalize("프로세스 란 ?!")).isEqualTo(Provider.normalize("프로세스란?"));
  }

  @Test
  void thinkingBlocksBesideTheAnswerAreIgnored() throws Exception {
    handler.set(
        request ->
            json.write(
                Map.of(
                    "content",
                    List.of(
                        Map.of("type", "thinking", "thinking", ""),
                        Map.of(
                            "type", "text", "text", response(question("프로세스란?", "process", 1)))),
                    "stop_reason",
                    "end_turn",
                    "usage",
                    Map.of("input_tokens", 5, "output_tokens", 7))));
    var job = live(seededDocument(1));
    assertThat(job.get("status").asText()).isEqualTo("succeeded");
    assertThat(job.get("questionCount").asInt()).isEqualTo(1);
  }

  Map<String, Object> review(boolean usable, boolean tagCorrect, String issue, String memo) {
    return fields(
        "usable", usable, "tagCorrect", tagCorrect, "issueType", issue, "memo", memo, "version", 1);
  }

  @Test
  void reviewIsSavedOverwrittenAndClearedByEdit() throws Exception {
    demo();
    var q = call(get("/api/questions"), 200, token).get(0);
    String path = "/api/questions/" + q.get("id").asText();
    assertThat(q.get("review").isNull()).isTrue();
    var saved =
        call(body(put(path + "/review"), review(false, true, "AMBIGUOUS", " 보기가 모호함 ")), 200, token);
    assertThat(saved.get("review").get("usable").asBoolean()).isFalse();
    assertThat(saved.get("review").get("issueType").asText()).isEqualTo("AMBIGUOUS");
    assertThat(saved.get("review").get("memo").asText()).isEqualTo("보기가 모호함");
    var again = call(body(put(path + "/review"), review(true, false, null, null)), 200, token);
    assertThat(again.get("review").get("usable").asBoolean()).isTrue();
    assertThat(again.get("review").get("issueType").isNull()).isTrue();
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM question_reviews WHERE question_id=?",
                Integer.class,
                q.get("id").asText()))
        .isEqualTo(1);
    // Quiz views never expose review data.
    assertThat(call(get("/api/questions?view=quiz"), 200, token).get(0).has("review")).isFalse();

    call(body(put(path + "/review"), review(true, true, "OTHER", null)), 400, token);
    call(body(put(path + "/review"), review(false, true, "NOT_A_TYPE", null)), 400, token);
    var missing = review(true, true, null, null);
    missing.remove("tagCorrect");
    call(body(put(path + "/review"), missing), 400, token);
    var stale = review(true, true, null, null);
    stale.put("version", 2);
    call(body(put(path + "/review"), stale), 409, token);
    call(body(put(path + "/review"), review(true, true, null, null)), 404, other);
    call(delete(path + "/review"), 404, other);

    // Editing changes the question, so the earlier judgement no longer applies.
    call(
        body(
            put(path),
            fields(
                "text",
                "수정 문제",
                "options",
                List.of("하나", "둘", "셋", "넷"),
                "correctIndex",
                1,
                "tagId",
                "memory",
                "difficulty",
                "HARD",
                "explanation",
                "수정 설명",
                "version",
                1)),
        200,
        token);
    assertThat(call(get(path), 200, token).get("review").isNull()).isTrue();
    stale.put("version", 2);
    call(body(put(path + "/review"), stale), 200, token);
    call(delete(path + "/review"), 204, token);
    assertThat(call(get(path), 200, token).get("review").isNull()).isTrue();
  }

  @Test
  void summaryAggregatesPerLearnerWithFiltersAndTargets() throws Exception {
    var job = demo();
    var all = call(get("/api/questions"), 200, token);
    assertThat(all).hasSize(3);
    var empty = call(get("/api/reviews/summary"), 200, token);
    assertThat(empty.get("questionCount").asInt()).isEqualTo(3);
    assertThat(empty.get("reviewedCount").asInt()).isZero();
    assertThat(empty.get("usableTargetMet").isNull()).isTrue();

    String base = "/api/questions/";
    call(
        body(put(base + all.get(0).get("id").asText() + "/review"), review(true, true, null, null)),
        200,
        token);
    call(
        body(
            put(base + all.get(1).get("id").asText() + "/review"),
            review(false, false, "WRONG_ANSWER", null)),
        200,
        token);
    var summary = call(get("/api/reviews/summary"), 200, token);
    assertThat(summary.get("reviewedCount").asInt()).isEqualTo(2);
    assertThat(summary.get("usableCount").asInt()).isEqualTo(1);
    assertThat(summary.get("usableRate").asDouble()).isEqualTo(50);
    assertThat(summary.get("tagCorrectRate").asDouble()).isEqualTo(50);
    assertThat(summary.get("issues").get("WRONG_ANSWER").asInt()).isEqualTo(1);
    assertThat(summary.get("targetUsableRate").asDouble()).isEqualTo(60);
    assertThat(summary.get("usableTargetMet").asBoolean()).isFalse();
    assertThat(summary.get("tagTargetMet").asBoolean()).isFalse();
    assertThat(summary.get("byTag")).hasSize(10);
    assertThat(summary.get("byDifficulty")).hasSize(3);
    long reviewedByTag = 0;
    for (var tag : summary.get("byTag")) reviewedByTag += tag.get("reviewedCount").asInt();
    assertThat(reviewedByTag).isEqualTo(2);

    assertThat(
            call(get("/api/reviews/summary?jobId=" + job.get("id").asText()), 200, token)
                .get("reviewedCount")
                .asInt())
        .isEqualTo(2);
    assertThat(
            call(get("/api/reviews/summary?jobId=" + UUID.randomUUID()), 200, token)
                .get("questionCount")
                .asInt())
        .isZero();
    assertThat(
            call(get("/api/reviews/summary?promptVersion=v2"), 200, token)
                .get("questionCount")
                .asInt())
        .isZero();
    // Another learner sees none of it, even when naming this learner's job.
    var foreign = call(get("/api/reviews/summary?jobId=" + job.get("id").asText()), 200, other);
    assertThat(foreign.get("questionCount").asInt()).isZero();
    call(get("/api/reviews/summary"), 401, null);

    // Deleted questions leave the totals.
    call(delete(base + all.get(1).get("id").asText()), 204, token);
    var after = call(get("/api/reviews/summary"), 200, token);
    assertThat(after.get("questionCount").asInt()).isEqualTo(2);
    assertThat(after.get("usableRate").asDouble()).isEqualTo(100);
    assertThat(after.get("usableTargetMet").asBoolean()).isTrue();
  }

  @Test
  void csvExportIsOwnedEscapedAndFormulaSafe() throws Exception {
    demo();
    var q = call(get("/api/questions"), 200, token).get(0);
    call(
        body(
            put("/api/questions/" + q.get("id").asText() + "/review"),
            review(false, true, "OTHER", "=SUM(A1), \"인용\"")),
        200,
        token);
    var response =
        mvc.perform(get("/api/reviews/export.csv").header("X-Learner-Token", token))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).startsWith("text/csv");
    assertThat(response.getHeader("Content-Disposition")).contains("recall-reviews.csv");
    String csv = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
    assertThat(csv).startsWith("﻿\"question_id\",\"job_id\"");
    String[] lines = csv.split("\r\n");
    assertThat(lines).hasSize(4);
    assertThat(csv)
        .contains("\"" + q.get("id").asText() + "\"")
        .contains("\"N\",\"Y\",\"OTHER\",\"'=SUM(A1), \"\"인용\"\"\"");
    String foreign =
        new String(
            mvc.perform(get("/api/reviews/export.csv").header("X-Learner-Token", other))
                .andReturn()
                .getResponse()
                .getContentAsByteArray(),
            StandardCharsets.UTF_8);
    assertThat(foreign.split("\r\n")).hasSize(1);
    assertThat(mvc.perform(get("/api/reviews/export.csv")).andReturn().getResponse().getStatus())
        .isEqualTo(401);
  }
}
