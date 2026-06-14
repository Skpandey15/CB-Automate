package in.techseva.cb.agent.service;

import in.techseva.cb.core.domain.CostRecord;
import in.techseva.cb.core.kafka.KafkaTopics;
import in.techseva.cb.core.repository.CostRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class CostTrackingService {

    private static final Logger log = LoggerFactory.getLogger(CostTrackingService.class);

    private final CostRepository costRepository;
    private final KafkaTemplate<String, CostRecord> kafkaTemplate;

    public CostTrackingService(CostRepository costRepository,
                                KafkaTemplate<String, CostRecord> kafkaTemplate) {
        this.costRepository = costRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    public CostRecord record(String vulnerabilityId,
                              String fixId,
                              String cweId,
                              String llmModel,
                              String operation,
                              long inputTokens,
                              long outputTokens,
                              boolean accepted) {
        double cost = CostRecord.calculateCost(llmModel, inputTokens, outputTokens);
        CostRecord record = new CostRecord(
                null,
                vulnerabilityId,
                fixId,
                cweId,
                llmModel,
                operation,
                inputTokens,
                outputTokens,
                cost,
                accepted,
                Instant.now()
        );
        CostRecord saved = costRepository.save(record);
        kafkaTemplate.send(KafkaTopics.COST_TRACKED, vulnerabilityId, saved);
        log.info("Cost tracked: vuln={} model={} op={} tokens={}+{} cost=${}",
                vulnerabilityId, llmModel, operation, inputTokens, outputTokens,
                String.format("%.6f", cost));
        return saved;
    }
}
