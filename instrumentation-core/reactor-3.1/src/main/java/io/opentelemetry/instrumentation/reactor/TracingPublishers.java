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

package io.opentelemetry.instrumentation.reactor;

import io.grpc.Context;
import io.opentelemetry.context.ContextUtils;
import io.opentelemetry.context.Scope;
import java.util.function.Consumer;
import org.reactivestreams.Publisher;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.Fuseable;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.GroupedFlux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.publisher.ParallelFlux;

public class TracingPublishers {

  /**
   * Registers a hook that applies to every operator, propagating {@link Context} to downstream
   * callbacks to ensure spans in the {@link Context} are available throughout the lifetime of a
   * reactive stream. This should generally be called in a static initializer block in your
   * application.
   */
  public static void registerOnEachOperator() {
    Hooks.onEachOperator(TracingPublishers.class.getName(), TracingPublishers::wrap);
  }

  /** Unregisters the hook registered by {@link #registerOnEachOperator()}. */
  public static void resetOnEachOperator() {
    Hooks.resetOnEachOperator(TracingPublishers.class.getName());
  }

  /**
   * Instead of using {@link reactor.core.publisher.Operators#lift} (available in reactor 3.1) or
   * {@link reactor.core.publisher.Operators#liftPublisher} (available in reactor 3.3) we create our
   * own version of {@link reactor.core.publisher.Operators#liftPublisher} that allows us to run
   * code at assembly time. The built in reactor functions handle all of the assembly time actions
   * internally so we are unable to attach spans to a Publisher context at the points where it would
   * make sense for us.
   *
   * <p>By doing this ourselves we will want to keep this in line with the {@link
   * reactor.core.publisher.Operators.LiftFunction} implementation in order to ensure greatest
   * compatibility
   */
  public static <T> Publisher<T> wrap(Publisher<T> delegate) {
    Context context = Context.current();

    // based on Operators.LiftFunction.apply in reactor 3.3.4
    if (delegate instanceof Fuseable) {
      if (delegate instanceof Mono) {
        return new FuseableMonoTracingPublisher<>(context, (Mono<T>) delegate);
      }
      if (delegate instanceof ParallelFlux) {
        return new FuseableParallelFluxTracingPublisher<>(context, (ParallelFlux<T>) delegate);
      }
      if (delegate instanceof ConnectableFlux) {
        return new FuseableConnectableFluxTracingPublisher<>(
            context, (ConnectableFlux<T>) delegate);
      }
      if (delegate instanceof GroupedFlux) {
        return new FuseableGroupedFluxTracingPublisher<>(context, (GroupedFlux<?, T>) delegate);
      }
      return new FuseableFluxTracingPublisher<>(context, (Flux<T>) delegate);
    } else {
      if (delegate instanceof Mono) {
        return new MonoTracingPublisher<>(context, (Mono<T>) delegate);
      }
      if (delegate instanceof ParallelFlux) {
        return new ParallelFluxTracingPublisher<>(context, (ParallelFlux<T>) delegate);
      }
      if (delegate instanceof ConnectableFlux) {
        return new ConnectableFluxTracingPublisher<>(context, (ConnectableFlux<T>) delegate);
      }
      if (delegate instanceof GroupedFlux) {
        return new GroupedFluxTracingPublisher<>(context, (GroupedFlux<?, T>) delegate);
      }
      return new FluxTracingPublisher<>(context, (Flux<T>) delegate);
    }
  }

  static <T> CoreSubscriber<? super T> wrapSubscriber(
      Context context, CoreSubscriber<? super T> actual) {
    if (actual instanceof TracingSubscriber) {
      return actual;
    } else {
      return new TracingSubscriber<>(context, actual);
    }
  }

  public static class MonoTracingPublisher<T> extends Mono<T> {
    private final Context context;
    private final Mono<T> delegate;

    public MonoTracingPublisher(Context context, Mono<T> delegate) {
      this.context = context;
      this.delegate = delegate;
    }

