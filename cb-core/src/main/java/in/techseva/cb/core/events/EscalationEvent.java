package in.techseva.cb.core.events;

import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.domain.Vulnerability;
import org.springframework.context.ApplicationEvent;

public class EscalationEvent extends ApplicationEvent {

    private final Vulnerability vulnerability;
    private final Fix fix;
    private final String reason;

    public EscalationEvent(Object source, Vulnerability vulnerability, Fix fix, String reason) {
        super(source);
        this.vulnerability = vulnerability;
        this.fix = fix;
        this.reason = reason;
    }

    public Vulnerability getVulnerability() { return vulnerability; }
    public Fix getFix() { return fix; }
    public String getReason() { return reason; }
}
