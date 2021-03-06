/*
 * Copyright The OpenTelemetry Authors
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

package io.opentelemetry.instrumentation.auto.ratpack;

import static io.opentelemetry.instrumentation.auto.ratpack.RatpackDecorator.DECORATE;
import static io.opentelemetry.instrumentation.auto.ratpack.RatpackDecorator.TRACER;
import static io.opentelemetry.trace.TracingContextUtils.currentContextWith;
import static io.opentelemetry.trace.TracingContextUtils.getSpan;

import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.opentelemetry.context.Scope;
import io.opentelemetry.trace.Span;
import ratpack.handling.Context;
import ratpack.handling.Handler;

public final class TracingHandler implements Handler {
  public static Handler INSTANCE = new TracingHandler();

  /**
   * This constant is copied over from
   * io.opentelemetry.instrumentation.auto.netty.v4_1.AttributeKeys. The key string must be kept
   * consistent.
   */
  public static final AttributeKey<io.grpc.Context> SERVER_ATTRIBUTE_KEY =
      AttributeKey.valueOf(
          "io.opentelemetry.instrumentation.auto.netty.v4_1.server.HttpServerTracingHandler.context");

  @Override
  public void handle(Context ctx) {
    Attribute<io.grpc.Context> spanAttribute =
        ctx.getDirectChannelAccess().getChannel().attr(SERVER_ATTRIBUTE_KEY);
    io.grpc.Context serverSpanContext = spanAttribute.get();

    // Relying on executor instrumentation to assume the netty span is in context as the parent.
    Span ratpackSpan = TRACER.spanBuilder("ratpack.handler").startSpan();
    DECORATE.afterStart(ratpackSpan);
    ctx.getExecution().add(ratpackSpan);

    ctx.getResponse()
        .beforeSend(
            response -> {
              try (Scope ignored = currentContextWith(ratpackSpan)) {
                if (serverSpanContext != null) {
                  // Rename the netty span name with the ratpack route.
                  DECORATE.onContext(getSpan(serverSpanContext), ctx);
                }
                DECORATE.onContext(ratpackSpan, ctx);
                DECORATE.beforeFinish(ratpackSpan);
                ratpackSpan.end();
              }
            });

    try (Scope ignored = currentContextWith(ratpackSpan)) {
      ctx.next();
      // exceptions are captured by ServerErrorHandlerInstrumentation
    }
  }
}
