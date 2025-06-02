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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import freemarker.template.TemplateExceptionHandler;
import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.http.HttpHeaderNames;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.HttpExecutionContext;
import io.gravitee.gateway.reactive.api.el.EvaluableMessage;
import io.gravitee.gateway.reactive.api.el.EvaluableRequest;
import io.gravitee.gateway.reactive.api.el.EvaluableResponse;
import io.gravitee.gateway.reactive.api.policy.Policy;
import io.gravitee.policy.rabbitmqconsumer.configuration.RabbitMQConfiguration;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RabbitMQConsumerPolicy implements Policy {

    private final RabbitMQConfiguration configuration;
    private final ConnectionFactory factory;
    private Integer timeOut;
    private Integer timeToLive;
    Map<String, Boolean> queueConfig = new HashMap<>();
    private String attributeQueueID;
    private Boolean createQueue;
    private Boolean consumeQueue;
    private static final String TEMPLATE_VARIABLE = "message";

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
        this.timeToLive = configuration.getTimeToLive();
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
        return consumeMessage(ctx);
    }

    @Override
    public Completable onResponse(HttpExecutionContext ctx) {
        return consumeMessage(ctx);
    }

    private Completable consumeMessage(HttpExecutionContext ctx) {
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
                            Map.of("x-expires", this.timeToLive)
                        );
                    } catch (IOException e) {
                        emitter.onError(
                            new RuntimeException("Queue declaration failed. Possibly due to mismatched parameters.", e)
                        );
                        return;
                    }
                }

                // Create a timeout handler
                java.util.Timer timer = new java.util.Timer(true);
                timer.schedule(
                    new java.util.TimerTask() {
                        @Override
                        public void run() {
                            try {
                                log.info(
                                    "Timeout reached for queue {} in {}ms, no message received",
                                    subscriptionId,
                                    timeOut
                                );
                                //No content
                                ctx.response().status(204);
                                channel.queueDelete(subscriptionId);
                                channel.close();
                                connection.close();
                                emitter.onComplete();
                            } catch (Exception e) {
                                log.error("Error during timeout cleanup", e);
                                emitter.onError(e);
                            }
                        }
                    },
                    this.timeOut
                );

                if (this.consumeQueue) {
                    channel.basicConsume(
                        subscriptionId,
                        true,
                        (consumerTag, delivery) -> {
                            try {
                                timer.cancel();
                                String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
                                log.info("Received message: {}", message);

                                // Set the message in template context for evaluation
                                ctx.getTemplateEngine().getTemplateContext().setVariable(TEMPLATE_VARIABLE, message);
                                assign(ctx)
                                    .subscribe(
                                        () -> {
                                            ctx.response().body(Buffer.buffer(message));

                                            try {
                                                channel.basicCancel(consumerTag);
                                                channel.close();
                                                connection.close();
                                            } catch (Exception e) {
                                                log.warn("Failed to clean up consumer/channel", e);
                                            }

                                            emitter.onComplete();
                                        },
                                        error -> {
                                            log.error("Error assigning attributes", error);
                                            emitter.onError(error);
                                        }
                                    );
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

    private Completable assign(HttpExecutionContext ctx) {
        return Flowable
            .fromIterable(configuration.getVariables())
            .flatMapCompletable(variable -> {
                try {
                    // Get the message from template context
                    String message = ctx
                        .getTemplateEngine()
                        .getTemplateContext()
                        .lookupVariable(TEMPLATE_VARIABLE)
                        .toString();

                    // Set the message in the template context for evaluation
                    ctx.getTemplateEngine().getTemplateContext().setVariable("message", message);

                    // Evaluate the expression
                    return ctx
                        .getTemplateEngine()
                        .eval(variable.getValue(), String.class)
                        .doOnSuccess(value -> {
                            // Set the attribute in both the context and template context
                            ctx.setAttribute(variable.getName(), value);
                            ctx.getTemplateEngine().getTemplateContext().setVariable(variable.getName(), value);
                            log.info("Assigned attribute {} = {}", variable.getName(), value);
                        })
                        .ignoreElement();
                } catch (Exception e) {
                    log.error("Error evaluating expression for variable {}: {}", variable.getName(), e.getMessage());
                    return Completable.error(e);
                }
            })
            .doOnComplete(() -> {
                // Clean up template context
                ctx.getTemplateEngine().getTemplateContext().setVariable(TEMPLATE_VARIABLE, null);
            });
    }

    private Maybe<Buffer> assignBodyContent(
        HttpExecutionContext ctx,
        HttpHeaders httpHeaders,
        Maybe<Buffer> body,
        boolean isRequest
    ) {
        return body
            .flatMap((Buffer content) -> {
                Writer writer = replaceContent(isRequest, ctx, content.toString());
                return Maybe.just(Buffer.buffer(writer.toString()));
            })
            .switchIfEmpty(
                Maybe.fromCallable(() -> {
                    // Fallback if body is empty (e.g., GET requests)
                    Writer writer = replaceContent(isRequest, ctx, "");
                    return Buffer.buffer(writer.toString());
                })
            )
            .doOnSuccess(buffer -> {
                // Automatically set Content-Length header
                httpHeaders.set(HttpHeaderNames.CONTENT_LENGTH, Integer.toString(buffer.length()));
            })
            .onErrorResumeNext(ioe -> {
                log.debug("Unable to assign body content", ioe);
                return ctx.interruptBodyWith(
                    new ExecutionFailure(HttpStatusCode.INTERNAL_SERVER_ERROR_500)
                        .message("Unable to assign body content: " + ioe.getMessage())
                );
            });
    }

    private Writer replaceContent(boolean isRequest, HttpExecutionContext ctx, String rawContent) {
        // Example: no-op writer
        StringWriter writer = new StringWriter();
        writer.write(rawContent); // or apply Freemarker/template logic here
        return writer;
    }
}
