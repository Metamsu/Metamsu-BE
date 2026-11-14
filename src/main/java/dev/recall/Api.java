package dev.recall;

import static dev.recall.Models.*;

import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class Api {
  final Learners learners;
  final Questions questions;
  final Documents documents;
  final Jobs jobs;
  final Provider provider;
  final Reviews reviews;
  final String course;

  Api(
      Learners learners,
      Questions questions,
      Documents documents,
      Jobs jobs,
      Provider provider,
      Reviews reviews,
      @Value("${recall.course-name}") String course) {
    this.learners = learners;
    this.questions = questions;
    this.documents = documents;
    this.jobs = jobs;
    this.provider = provider;
    this.reviews = reviews;
    this.course = course;
  }

  @PostMapping("/learners")
  @ResponseStatus(HttpStatus.CREATED)
  Object learner() {
    return Map.of("token", learners.create());
  }

  @GetMapping("/config")
  Object config() {
    return Map.of(
        "liveEnabled",
        provider.liveEnabled(),
        "courseName",
        course,
        "timezone",
        "Asia/Seoul",
        "promptVersion",
        provider.promptVersion());
  }

  @GetMapping("/tags")
  Object tags() {
    return questions.tags();
  }

  @GetMapping("/dashboard")
  Object dashboard(@RequestAttribute("learner") String learner) {
    return questions.dashboard(learner);
  }

  @PostMapping(value = "/documents", consumes = "multipart/form-data")
  @ResponseStatus(HttpStatus.CREATED)
  Object upload(
      @RequestAttribute("learner") String learner, @RequestPart("file") MultipartFile file) {
    return documents.upload(learner, file);
  }

  @GetMapping("/documents")
  Object documents(@RequestAttribute("learner") String learner) {
    return documents.list(learner);
  }

  @PostMapping("/jobs")
  @ResponseStatus(HttpStatus.ACCEPTED)
  Object createJob(
      @RequestAttribute("learner") String learner, @Valid @RequestBody JobRequest request) {
    return jobs.create(learner, request);
  }

  @GetMapping("/jobs")
  Object jobs(@RequestAttribute("learner") String learner) {
    return jobs.list(learner);
  }

  @GetMapping("/jobs/{id}")
  Object job(@RequestAttribute("learner") String learner, @PathVariable String id) {
    return jobs.get(learner, id);
  }

  @GetMapping("/questions")
  Object questions(
      @RequestAttribute("learner") String learner,
      @RequestParam(defaultValue = "manage") String view,
      @RequestParam(required = false) String tagId) {
    return questions.list(learner, view, tagId);
  }

  @GetMapping("/questions/{id}")
  Object question(
      @RequestAttribute("learner") String learner,
      @PathVariable String id,
      @RequestParam(defaultValue = "manage") String view) {
    return questions.get(learner, id, view);
  }

  @PutMapping("/questions/{id}")
  Object edit(
      @RequestAttribute("learner") String learner,
      @PathVariable String id,
      @Valid @RequestBody EditRequest request) {
    return questions.edit(learner, id, request);
  }

  @DeleteMapping("/questions/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@RequestAttribute("learner") String learner, @PathVariable String id) {
    questions.delete(learner, id);
  }

  @PutMapping("/questions/{id}/review")
  Object review(
      @RequestAttribute("learner") String learner,
      @PathVariable String id,
      @Valid @RequestBody ReviewRequest request) {
    return reviews.save(learner, id, request);
  }

  @DeleteMapping("/questions/{id}/review")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void deleteReview(@RequestAttribute("learner") String learner, @PathVariable String id) {
    reviews.delete(learner, id);
  }

  @GetMapping("/reviews/summary")
  Object reviewSummary(
      @RequestAttribute("learner") String learner,
      @RequestParam(required = false) String jobId,
      @RequestParam(required = false) String promptVersion) {
    return reviews.summary(learner, jobId, promptVersion);
  }

  @GetMapping("/reviews/export.csv")
  ResponseEntity<byte[]> reviewExport(@RequestAttribute("learner") String learner) {
    return ResponseEntity.ok()
        .header("Content-Type", "text/csv; charset=UTF-8")
        .header("Content-Disposition", "attachment; filename=\"recall-reviews.csv\"")
        .body(reviews.csv(learner));
  }

  @PostMapping("/attempts")
  Object attempt(
      @RequestAttribute("learner") String learner, @Valid @RequestBody AttemptRequest request) {
    return questions.attempt(learner, request);
  }
}
