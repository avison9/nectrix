package com.nectrix.events.consumer;

import com.google.protobuf.Message;

/** Business-logic callback invoked by {@link IdempotentConsumer} for each non-duplicate record. */
@FunctionalInterface
public interface RecordHandler<T extends Message> {

  /** May throw — {@link IdempotentConsumer} retries per its configured {@link RetryPolicy}. */
  void handle(T event) throws Exception;
}
