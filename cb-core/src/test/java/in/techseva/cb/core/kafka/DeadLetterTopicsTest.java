package in.techseva.cb.core.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeadLetterTopicsTest {

    @Test
    void destination_appendsDlqSuffixOnSamePartition() {
        var record = new ConsumerRecord<String, Object>("fixes.generated", 3, 42L, "key", "value");

        var destination = DeadLetterTopics.destination(record);

        assertThat(destination.topic()).isEqualTo("fixes.generated.dlq");
        assertThat(destination.partition()).isEqualTo(3);
    }
}
