package datadog.trace.instrumentation.play;

import static net.bytebuddy.matcher.ElementMatchers.*;

import akka.japi.JavaPartialFunction;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.DDAdvice;
import datadog.trace.agent.tooling.DDTransformers;
import datadog.trace.agent.tooling.HelperInjector;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMap;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import org.slf4j.LoggerFactory;
import play.api.mvc.Action;
import play.api.mvc.Request;
import play.api.mvc.Result;
import play.routing.HandlerDef;
import play.routing.Router;
import scala.Function1;
import scala.Option;
import scala.Tuple2;
import scala.concurrent.Future;

@Slf4j
@AutoService(Instrumenter.class)
public final class PlayInstrumentation extends Instrumenter.Configurable {
  private static final HelperInjector PLAY_HELPERS =
      new HelperInjector(
          PlayInstrumentation.class.getName() + "$RequestCallback",
          PlayInstrumentation.class.getName() + "$RequestError",
          PlayInstrumentation.class.getName() + "$PlayHeaders");

  public PlayInstrumentation() {
    super("play");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public AgentBuilder apply(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(hasSuperType(named("play.api.mvc.Action")))
        .and(
            declaresMethod(
                named("executionContext").and(returns(named("scala.concurrent.ExecutionContext")))))
        .transform(PLAY_HELPERS)
        .transform(DDTransformers.defaultTransformers())
        .transform(
            DDAdvice.create()
                .advice(
                    named("apply")
                        .and(takesArgument(0, named("play.api.mvc.Request")))
                        .and(returns(named("scala.concurrent.Future"))),
                    PlayAdvice.class.getName()))
        .asDecorator();
  }

  public static class PlayAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Scope startSpan(@Advice.Argument(0) final Request req) {
      // TODO begin tracking across threads
      // if context instanceof TraceScope
      // -- context.setAsyncLink(true)

      if (GlobalTracer.get().activeSpan() == null) {
        final SpanContext extractedContext;
        if (GlobalTracer.get().scopeManager().active() == null) {
          extractedContext =
              GlobalTracer.get().extract(Format.Builtin.HTTP_HEADERS, new PlayHeaders(req));
        } else {
          extractedContext = null;
        }
        return GlobalTracer.get()
            .buildSpan("play.request")
            .asChildOf(extractedContext)
            .startActive(false);
      } else {
        // An upstream framework (e.g. akka-http, netty) has already started the span.
        // Do not extract the context.
        return GlobalTracer.get().buildSpan("play.request").startActive(false);
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopTraceOnResponse(
        @Advice.Enter final Scope scope,
        @Advice.This final Object thisAction,
        @Advice.Thrown final Throwable throwable,
        @Advice.Argument(0) final Request req,
        @Advice.Return(readOnly = false) Future<Result> responseFuture) {
      // more about routes here: https://github.com/playframework/playframework/blob/master/documentation/manual/releases/release26/migration26/Migration26.md
      final Option handlerOption = req.attrs().get(Router.Attrs.HANDLER_DEF.underlying());
      if (!handlerOption.isEmpty()) {
        final HandlerDef handlerDef = (HandlerDef) handlerOption.get();
        scope.span().setTag(Tags.HTTP_URL.getKey(), handlerDef.path());
        scope.span().setOperationName(handlerDef.path());
        scope.span().setTag(DDTags.RESOURCE_NAME, req.method() + " " + handlerDef.path());
      }
      scope.span().setTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER);
      scope.span().setTag(Tags.HTTP_METHOD.getKey(), req.method());
      scope.span().setTag(DDTags.SPAN_TYPE, DDSpanTypes.WEB_SERVLET);
      scope.span().setTag(Tags.COMPONENT.getKey(), "play-action");

      if (throwable == null) {
        responseFuture.onFailure(
            new RequestError(scope.span()), ((Action) thisAction).executionContext());
        responseFuture =
            responseFuture.map(
                new RequestCallback(scope.span()), ((Action) thisAction).executionContext());
      } else {
        RequestError.onError(scope.span(), throwable);
        scope.span().finish();
      }
      scope.close();
    }
  }

  public static class PlayHeaders implements TextMap {
    private final Request request;

    public PlayHeaders(Request request) {
      this.request = request;
    }

    @Override
    public Iterator<Map.Entry<String, String>> iterator() {
      final scala.collection.Map scalaMap = request.headers().toSimpleMap();
      final Map<String, String> javaMap = new HashMap<>(scalaMap.size());
      final scala.collection.Iterator<Tuple2<String, String>> scalaIterator = scalaMap.iterator();
      while (scalaIterator.hasNext()) {
        final Tuple2<String, String> tuple = scalaIterator.next();
        javaMap.put(tuple._1(), tuple._2());
      }
      return javaMap.entrySet().iterator();
    }

    @Override
    public void put(String s, String s1) {
      throw new IllegalStateException("play headers can only be extracted");
    }
  }

  public static class RequestError extends JavaPartialFunction<Throwable, Object> {
    private final Span span;

    public RequestError(Span span) {
      this.span = span;
    }

    @Override
    public Object apply(Throwable t, boolean isCheck) throws Exception {
      try {
        onError(span, t);
      } catch (Throwable t2) {
        LoggerFactory.getLogger(RequestCallback.class).debug("error in play instrumentation", t);
      }
      span.finish();
      return null;
    }

    public static void onError(final Span span, final Throwable t) {
      Tags.ERROR.set(span, Boolean.TRUE);
      span.log(logsForException(t));
      Tags.HTTP_STATUS.set(span, 500);
    }

    public static Map<String, Object> logsForException(Throwable throwable) {
      final Map<String, Object> errorLogs = new HashMap<>(4);
      errorLogs.put("event", Tags.ERROR.getKey());
      errorLogs.put("error.kind", throwable.getClass().getName());
      errorLogs.put("error.object", throwable);

      errorLogs.put("message", throwable.getMessage());

      final StringWriter sw = new StringWriter();
      throwable.printStackTrace(new PrintWriter(sw));
      errorLogs.put("stack", sw.toString());

      return errorLogs;
    }
  }

  public static class RequestCallback implements Function1<Result, Result> {
    private final Span span;

    public RequestCallback(Span span) {
      this.span = span;
    }

    public Result apply(Result result) {
      // TODO stop tracking across threads
      // if context instanceof TraceScope
      // -- context.setAsyncLink(false)
      try {
        Tags.HTTP_STATUS.set(span, result.header().status());
      } catch (Throwable t) {
        LoggerFactory.getLogger(RequestCallback.class).debug("error in play instrumentation", t);
      }
      span.finish();
      return result;
    }
  }
}
