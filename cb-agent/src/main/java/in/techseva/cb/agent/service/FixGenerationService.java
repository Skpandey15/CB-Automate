package in.techseva.cb.agent.service;

import in.techseva.cb.agent.agent.AgentOrchestrator;
import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.domain.FixStatus;
import in.techseva.cb.core.domain.ReviewFeedback;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.domain.VulnerabilityStatus;
import in.techseva.cb.core.events.EscalationEvent;
import in.techseva.cb.core.events.FixGeneratedEvent;
import in.techseva.cb.core.events.VulnerabilityDetectedEvent;
import in.techseva.cb.core.ontology.OntologyMapper;
import in.techseva.cb.core.repository.FixRepository;
import in.techseva.cb.core.repository.ReviewFeedbackRepository;
import in.techseva.cb.core.repository.VulnerabilityRepository;
import in.techseva.cb.core.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class FixGenerationService {

    private static final Logger log = LoggerFactory.getLogger(FixGenerationService.class);
    private static final int MAX_RETRIES = 3;

    private final AgentOrchestrator agentOrchestrator;
    private final OntologyMapper ontologyMapper;
    private final VulnerabilityRepository vulnerabilityRepo;
    private final FixRepository fixRepo;
    private final ReviewFeedbackRepository reviewFeedbackRepo;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;
    private final String primaryModel;

    public FixGenerationService(AgentOrchestrator agentOrchestrator,
                                OntologyMapper ontologyMapper,
                                VulnerabilityRepository vulnerabilityRepo,
                                FixRepository fixRepo,
                                ReviewFeedbackRepository reviewFeedbackRepo,
                                ApplicationEventPublisher eventPublisher,
                                AuditService auditService,
                                @Value("${agent.primary-model:gpt-4o}") String primaryModel) {
        this.agentOrchestrator = agentOrchestrator;
        this.ontologyMapper = ontologyMapper;
        this.vulnerabilityRepo = vulnerabilityRepo;
        this.fixRepo = fixRepo;
        this.reviewFeedbackRepo = reviewFeedbackRepo;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
        this.primaryModel = primaryModel;
    }

    @Async("cbAgentExecutor")
    @EventListener
    public void onVulnerabilityDetected(VulnerabilityDetectedEvent event) {
        Vulnerability vuln = event.getVulnerability();
        log.info("Agent processing vulnerability id={} cwe={} retryCount={}",
            vuln.id(), vuln.cweId(), vuln.retryCount());

        try {
            vulnerabilityRepo.save(vuln.withStatus(VulnerabilityStatus.IN_PROGRESS));

            List<ReviewFeedback> feedbacks =
                reviewFeedbackRepo.findByVulnerabilityIdAndProcessedFalse(vuln.id());
            if (!feedbacks.isEmpty()) {
                log.info("Including {} rejection feedback(s) in re-fix prompt for vuln={}",
                    feedbacks.size(), vuln.id());
            }

            // Agentic: LLM calls retrieveSimilarFixes + getRemediationGuideline tools internally
            String rawFix = agentOrchestrator.generateFix(vuln, feedbacks);
            Fix fix = parseFix(rawFix, vuln);
            Fix saved = fixRepo.save(fix);

            feedbacks.forEach(f -> reviewFeedbackRepo.save(f.withProcessed()));
            vulnerabilityRepo.save(vuln.withStatus(VulnerabilityStatus.FIX_GENERATED));

            auditService.log(vuln.id(), "Vulnerability", "FIX_GENERATED", "agent",
                Map.of("fixId", saved.id(), "model", fix.llmModel(), "confidence", fix.confidence()));
            eventPublisher.publishEvent(new FixGeneratedEvent(this, vuln, saved));

        } catch (Exception e) {
            log.error("Fix generation failed for vuln={}: {}", vuln.id(), e.getMessage(), e);
            handleFailure(vuln, e.getMessage());
        }
    }

    private Fix parseFix(String rawFix, Vulnerability vuln) {
        String json = rawFix.strip();
        if (json.startsWith("```")) {
            int start = json.indexOf('\n') + 1;
            int end = json.lastIndexOf("```");
            json = end > start ? json.substring(start, end).strip() : json;
        }
        Fix parsed = ontologyMapper.parseFixFromJsonLd(json);
        return new Fix(
            null,
            vuln.id(),
            parsed.patchDiff(),
            parsed.gradlePatch(),
            parsed.strategy() != null ? parsed.strategy() : "llm-generated",
            parsed.confidence(),
            parsed.llmModel() != null ? parsed.llmModel() : primaryModel,
            parsed.explanation(),
            null, null, null, null,
            FixStatus.PENDING,
            false, null,
            parsed.tokensUsed(),
            Instant.now()
        );
    }

    private void handleFailure(Vulnerability vuln, String reason) {
        Vulnerability updated = vuln.incrementRetryCount();
        if (updated.retryCount() >= MAX_RETRIES) {
            vulnerabilityRepo.save(updated.withStatus(VulnerabilityStatus.FAILED));
            eventPublisher.publishEvent(new EscalationEvent(this, updated, null,
                "Max retries exceeded: " + reason));
            auditService.log(vuln.id(), "Vulnerability", "ESCALATED", "agent",
                Map.of("reason", reason, "retryCount", updated.retryCount()));
        } else {
            vulnerabilityRepo.save(updated.withStatus(VulnerabilityStatus.DETECTED));
            auditService.log(vuln.id(), "Vulnerability", "RETRY_SCHEDULED", "agent",
                Map.of("retryCount", updated.retryCount(), "reason", reason));
        }
    }
}
