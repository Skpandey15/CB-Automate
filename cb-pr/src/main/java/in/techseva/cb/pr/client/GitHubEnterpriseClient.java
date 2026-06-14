package in.techseva.cb.pr.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class GitHubEnterpriseClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubEnterpriseClient.class);

    private final RestClient restClient;
    private final RestClient diffClient;
    private final String defaultBranch;

    public GitHubEnterpriseClient(
            @Value("${github.api-url:https://api.github.com}") String apiUrl,
            @Value("${github.token}") String token,
            @Value("${github.default-branch:main}") String defaultBranch) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);   // 10s connect timeout
        factory.setReadTimeout(30_000);      // 30s read timeout

        this.restClient = RestClient.builder()
                .baseUrl(apiUrl)
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultHeader("Accept", "application/vnd.github.v3+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
        // Separate client for diff requests (different Accept header)
        this.diffClient = RestClient.builder()
                .baseUrl(apiUrl)
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultHeader("Accept", "application/vnd.github.diff")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
        this.defaultBranch = defaultBranch;
    }

    @CircuitBreaker(name = "github-api", fallbackMethod = "createBranchFallback")
    @Retry(name = "github-api")
    public void createBranch(String owner, String repo, String branchName) {
        GitRef baseRef = restClient.get()
                .uri("/repos/{owner}/{repo}/git/ref/heads/{branch}", owner, repo, defaultBranch)
                .retrieve()
                .body(GitRef.class);

        if (baseRef == null) throw new RuntimeException("Could not get base branch SHA");

        Map<String, String> body = Map.of(
                "ref", "refs/heads/" + branchName,
                "sha", baseRef.object().sha());
        restClient.post()
                .uri("/repos/{owner}/{repo}/git/refs", owner, repo)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();

        log.info("Created branch {} on {}/{}", branchName, owner, repo);
    }

    public void createBranchFallback(String owner, String repo, String branchName, Throwable t) {
        log.error("GitHub circuit open, cannot create branch: {}", t.getMessage());
        throw new RuntimeException("GitHub API unavailable: " + t.getMessage());
    }

    @CircuitBreaker(name = "github-api")
    @Retry(name = "github-api")
    public GitPR createPullRequest(String owner, String repo, String branchName,
                                    String title, String body) {
        Map<String, Object> prBody = Map.of(
                "title", title,
                "head", branchName,
                "base", defaultBranch,
                "body", body,
                "draft", false);
        GitPR pr = restClient.post()
                .uri("/repos/{owner}/{repo}/pulls", owner, repo)
                .contentType(MediaType.APPLICATION_JSON)
                .body(prBody)
                .retrieve()
                .body(GitPR.class);
        log.info("Created PR #{} on {}/{}: {}", pr != null ? pr.number() : "?", owner, repo, title);
        return pr;
    }

    /**
     * Request reviewers on a PR. Handles partial failures gracefully (logs and continues).
     */
    public void requestReviewers(String owner, String repo, int prNumber, List<String> reviewers) {
        if (reviewers == null || reviewers.isEmpty()) return;
        try {
            Map<String, Object> body = Map.of("reviewers", reviewers);
            restClient.post()
                    .uri("/repos/{owner}/{repo}/pulls/{prNumber}/requested_reviewers",
                            owner, repo, prNumber)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Requested reviewers {} on PR #{}", reviewers, prNumber);
        } catch (Exception e) {
            log.warn("Could not request reviewers {} on PR #{}: {}", reviewers, prNumber, e.getMessage());
        }
    }

    /**
     * Fetch the unified diff for a PR (may be truncated by GitHub for large PRs).
     */
    public String getPRDiff(String owner, String repo, int prNumber) {
        try {
            return diffClient.get()
                    .uri("/repos/{owner}/{repo}/pulls/{prNumber}", owner, repo, prNumber)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.warn("Could not fetch PR diff for #{}: {}", prNumber, e.getMessage());
            return "";
        }
    }

    /**
     * Post a comment on the PR (issue-style comment — visible in the main timeline).
     * Returns the comment ID needed to poll the checkbox state later.
     */
    public Long createIssueComment(String owner, String repo, int prNumber, String body) {
        Map<String, String> reqBody = Map.of("body", body);
        GitIssueComment comment = restClient.post()
                .uri("/repos/{owner}/{repo}/issues/{prNumber}/comments", owner, repo, prNumber)
                .contentType(MediaType.APPLICATION_JSON)
                .body(reqBody)
                .retrieve()
                .body(GitIssueComment.class);
        if (comment == null) throw new RuntimeException("GitHub returned null comment");
        log.info("Posted review comment id={} on PR #{}", comment.id(), prNumber);
        return comment.id();
    }

    /**
     * Fetch the current body of an issue/PR comment (to detect checked checkboxes).
     */
    public String getIssueCommentBody(String owner, String repo, long commentId) {
        GitIssueComment comment = restClient.get()
                .uri("/repos/{owner}/{repo}/issues/comments/{commentId}", owner, repo, commentId)
                .retrieve()
                .body(GitIssueComment.class);
        return comment != null ? comment.body() : "";
    }

    /**
     * Get PR comments (non-review) to look for REJECT: command replies.
     */
    public List<GitIssueComment> getPRIssueComments(String owner, String repo, int prNumber) {
        GitIssueComment[] comments = restClient.get()
                .uri("/repos/{owner}/{repo}/issues/{prNumber}/comments?per_page=100",
                        owner, repo, prNumber)
                .retrieve()
                .body(GitIssueComment[].class);
        return comments != null ? List.of(comments) : List.of();
    }

    /**
     * Close a PR (used when rejecting the fix so the re-generated fix creates a fresh PR).
     */
    public void closePR(String owner, String repo, int prNumber) {
        try {
            Map<String, String> body = Map.of("state", "closed");
            restClient.patch()
                    .uri("/repos/{owner}/{repo}/pulls/{prNumber}", owner, repo, prNumber)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Closed PR #{} on {}/{}", prNumber, owner, repo);
        } catch (Exception e) {
            log.warn("Could not close PR #{}: {}", prNumber, e.getMessage());
        }
    }

    // ── Records ────────────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitRef(
            @JsonProperty("ref") String ref,
            @JsonProperty("object") GitObject object) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitObject(
            @JsonProperty("sha") String sha,
            @JsonProperty("type") String type) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitPR(
            @JsonProperty("number") int number,
            @JsonProperty("html_url") String htmlUrl,
            @JsonProperty("state") String state) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitIssueComment(
            @JsonProperty("id") Long id,
            @JsonProperty("body") String body,
            @JsonProperty("user") GitUser user) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitUser(
            @JsonProperty("login") String login) {}
}
