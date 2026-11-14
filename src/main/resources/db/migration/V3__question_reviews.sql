CREATE TABLE question_reviews (
 question_id VARCHAR(36) PRIMARY KEY REFERENCES questions(id),
 question_version INTEGER NOT NULL, usable BOOLEAN NOT NULL, tag_correct BOOLEAN NOT NULL,
 issue_type VARCHAR(20), memo VARCHAR(2000), updated_at VARCHAR(40) NOT NULL
);
