package datadog.opentracing.scopemanager;

import datadog.opentracing.DDSpanContext;
import datadog.opentracing.PendingTrace;
import datadog.trace.context.TraceScope;
import datadog.trace.context.TraceScope.ParentingStrategy;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.noop.NoopScopeManager;

import java.io.Closeable;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.extern.slf4j.Slf4j;

import datadog.trace.api.interceptor.MutableSpan;
import datadog.opentracing.DDSpan;
import datadog.trace.context.TraceScope.ParentingStrategy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Deque;
import java.util.ArrayDeque;

@Slf4j
public class ContinuableScope implements Scope, TraceScope {
  /**
   * ScopeManager holding the thread-local to this scope.
   */
  private final ContextualScopeManager scopeManager;
  /**
   * Span contained by this scope. Async scopes will hold a reference to the parent scope's span.
   */
  private final DDSpan spanUnderScope;
  /**
   * If true, finish the span when openCount hits 0.
   */
  private final boolean finishOnClose;
  /**
   * Count of open scope and continuations
   */
  private final AtomicInteger openCount;
  /**
   * Scope to placed in the thread local after close. May be null.
   */
  private final Scope toRestore;

  ContinuableScope(final ContextualScopeManager scopeManager, final DDSpan spanUnderScope, final boolean finishOnClose) {
    this(scopeManager, new AtomicInteger(1), spanUnderScope, finishOnClose);
  }

  private ContinuableScope(
      final ContextualScopeManager scopeManager,
      final AtomicInteger openCount,
      final DDSpan spanUnderScope,
      final boolean finishOnClose) {
    this.scopeManager = scopeManager;
    this.openCount = openCount;
    this.spanUnderScope = spanUnderScope;
    this.finishOnClose = finishOnClose;
    this.toRestore = scopeManager.tlsScope.get();
    scopeManager.tlsScope.set(this);
  }

  @Override
  public void close() {
    if (openCount.decrementAndGet() == 0 && finishOnClose) {
      spanUnderScope.finish();
    }

    if (scopeManager.tlsScope.get() == this) {
      scopeManager.tlsScope.set(toRestore);
    }
  }

  @Override
  public DDSpan span() {
    return spanUnderScope;
  }

  @Override
  public void setAsyncLinking(boolean value) {
    // FIXME: implement
  }

  @Override
  public boolean isAsyncLinking() {
    // FIXME: implement
    return true;
  }

  /**
   * The continuation returned should be closed after the associa
   *
   * @param finishOnClose
   * @return
   */
  public Continuation capture() {
    return new Continuation();
  }

  public class Continuation implements Closeable, TraceScope.Continuation {
    public WeakReference<Continuation> ref;

    private final AtomicBoolean used = new AtomicBoolean(false);
    private final PendingTrace trace;

    private Continuation() {
      openCount.incrementAndGet();
      if (spanUnderScope.context() instanceof DDSpanContext) {
        final DDSpanContext context = (DDSpanContext) spanUnderScope.context();
        trace = context.getTrace();
        trace.registerContinuation(this);
      } else {
        trace = null;
      }
    }

    public ContinuableScope activate() {
      if (used.compareAndSet(false, true)) {
        final ContinuableScope scope = new ContinuableScope(scopeManager, openCount, spanUnderScope, finishOnClose);
        if (trace != null) {
          // FIXME: Allow registering Scope with the trace.
          // trace must remain open while context propagation.
          // tests are passing because parent span remains open, but this is not required.
          trace.cancelContinuation(this);
        }
        return scope;
      } else {
        log.debug("Failed to activate continuation. Reusing a continuation not allowed.  Returning a new scope. Spans will not be linked.");
        return new ContinuableScope(scopeManager, new AtomicInteger(1), spanUnderScope, finishOnClose);
      }
    }

    @Override
    public void close() {
      used.getAndSet(true);
      if (trace != null) {
        trace.cancelContinuation(this);
        ContinuableScope.this.close();
      }
    }
  }
}
