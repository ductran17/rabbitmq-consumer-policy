/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.policy.rabbitmqconsumer;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.reactive.api.context.HttpExecutionContext;
import io.gravitee.gateway.reactive.api.policy.Policy;
import io.gravitee.policy.rabbitmqconsumer.configuration.RabbitMQConfiguration;
import io.reactivex.rxjava3.core.Completable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RabbitMQConsumerPolicy implements Policy {

    private final RabbitMQConfiguration configuration;
    private final ConnectionFactory factory;
    private Integer timeOut;
    Map<String, Boolean> queueConfig = new HashMap<>();
    private String attributeQueueID;
    private Boolean createQueue;
    private Boolean consumeQueue;

    public RabbitMQConsumerPolicy(RabbitMQConfiguration configuration) {
        this.configuration = configuration;
        this.factory = new ConnectionFactory();
        factory.setHost(configuration.getHost());
        factory.setPort(configuration.getPort());
        factory.setUsername(configuration.getUsername());
        factory.setPassword(configuration.getPassword());
        factory.setVirtualHost("/");
        factory.setConnectionTimeout(5000);
        this.attributeQueueID = configuration.getAttributeQueueID();
        this.timeOut = configuration.getTimeout();
        this.createQueue = configuration.getCreateQueue();
        this.consumeQueue = configuration.getConsumeQueue();
        this.queueConfig =
            Map.of(
                "durable",
                configuration.getQueueDurable(),
                "exclusive",
                configuration.getQueueExclusive(),
                "autoDelete",
                configuration.getQueueAutoDelete()
            );
    }

    @Override
    public String id() {
        return "rabbitmq-consumer-policy";
    }

    @Override
    public Completable onRequest(HttpExecutionContext ctx) {
        return Completable.complete();
    }

    @Override
    public Completable onResponse(HttpExecutionContext ctx) {
        return Completable.create(emitter -> {
            String subscriptionId = ctx.getAttribute(this.attributeQueueID);
            if (subscriptionId == null) {
                emitter.onError(new IllegalArgumentException("Subscription ID not found in context"));
                return;
            }

            try {
                Connection connection = factory.newConnection();
                Channel channel = connection.createChannel();

                if (this.createQueue) {
                    try {
                        // Use queueDeclare to make sure queue exist (will create if queue not exist
                        channel.queueDeclare(
                            subscriptionId,
                            queueConfig.get("durable"), // durable
                            queueConfig.get("exclusive"), // exclusive
                            queueConfig.get("autoDelete"), // autoDelete
                            Map.of("x-expires", this.timeOut)
                        );
                    } catch (IOException e) {
                        emitter.onError(
                            new RuntimeException("Queue declaration failed. Possibly due to mismatched parameters.", e)
                        );
                        return;
                    }
                }

                // Consume messages and stop after receiving the first one
                if (this.consumeQueue) {
                    channel.basicConsume(
                        subscriptionId,
                        true,
                        (consumerTag, delivery) -> {
                            try {
                                String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
                                ctx.response().headers().set("Content-Type", "text/plain");
                                ctx.response().body(Buffer.buffer(message));
                                ctx.response().end(ctx);
                                channel.close();
                                connection.close();
                                emitter.onComplete();
                            } catch (Exception e) {
                                emitter.onError(e);
                            }
                        },
                        consumerTag -> {}
                    );
                }
            } catch (Exception e) {
                emitter.onError(e);
            }
        });
    }
}
