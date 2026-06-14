package in.techseva.cb.agent.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed cache for LLM prompt/response pairs and PR diffs.
 * Keys are content-addressed (SHA-256 of prompt + model).
 */
@Component
public class RedisPromptCache {

    private static final Logger log = LoggerFactory.getLogger(RedisPromptCache.class);
    private static final Duration PROMPT_TTL = Duration.ofHours(24);
    private static final Duration PR_TTL = Duration.ofHours(4);
    private static final String PROMPT_PREFIX = "cb:prompt:";
    private static final String PR_PREFIX = "cb:pr:";

    private final StringRedisTemplate redis;

    public RedisPromptCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Optional<String> getPromptResponse(String model, String promptHash) {
        String key = PROMPT_PREFIX + model + ":" + promptHash;
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            log.debug("Prompt cache HIT for model={} hash={}", model, promptHash);
        }
        return Optional.ofNullable(cached);
    }

    public void putPromptResponse(String model, String promptHash, String response) {
        String key = PROMPT_PREFIX + model + ":" + promptHash;
        redis.opsForValue().set(key, response, PROMPT_TTL);
        log.debug("Prompt cache SET model={} hash={} ttl={}h", model, promptHash, PROMPT_TTL.toHours());
    }

    public Optional<String> getPrCache(String vulnId) {
        String key = PR_PREFIX + vulnId;
        return Optional.ofNullable(redis.opsForValue().get(key));
    }

    public void putPrCache(String vulnId, String prJson) {
        String key = PR_PREFIX + vulnId;
        redis.opsForValue().set(key, prJson, PR_TTL);
    }

    public void evictPrCache(String vulnId) {
        redis.delete(PR_PREFIX + vulnId);
    }

    public static String hashPrompt(String prompt) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(prompt.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return String.valueOf(prompt.hashCode());
        }
    }
}
