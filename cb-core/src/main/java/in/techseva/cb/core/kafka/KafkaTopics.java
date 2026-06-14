package in.techseva.cb.core.kafka;

public final class KafkaTopics {

    private KafkaTopics() {}

    public static final String VULNERABILITIES_DETECTED = "vulnerabilities.detected";
    public static final String FIXES_GENERATED          = "fixes.generated";
    public static final String FIXES_VALIDATED          = "fixes.validated";
    public static final String PR_CREATED               = "pr.created";
    public static final String ESCALATIONS_TRIGGERED    = "escalations.triggered";
    public static final String REVIEW_FEEDBACK          = "review.feedback";
    public static final String COST_TRACKED             = "cost.tracked";
}
