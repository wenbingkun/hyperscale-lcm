package com.sc.lcm.core.domain;

import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer;

public class JobDeserializer extends ObjectMapperDeserializer<Job> {
    public JobDeserializer() {
        super(Job.class);
    }
}
