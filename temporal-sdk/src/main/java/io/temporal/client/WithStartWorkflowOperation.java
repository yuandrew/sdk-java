package io.temporal.client;

import io.temporal.workflow.Functions;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

/**
 * WithStartWorkflowOperation is a start workflow request that can be executed together with an
 * update workflow request. See {@link WorkflowClient#startUpdateWithStart} and {@link
 * WorkflowClient#executeUpdateWithStart}.
 *
 * @param <R> type of the workflow result
 */
public final class WithStartWorkflowOperation<R> {

  private final AtomicBoolean invoked = new AtomicBoolean(false);
  private WorkflowStub stub;
  private Object[] args;
  @Nullable private Functions.Proc startMethod;
  private Class<? extends R> resultClass;

  private WithStartWorkflowOperation() {}

  /**
   * Creates a new {@link WithStartWorkflowOperation} for a zero argument workflow method.
   *
   * @param startMethod method reference annotated with @WorkflowMethod of a proxy created through
   *     {@link WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   */
  public WithStartWorkflowOperation(Functions.Func<R> startMethod) {
    this.startMethod =
        () -> {
          startMethod.apply();
        };
    this.args = new Object[] {};
  }

  /**
   * Creates a new {@link WithStartWorkflowOperation} for a one argument workflow method.
   *
   * @param startMethod method reference annotated with @WorkflowMethod of a proxy created through
   *     {@link WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow method parameter
   */
  public <A1> WithStartWorkflowOperation(Functions.Func1<A1, R> startMethod, A1 arg1) {
    this.startMethod = () -> startMethod.apply(arg1);
    this.args = new Object[] {arg1};
  }

  /**
   * Creates a new {@link WithStartWorkflowOperation} for a two argument workflow method.
   *
   * @param startMethod method reference annotated with @WorkflowMethod of a proxy created through
   *     {@link WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow method parameter
   * @param arg2 second workflow method parameter
   */
  public <A1, A2> WithStartWorkflowOperation(
      Functions.Func2<A1, A2, R> startMethod, A1 arg1, A2 arg2) {
    this.startMethod = () -> startMethod.apply(arg1, arg2);
    this.args = new Object[] {arg1, arg2};
  }

  /**
   * Creates a new {@link WithStartWorkflowOperation} for a three argument workflow method.
   *
   * @param startMethod method reference annotated with @WorkflowMethod of a proxy created through
   *     {@link WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow method parameter
   * @param arg2 second workflow method parameter
   * @param arg3 third workflow method parameter
   */
  public <A1, A2, A3> WithStartWorkflowOperation(
      Functions.Func3<A1, A2, A3, R> startMethod, A1 arg1, A2 arg2, A3 arg3) {
    this.startMethod = () -> startMethod.apply(arg1, arg2, arg3);
    this.args = new Object[] {arg1, arg2, arg3};
  }

  /**
   * Creates a new {@link WithStartWorkflowOperation} for a four argument workflow method.
   *
   * @param startMethod method reference annotated with @WorkflowMethod of a proxy created through
   *     {@link WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow method parameter
   * @param arg2 second workflow method parameter
   * @param arg3 third workflow method parameter
   * @param arg4 fourth workflow method parameter
   */
  public <A1, A2, A3, A4> WithStartWorkflowOperation(
      Functions.Func4<A1, A2, A3, A4, R> startMethod, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
    this.startMethod = () -> startMethod.apply(arg1, arg2, arg3, arg4);
    this.args = new Object[] {arg1, arg2, arg3, arg4};
  }

  /**
   * Creates a new {@link WithStartWorkflowOperation} for a five argument workflow method.
   *
   * @param startMethod method reference annotated with @WorkflowMethod of a proxy created through
   *     {@link WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow method parameter
   * @param arg2 second workflow method parameter
   * @param arg3 third workflow method parameter
   * @param arg4 fourth workflow method parameter
   * @param arg5 fifth workflow method parameter
   */
  public <A1, A2, A3, A4, A5> WithStartWorkflowOperation(
      Functions.Func5<A1, A2, A3, A4, A5, R> startMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5) {
    this.startMethod = () -> startMethod.apply(arg1, arg2, arg3, arg4, arg5);
    this.args = new Object[] {arg1, arg2, arg3, arg4, arg5};
  }

  /**
   * Creates a new {@link WithStartWorkflowOperation} for a six argument workflow method.
   *
   * @param startMethod method reference annotated with @WorkflowMethod of a proxy created through
   *     {@link WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow method parameter
   * @param arg2 second workflow method parameter
   * @param arg3 third workflow method parameter
   * @param arg4 fourth workflow method parameter
   * @param arg5 fifth workflow method parameter
   * @param arg6 sixth workflow method parameter
   */
  public <A1, A2, A3, A4, A5, A6> WithStartWorkflowOperation(
      Functions.Func6<A1, A2, A3, A4, A5, A6, R> startMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    this.startMethod = () -> startMethod.apply(arg1, arg2, arg3, arg4, arg5, arg6);
    this.args = new Object[] {arg1, arg2, arg3, arg4, arg5, arg6};
  }

  /**
   * Creates a new {@link WithStartWorkflowOperation} for a zero argument workflow method.
   *
   * @param startMethod method reference annotated with @WorkflowMethod of a proxy created through
   *     {@link WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   */
  public WithStartWorkflowOperation(Functions.Proc startMethod) {
    this.startMethod =
        () -> {
          startMethod.apply();
        };
    this.args = new Object[] {};
  }

