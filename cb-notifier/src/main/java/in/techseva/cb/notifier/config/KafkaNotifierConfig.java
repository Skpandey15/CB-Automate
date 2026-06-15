package in.techseva.cb.notifier.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaNotifierConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaNotifierConfig.class);

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        var errorHandler = new DefaultErrorHandler(
            (rec, ex) -> log.error("Skipping bad record topic={} partition={} offset={}: {}",
                rec.topic(), rec.partition(), rec.offset(), ex.getMessage()),
            new FixedBackOff(1000L, 3L)
        );
        errorHandler.addNotRetryableExceptions(DeserializationException.class);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
