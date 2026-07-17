package com.nectrix.coreapp.billing.config;

import com.nectrix.events.consumer.EventProducer;
import com.nectrix.events.v1.BillingEvent;
import java.util.Properties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * TICKET-113 — topic {@code billing}, partition key {@code user_id}
 * (packages/event-contracts/proto/nectrix/events/v1/billing_event.proto's own comment). Mirrors
 * modules/invitations' BrokerConnectionEventProducerConfiguration exactly (same
 * KAFKA_HOST/KAFKA_PORT env-var convention, same vanilla-kafka-clients-not-Spring-Kafka reasoning).
 * TICKET-115 (notifications) isn't built yet to consume this — publishing anyway, same "producer
 * ships ahead of its consumer ticket" precedent as BrokerConnectionEvent/ CopyRelationshipEvent.
 */
@Configuration
public class BillingEventProducerConfiguration {

  private static final String TOPIC = "billing";

  @Bean(destroyMethod = "close")
  public EventProducer<BillingEvent> billingEventProducer(Environment env) {
    String host = env.getProperty("KAFKA_HOST", "localhost");
    String port = env.getProperty("KAFKA_PORT", "9092");

    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, host + ":" + port);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);

    KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props);
    return new EventProducer<>(producer, TOPIC);
  }
}