  /**
   * Creates a new {@link WithStartWorkflowOperation} for a one argument workflow method.
   *
   * @param startMethod method reference annotated with @WorkflowMethod of a proxy created through
   *     {@link WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow method parameter
   */
  public <A1> WithStartWorkflowOperation(Functions.Proc1<A1> startMethod, A1 arg1) {
    this.startMethod = () -> startMethod.apply(arg1);
    this.args = new Object[] {arg1};
  }

  /**
   * Creates a new {@link WithStartWorkflowOperation} for a two argument workflow method.
   *
   * @param startMethod method reference annotated with @WorkflowMethod of a proxy created through
   *     {@link WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow method parameter
   * @param arg2 second workflow method parameter
   */
  public <A1, A2> WithStartWorkflowOperation(
      Functions.Proc2<A1, A2> startMethod, A1 arg1, A2 arg2) {
    this.startMethod = () -> startMethod.apply(arg1, arg2);
    this.args = new Object[] {arg1, arg2};
  }

  /**
   * Creates a new {@link WithStartWorkflowOperation} for a three argument workflow method.
   *
   * @param startMethod method reference annotated with @WorkflowMethod of a proxy created through
   *     {@link WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow method parameter
   * @param arg2 second workflow method parameter
   * @param arg3 third workflow method parameter
   */
  public <A1, A2, A3> WithStartWorkflowOperation(
      Functions.Proc3<A1, A2, A3> startMethod, A1 arg1, A2 arg2, A3 arg3) {
    this.startMethod = () -> startMethod.apply(arg1, arg2, arg3);
    this.args = new Object[] {arg1, arg2, arg3};
  }

  /**
   * Creates a new {@link WithStartWorkflowOperation} for a four argument workflow method.
   *
   * @param startMethod method reference annotated with @WorkflowMethod of a proxy created through
   *     {@link WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow method parameter
   * @param arg2 second workflow method parameter
   * @param arg3 third workflow method parameter
   * @param arg4 fourth workflow method parameter
   */
  public <A1, A2, A3, A4> WithStartWorkflowOperation(
      Functions.Proc4<A1, A2, A3, A4> startMethod, A1 arg1, A2 arg2, A3 arg3, A4 arg4) {
    this.startMethod = () -> startMethod.apply(arg1, arg2, arg3, arg4);
    this.args = new Object[] {arg1, arg2, arg3, arg4};
  }

  /**
   * Creates a new {@link WithStartWorkflowOperation} for a five argument workflow method.
   *
   * @param startMethod method reference annotated with @WorkflowMethod of a proxy created through
   *     {@link WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow method parameter
   * @param arg2 second workflow method parameter
   * @param arg3 third workflow method parameter
   * @param arg4 fourth workflow method parameter
   * @param arg5 fifth workflow method parameter
   */
  public <A1, A2, A3, A4, A5> WithStartWorkflowOperation(
      Functions.Proc5<A1, A2, A3, A4, A5> startMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5) {
    this.startMethod = () -> startMethod.apply(arg1, arg2, arg3, arg4, arg5);
    this.args = new Object[] {arg1, arg2, arg3, arg4, arg5};
  }

  /**
   * Creates a new {@link WithStartWorkflowOperation} for a six argument workflow method.
   *
   * @param startMethod method reference annotated with @WorkflowMethod of a proxy created through
   *     {@link WorkflowClient#newWorkflowStub(Class, WorkflowOptions)}.
   * @param arg1 first workflow method parameter
   * @param arg2 second workflow method parameter
   * @param arg3 third workflow method parameter
   * @param arg4 fourth workflow method parameter
   * @param arg5 fifth workflow method parameter
   * @param arg6 sixth workflow method parameter
   */
  public <A1, A2, A3, A4, A5, A6> WithStartWorkflowOperation(
      Functions.Proc6<A1, A2, A3, A4, A5, A6> startMethod,
      A1 arg1,
      A2 arg2,
      A3 arg3,
      A4 arg4,
      A5 arg5,
      A6 arg6) {
    this.startMethod = () -> startMethod.apply(arg1, arg2, arg3, arg4, arg5, arg6);
    this.args = new Object[] {arg1, arg2, arg3, arg4, arg5, arg6};
  }

  /**
   * Obtains workflow result.
   *
   * @return the result of the workflow
   */
  public R getResult() {
    return this.stub.getResult(this.resultClass);
  }

  /**
   * Mark the operation as having been invoked.
   *
   * @return false if the operation was already invoked
   */
  boolean markInvoked() {
    return invoked.compareAndSet(false, true);
  }

  @Nullable
  Functions.Proc getStartMethod() {
    return startMethod;
  }

  void setResultClass(Class resultClass) {
    this.resultClass = resultClass;
  }

  // equals/hashCode intentionally left as default

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("WithStartWorkflowOperation{args=").append(Arrays.toString(args));
    if (stub != null) {
      sb.append(", stub=").append(stub);
    }
    if (startMethod != null) {
      sb.append(", startMethod=").append(startMethod);
    }
    if (resultClass != null) {
      sb.append(", resultClass=").append(resultClass);
    }
    sb.append("}");
    return sb.toString();
  }

  void setStub(WorkflowStub stub) {
    this.stub = stub;
  }
}
