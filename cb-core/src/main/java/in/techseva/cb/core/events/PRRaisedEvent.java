package in.techseva.cb.core.events;

import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.domain.Vulnerability;
import org.springframework.context.ApplicationEvent;

public class PRRaisedEvent extends ApplicationEvent {

    private final Vulnerability vulnerability;
    private final Fix fix;

    public PRRaisedEvent(Object source, Vulnerability vulnerability, Fix fix) {
        super(source);
        this.vulnerability = vulnerability;
        this.fix = fix;
    }

    public Vulnerability getVulnerability() { return vulnerability; }
    public Fix getFix() { return fix; }
}
