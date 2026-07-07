package com.nectrix.events.consumer;

import com.google.protobuf.Message;
import java.util.concurrent.Future;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

/** Thin typed wrapper over {@code KafkaProducer<String, byte[]>} — keyed publish of a proto message. */
public class EventProducer<T extends Message> implements AutoCloseable {

  private final KafkaProducer<String, byte[]> producer;
  private final String topic;

  public EventProducer(KafkaProducer<String, byte[]> producer, String topic) {
    this.producer = producer;
    this.topic = topic;
  }

  public Future<RecordMetadata> send(String key, T message) {
    return producer.send(new ProducerRecord<>(topic, key, message.toByteArray()));
  }

  @Override
  public void close() {
    producer.close();
  }
}
