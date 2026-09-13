package in.techseva.cb.escalation.config;

import in.techseva.cb.core.kafka.DeadLetterTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.backoff.FixedBackOff;

import java.util.concurrent.Executor;

@Configuration
public class EscalationConfig {

    private static final Logger log = LoggerFactory.getLogger(EscalationConfig.class);

    @Bean("escalationExecutor")
    public Executor escalationExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("cb-escalation-");
        executor.initialize();
        return executor;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            KafkaTemplate<Object, Object> kafkaTemplate) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
            (rec, ex) -> {
                log.error("Exhausted retries, sending to DLQ topic={}{} partition={} offset={}: {}",
                    rec.topic(), DeadLetterTopics.SUFFIX, rec.partition(), rec.offset(), ex.getMessage());
                return DeadLetterTopics.destination(rec);
            });
        var errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
        errorHandler.addNotRetryableExceptions(DeserializationException.class);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
