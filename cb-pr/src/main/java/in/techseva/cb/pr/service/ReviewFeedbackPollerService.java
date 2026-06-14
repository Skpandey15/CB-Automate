package in.techseva.cb.pr.service;

import in.techseva.cb.core.domain.ReviewFeedback;
import in.techseva.cb.core.domain.ReviewSession;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.repository.ReviewFeedbackRepository;
import in.techseva.cb.core.repository.ReviewSessionRepository;
import in.techseva.cb.core.repository.VulnerabilityRepository;
import in.techseva.cb.core.domain.VulnerabilityStatus;
import in.techseva.cb.pr.client.GitHubEnterpriseClient;
import in.techseva.cb.pr.client.GitHubEnterpriseClient.GitIssueComment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Polls PENDING ReviewSessions to detect ACCEPT/REJECT decisions made via GitHub checkboxes.
 *
 * Accept: reviewer checks [x] ✅ ACCEPT in the CB review comment.
 * Reject: reviewer checks [x] ❌ REJECT AND posts a PR comment starting with "REJECT:".
 *
 * On REJECT: closes the PR, stores ReviewFeedback, resets vuln to DETECTED for re-fix.
 */
@Service
public class ReviewFeedbackPollerService {

    private static final Logger log = LoggerFactory.getLogger(ReviewFeedbackPollerService.class);

    private final ReviewSessionRepository reviewSessionRepo;
    private final ReviewFeedbackRepository reviewFeedbackRepo;
    private final VulnerabilityRepository vulnerabilityRepo;
    private final GitHubEnterpriseClient githubClient;
    private final String repoOwner;
    private final String repoName;

    public ReviewFeedbackPollerService(
            ReviewSessionRepository reviewSessionRepo,
            ReviewFeedbackRepository reviewFeedbackRepo,
            VulnerabilityRepository vulnerabilityRepo,
            GitHubEnterpriseClient githubClient,
            @Value("${github.owner}") String repoOwner,
            @Value("${github.repo}") String repoName) {
        this.reviewSessionRepo = reviewSessionRepo;
        this.reviewFeedbackRepo = reviewFeedbackRepo;
        this.vulnerabilityRepo = vulnerabilityRepo;
        this.githubClient = githubClient;
        this.repoOwner = repoOwner;
        this.repoName = repoName;
    }

    @Scheduled(fixedDelayString = "${review.poll-interval-ms:60000}", initialDelay = 30000)
    public synchronized void pollPendingReviews() {
        List<ReviewSession> pending = reviewSessionRepo.findByStatus("PENDING");
        if (pending.isEmpty()) {
            log.debug("No PENDING review sessions");
            return;
        }
        log.info("Checking {} PENDING review session(s) for ACCEPT/REJECT decisions", pending.size());

        for (ReviewSession session : pending) {
            try {
                processSession(session);
            } catch (Exception e) {
                log.error("Error processing review session id={}: {}", session.id(), e.getMessage(), e);
            }
        }
    }

    private void processSession(ReviewSession session) {
        String commentBody = githubClient.getIssueCommentBody(
                repoOwner, repoName, session.githubCommentId());

        if (commentBody == null || commentBody.isBlank()) {
            log.debug("Review comment body empty for session id={}", session.id());
            return;
        }

        boolean accepted = isAccepted(commentBody);
        boolean rejected = isRejected(commentBody);

        if (!accepted && !rejected) {
            log.debug("No decision yet for session id={} (vuln={})", session.id(), session.vulnerabilityId());
            return;
        }

        if (rejected) {
            log.info("REJECT detected for session id={} vuln={}", session.id(), session.vulnerabilityId());
            String reason = extractRejectionReason(session.prNumber());
            handleRejection(session, reason);
        } else {
            log.info("ACCEPT detected for session id={} vuln={}", session.id(), session.vulnerabilityId());
            reviewSessionRepo.save(session.withStatus("ACCEPTED"));
        }
    }

    private boolean isAccepted(String body) {
        // GitHub stores [x] when checkbox is checked
        return body.contains("[x] ✅ **ACCEPT**") || body.contains("[x] ✅ ACCEPT")
                || body.contains("[X] ✅ **ACCEPT**") || body.contains("[X] ✅ ACCEPT");
    }

    private boolean isRejected(String body) {
        return body.contains("[x] ❌ **REJECT**") || body.contains("[x] ❌ REJECT")
                || body.contains("[X] ❌ **REJECT**") || body.contains("[X] ❌ REJECT");
    }

    /**
     * Looks for a PR comment beginning with "REJECT:" to capture the reviewer's reason.
     * Falls back to a generic message if no such comment exists.
     */
    private String extractRejectionReason(int prNumber) {
        try {
            List<GitIssueComment> comments = githubClient.getPRIssueComments(
                    repoOwner, repoName, prNumber);
            for (GitIssueComment c : comments) {
                if (c.body() != null && c.body().trim().toUpperCase().startsWith("REJECT:")) {
                    String reason = c.body().trim().substring("REJECT:".length()).trim();
                    log.info("Rejection reason from PR comment: {}", reason);
                    return reason;
                }
            }
        } catch (Exception e) {
            log.warn("Could not fetch PR comments for rejection reason: {}", e.getMessage());
        }
        return "Reviewer rejected the fix via checkbox (no specific reason provided)";
    }

    private void handleRejection(ReviewSession session, String reason) {
        // Save rejection feedback so cb-agent can include it in the re-fix prompt
        ReviewFeedback feedback = new ReviewFeedback(
                null,
                session.vulnerabilityId(),
                session.prNumber(),
                reason,
                false,
                Instant.now()
        );
        reviewFeedbackRepo.save(feedback);
        log.info("Saved rejection feedback for vuln={}: {}", session.vulnerabilityId(), reason);

        // Close the rejected PR
        githubClient.closePR(repoOwner, repoName, session.prNumber());

        // Reset vulnerability to DETECTED so cb-agent picks it up again
        Optional<Vulnerability> vulnOpt = vulnerabilityRepo.findById(session.vulnerabilityId());
        if (vulnOpt.isPresent()) {
            Vulnerability vuln = vulnOpt.get();
            Vulnerability reset = vuln.incrementRetryCount().withStatus(VulnerabilityStatus.DETECTED);
            vulnerabilityRepo.save(reset);
            log.info("Vuln {} reset to DETECTED (retryCount={}) for re-fix", vuln.id(), reset.retryCount());
        } else {
            log.warn("Vulnerability {} not found for rejection reset", session.vulnerabilityId());
        }

        reviewSessionRepo.save(session.withStatus("REJECTED"));
    }
}
