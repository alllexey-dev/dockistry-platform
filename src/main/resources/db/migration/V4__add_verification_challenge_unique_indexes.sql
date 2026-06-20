CREATE UNIQUE INDEX idx_email_verification_challenges_user
    ON email_verification_challenges (user_id)
    WHERE email_verification_challenges.consumed_at IS NULL
        AND email_verification_challenges.superseded_at IS NULL;

CREATE UNIQUE INDEX idx_email_verification_challenges_link_code_hash
    ON email_verification_challenges (link_code_hash);

CREATE INDEX idx_email_verification_throttles_user_blocked_until
    ON email_verification_throttles (user_id, blocked_until);

CREATE UNIQUE INDEX idx_email_send_jobs_email_verification_active_user
    ON email_send_jobs ((job_context ->> 'userId'))
    WHERE job_type = 'EMAIL_VERIFICATION'
        AND status IN ('PENDING', 'PROCESSING', 'FAILED');
