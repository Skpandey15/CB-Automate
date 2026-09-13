package in.techseva.cb.core.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;

/**
 * Shared dead-letter-topic naming convention for every module's Kafka
 * consumer error handler: a poison message from "{@code some.topic}" is
 * republished to "{@code some.topic.dlq}" on the same partition, instead
 * of being silently dropped after its retries are exhausted.
 */
public final class DeadLetterTopics {

    private DeadLetterTopics() {}

    public static final String SUFFIX = ".dlq";

    public static TopicPartition destination(ConsumerRecord<?, ?> record) {
        return new TopicPartition(record.topic() + SUFFIX, record.partition());
    }
}