    @Override
    public void subscribe(CoreSubscriber<? super T> actual) {
      try (Scope scope = ContextUtils.withScopedContext(context)) {
        delegate.subscribe(wrapSubscriber(context, actual));
      }
    }
  }

  public static class ParallelFluxTracingPublisher<T> extends ParallelFlux<T> {
    private final Context context;
    private final ParallelFlux<T> delegate;

    public ParallelFluxTracingPublisher(Context context, ParallelFlux<T> delegate) {
      this.context = context;
      this.delegate = delegate;
    }

    @Override
    public int parallelism() {
      return delegate.parallelism();
    }

    @Override
    protected void subscribe(CoreSubscriber<? super T>[] subscribers) {
      try (Scope scope = ContextUtils.withScopedContext(context)) {
        for (CoreSubscriber<? super T> subscriber : subscribers) {
          delegate.subscribe(wrapSubscriber(context, subscriber));
        }
      }
    }
  }

  public static class ConnectableFluxTracingPublisher<T> extends ConnectableFlux<T> {
    private final Context context;
    private final ConnectableFlux<T> delegate;

    public ConnectableFluxTracingPublisher(Context context, ConnectableFlux<T> delegate) {
      this.context = context;
      this.delegate = delegate;
    }

    @Override
    public void connect(Consumer<? super Disposable> cancelSupport) {
      try (Scope scope = ContextUtils.withScopedContext(context)) {
        delegate.connect(cancelSupport);
      }
    }

    @Override
    public void subscribe(CoreSubscriber<? super T> actual) {
      try (Scope scope = ContextUtils.withScopedContext(context)) {
        delegate.subscribe(wrapSubscriber(context, actual));
      }
    }
  }

  public static class GroupedFluxTracingPublisher<O, T> extends GroupedFlux<O, T> {
    private final Context context;
    private final GroupedFlux<O, T> delegate;

    public GroupedFluxTracingPublisher(Context context, GroupedFlux<O, T> delegate) {
      this.context = context;
      this.delegate = delegate;
    }

    @Override
    public O key() {
      return delegate.key();
    }

    @Override
    public void subscribe(CoreSubscriber<? super T> actual) {
      try (Scope scope = ContextUtils.withScopedContext(context)) {
        delegate.subscribe(wrapSubscriber(context, actual));
      }
    }
  }

  public static class FluxTracingPublisher<T> extends Flux<T> {
    private final Context context;
    private final Flux<T> delegate;

    public FluxTracingPublisher(Context context, Flux<T> delegate) {
      this.context = context;
      this.delegate = delegate;
    }

    @Override
    public void subscribe(CoreSubscriber<? super T> actual) {
      try (Scope scope = ContextUtils.withScopedContext(context)) {
        delegate.subscribe(wrapSubscriber(context, actual));
      }
    }
  }

  public static class FuseableMonoTracingPublisher<T> extends MonoTracingPublisher<T>
      implements Fuseable {
    public FuseableMonoTracingPublisher(Context context, Mono<T> delegate) {
      super(context, delegate);
    }
  }

  public static class FuseableParallelFluxTracingPublisher<T>
      extends ParallelFluxTracingPublisher<T> implements Fuseable {
    public FuseableParallelFluxTracingPublisher(Context context, ParallelFlux<T> delegate) {
      super(context, delegate);
    }
  }

  public static class FuseableConnectableFluxTracingPublisher<T>
      extends ConnectableFluxTracingPublisher<T> implements Fuseable {
    public FuseableConnectableFluxTracingPublisher(Context context, ConnectableFlux<T> delegate) {
      super(context, delegate);
    }
  }

  public static class FuseableGroupedFluxTracingPublisher<O, T>
      extends GroupedFluxTracingPublisher<O, T> implements Fuseable {
    public FuseableGroupedFluxTracingPublisher(Context context, GroupedFlux<O, T> delegate) {
      super(context, delegate);
    }
  }

  public static class FuseableFluxTracingPublisher<T> extends FluxTracingPublisher<T>
      implements Fuseable {
    public FuseableFluxTracingPublisher(Context context, Flux<T> delegate) {
      super(context, delegate);
    }
  }
}
