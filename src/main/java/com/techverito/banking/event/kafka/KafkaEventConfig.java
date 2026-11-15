package com.techverito.banking.event.kafka;

import com.techverito.banking.config.EventProperties;
import com.techverito.banking.event.BusinessEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * The Kafka plumbing behind {@link KafkaEventBus}: one topic, JSON payloads and a
 * single consumer group shared by this application's subscribers.
 *
 * <p>Only the broker connection details come from Spring Boot's own
 * {@code spring.kafka.*} settings; everything about the event format is fixed here.
 * Type headers are turned off in both directions: the payload shape is the event
 * envelope itself, so a consumer never resolves a Java class from a header, and the
 * deserializer always produces a {@link BusinessEvent} from the trusted event
 * package. Unknown JSON fields are ignored by the envelope, which is what keeps an
 * added field backward compatible.</p>
 *
 * <p>The whole configuration is only active for the Kafka transport, so an
 * application started with {@code banking.events.transport=in-memory} creates no
 * producer, no consumer and no topic, and therefore never contacts a broker.</p>
 *
 * <p>Publishing is at-least-once with the broker's own duplicate suppression: the
 * producer asks for {@code acks=all}, which is what "stored" means for the outbox
 * publisher, and has idempotence enabled, so a producer retry cannot append the same
 * record twice. The topic has three partitions, so the two business keys in use spread
 * over separate partitions and one account's backlog never blocks another's; records
 * with the same key still keep their order on one partition.</p>
 */
@Configuration
@EnableKafka
@EnableConfigurationProperties({KafkaProperties.class, EventProperties.class})
@ConditionalOnProperty(prefix = "banking.events", name = "transport", havingValue = "kafka", matchIfMissing = true)
public class KafkaEventConfig {

    /** The only package a consumed payload may be mapped into. */
    private static final String TRUSTED_PACKAGES = "com.techverito.banking.event";

    private static final String TRUSTED_PACKAGES_PROPERTY = "spring.json.trusted.packages";

    private static final String USE_TYPE_HEADERS_PROPERTY = "spring.json.use.type.headers";

    private static final String VALUE_DEFAULT_TYPE_PROPERTY = "spring.json.value.default.type";

    /** A new subscriber of an existing topic still sees the events already on it. */
    private static final String EARLIEST = "earliest";

    /**
     * A publish only counts once every in-sync replica has the record, and the
     * producer's idempotence keeps its own retries from writing the same record twice.
     */
    private static final String ACKS_ALL = "all";

    /** Bounds a dead-broker wait, so a drain attempt fails and is retried instead of hanging. */
    private static final int REQUEST_TIMEOUT_MS = 5_000;

    private static final int DELIVERY_TIMEOUT_MS = 15_000;

    @Bean
    public ProducerFactory<String, BusinessEvent> producerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers(kafkaProperties));
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        config.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        config.put(ProducerConfig.ACKS_CONFIG, ACKS_ALL);
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, REQUEST_TIMEOUT_MS);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, DELIVERY_TIMEOUT_MS);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, BusinessEvent> kafkaTemplate(ProducerFactory<String, BusinessEvent> producerFactory,
                                                             EventProperties properties) {
        KafkaTemplate<String, BusinessEvent> template = new KafkaTemplate<>(producerFactory);
        template.setDefaultTopic(properties.topic());
        return template;
    }

    @Bean
    public ConsumerFactory<String, BusinessEvent> consumerFactory(KafkaProperties kafkaProperties,
                                                                 EventProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers(kafkaProperties));
        config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.consumerGroup());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, EARLIEST);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        config.put(TRUSTED_PACKAGES_PROPERTY, TRUSTED_PACKAGES);
        config.put(USE_TYPE_HEADERS_PROPERTY, false);
        config.put(VALUE_DEFAULT_TYPE_PROPERTY, BusinessEvent.class.getName());
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, BusinessEvent> kafkaListenerContainerFactory(
            ConsumerFactory<String, BusinessEvent> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, BusinessEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        return factory;
    }

    @Bean
    public NewTopic businessEventsTopic(EventProperties properties) {
        return TopicBuilder.name(properties.topic())
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * The dead-letter topic of the business-event topic, derived from the one topic
     * setting, so it exists in a freshly started local Kafka before any event fails.
     */
    @Bean
    public NewTopic businessEventsDeadLetterTopic(EventProperties properties) {
        return TopicBuilder.name(KafkaDeadLetterPublisher.deadLetterTopicName(properties.topic()))
                .partitions(3)
                .replicas(1)
                .build();
    }

    private String brokers(KafkaProperties kafkaProperties) {
        return String.join(",", kafkaProperties.getBootstrapServers());
    }
}
