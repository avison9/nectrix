package com.nectrix.coreapp.invitations.config;

import com.nectrix.events.consumer.EventProducer;
import com.nectrix.events.v1.BrokerConnectionEvent;
import java.util.Properties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * TICKET-101 — this platform's first real Java business Kafka producer. Topic {@code
 * broker-connection}, partition key {@code broker_account_id} (docs/15-event-driven-
 * architecture.md §15.3 / packages/event-contracts/README.md). {@code KAFKA_HOST}/{@code
 * KAFKA_PORT} match every other service's env-var convention (apps/copy-engine,
 * apps/broker-adapters, InfraConnectivitySmokeTest) — deliberately not Spring Kafka's {@code
 * spring.kafka.*} properties, since packages/event-contracts/java's {@code EventProducer} is
 * framework-agnostic vanilla {@code kafka-clients} by design.
 */
@Configuration
public class BrokerConnectionEventProducerConfiguration {

  private static final String TOPIC = "broker-connection";

  @Bean(destroyMethod = "close")
  public EventProducer<BrokerConnectionEvent> brokerConnectionEventProducer(Environment env) {
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
