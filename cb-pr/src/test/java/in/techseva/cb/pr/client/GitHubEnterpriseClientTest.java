package in.techseva.cb.pr.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GitHubEnterpriseClientTest {

    private MockRestServiceServer mockServer;

    private GitHubEnterpriseClient clientWithMockedTransport() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.test");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient mocked = builder.build();
        return new GitHubEnterpriseClient(mocked, mocked, "main");
    }

    @Test
    void createPullRequest_parsesResponse() {
        var client = clientWithMockedTransport();
        mockServer.expect(requestTo("https://api.github.test/repos/acme/widgets/pulls"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"number\":42,\"html_url\":\"https://github.com/acme/widgets/pull/42\",\"state\":\"open\"}",
                        MediaType.APPLICATION_JSON));

        var pr = client.createPullRequest("acme", "widgets", "cb/fix-1", "title", "body");

        assertThat(pr.number()).isEqualTo(42);
        assertThat(pr.htmlUrl()).isEqualTo("https://github.com/acme/widgets/pull/42");
        mockServer.verify();
    }

    @Test
    void requestReviewers_swallowsFailure() {
        // Javadoc on requestReviewers: "Handles partial failures gracefully
        // (logs and continues)" -- pin that it never propagates.
        var client = clientWithMockedTransport();
        mockServer.expect(requestTo("https://api.github.test/repos/acme/widgets/pulls/7/requested_reviewers"))
                .andRespond(withServerError());

        client.requestReviewers("acme", "widgets", 7, List.of("reviewer1"));

        mockServer.verify();
    }

    @Test
    void requestReviewers_emptyList_makesNoCall() {
        var client = clientWithMockedTransport();

        client.requestReviewers("acme", "widgets", 7, List.of());

        mockServer.verify(); // no expectations set -> any actual call would fail fast
    }

    @Test
    void getPRDiff_onFailure_returnsEmptyString() {
        var client = clientWithMockedTransport();
        mockServer.expect(requestTo("https://api.github.test/repos/acme/widgets/pulls/7"))
                .andRespond(withServerError());

        String diff = client.getPRDiff("acme", "widgets", 7);

        assertThat(diff).isEmpty();
    }

    @Test
    void closePR_swallowsFailure() {
        var client = clientWithMockedTransport();
        mockServer.expect(requestTo("https://api.github.test/repos/acme/widgets/pulls/7"))
                .andRespond(withServerError());

        client.closePR("acme", "widgets", 7);

        mockServer.verify();
    }
}
