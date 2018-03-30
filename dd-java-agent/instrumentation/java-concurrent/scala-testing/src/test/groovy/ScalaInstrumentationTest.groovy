import datadog.opentracing.DDSpan
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.Trace
import spock.lang.Shared
import spock.lang.Unroll

import java.lang.reflect.Method
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class ScalaInstrumentationTest extends AgentTestRunner {
  static {
    System.setProperty("dd.integration.java_concurrent.enabled", "true")
  }

  @Override
  void afterTest() {
    // Ignore failures to instrument sun proxy classes
  }

  def "scala futures and callbacks"() {
    setup:
    ScalaConcurrentTests scalaTest = new ScalaConcurrentTests()
    int expectedNumberOfSpans = scalaTest.traceWithFutureAndCallbacks()
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    trace.size() == expectedNumberOfSpans
    trace[0].operationName == "ScalaConcurrentTests.traceWithFutureAndCallbacks"
    findSpan(trace, "goodFuture").context().getParentId() == trace[0].context().getSpanId()
    findSpan(trace, "badFuture").context().getParentId() == trace[0].context().getSpanId()
    findSpan(trace, "successCallback").context().getParentId() == trace[0].context().getSpanId()
    findSpan(trace, "successCallback").context().getParentId() == trace[0].context().getSpanId()
    findSpan(trace, "failureCallback").context().getParentId() == trace[0].context().getSpanId()
  }

  def "scala propagates across futures with no traces"() {
    setup:
    ScalaConcurrentTests scalaTest = new ScalaConcurrentTests()
    int expectedNumberOfSpans = scalaTest.tracedAcrossThreadsWithNoTrace()
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    trace.size() == expectedNumberOfSpans
    trace[0].operationName == "ScalaConcurrentTests.tracedAcrossThreadsWithNoTrace"
    findSpan(trace, "callback").context().getParentId() == trace[0].context().getSpanId()
  }

  def "scala either promise completion"() {
    setup:
    ScalaConcurrentTests scalaTest = new ScalaConcurrentTests()
    int expectedNumberOfSpans = scalaTest.traceWithPromises()
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    TEST_WRITER.size() == 1
    trace.size() == expectedNumberOfSpans
    trace[0].operationName == "ScalaConcurrentTests.traceWithPromises"
    findSpan(trace, "keptPromise").context().getParentId() == trace[0].context().getSpanId()
    findSpan(trace, "keptPromise2").context().getParentId() == trace[0].context().getSpanId()
    findSpan(trace, "brokenPromise").context().getParentId() == trace[0].context().getSpanId()
  }

  def "scala first completed future"() {
    setup:
    ScalaConcurrentTests scalaTest = new ScalaConcurrentTests()
    int expectedNumberOfSpans = scalaTest.tracedWithFutureFirstCompletions()
    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    TEST_WRITER.size() == 1
    trace.size() == expectedNumberOfSpans
    findSpan(trace, "timeout1").context().getParentId() == trace[0].context().getSpanId()
    findSpan(trace, "timeout2").context().getParentId() == trace[0].context().getSpanId()
    findSpan(trace, "timeout3").context().getParentId() == trace[0].context().getSpanId()
  }

  private DDSpan findSpan(List<DDSpan> trace, String opName) {
    for (DDSpan span : trace) {
      if (span.getOperationName() == opName) {
        return span
      }
    }
    return null
  }
}
