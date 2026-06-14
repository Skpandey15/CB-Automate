package in.techseva.cb.core.repository;

import in.techseva.cb.core.domain.CostRecord;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

public interface CostRepository extends MongoRepository<CostRecord, String> {

    List<CostRecord> findByVulnerabilityId(String vulnerabilityId);

    List<CostRecord> findByRecordedAtAfter(Instant since);

    @Aggregation(pipeline = {
        "{ $match: { recordedAt: { $gte: ?0 } } }",
        "{ $group: { _id: '$cweId', totalCost: { $sum: '$costUsd' }, totalTokens: { $sum: { $add: ['$inputTokens', '$outputTokens'] } }, count: { $sum: 1 } } }",
        "{ $sort: { totalCost: -1 } }"
    })
    List<CostSummaryByCwe> sumCostByCwe(Instant since);

    @Aggregation(pipeline = {
        "{ $match: { recordedAt: { $gte: ?0 } } }",
        "{ $group: { _id: '$llmModel', totalCost: { $sum: '$costUsd' }, totalTokens: { $sum: { $add: ['$inputTokens', '$outputTokens'] } } } }"
    })
    List<CostSummaryByModel> sumCostByModel(Instant since);

    record CostSummaryByCwe(String id, double totalCost, long totalTokens, long count) {}
    record CostSummaryByModel(String id, double totalCost, long totalTokens) {}
}
