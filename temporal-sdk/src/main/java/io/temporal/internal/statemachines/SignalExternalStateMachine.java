package io.temporal.internal.statemachines;

import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.SignalExternalWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.failure.v1.ApplicationFailureInfo;
import io.temporal.api.failure.v1.CanceledFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.SignalExternalWorkflowExecutionFailedEventAttributes;
import io.temporal.workflow.Functions;

final class SignalExternalStateMachine
    extends EntityStateMachineInitialCommand<
        SignalExternalStateMachine.State,
        SignalExternalStateMachine.ExplicitEvent,
        SignalExternalStateMachine> {

  private SignalExternalWorkflowExecutionCommandAttributes signalAttributes;

  private final Functions.Proc2<Void, Failure> completionCallback;
  private WorkflowExecution execution;

  /**
   * Register new instance of the signal commands
   *
   * @param signalAttributes attributes used to signal an external workflow
   * @param completionCallback either SignalExternalWorkflowExecutionFailed,
   *     ExternalWorkflowExecutionSignaled or true value of the second parameter to indicate
   *     immediate cancellation.
   * @param commandSink sink to send commands
   * @return cancellation handler
   */
  public static Functions.Proc newInstance(
      SignalExternalWorkflowExecutionCommandAttributes signalAttributes,
      Functions.Proc2<Void, Failure> completionCallback,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    SignalExternalStateMachine commands =
        new SignalExternalStateMachine(
            signalAttributes, completionCallback, commandSink, stateMachineSink);
    return commands::cancel;
  }

  private SignalExternalStateMachine(
      SignalExternalWorkflowExecutionCommandAttributes signalAttributes,
      Functions.Proc2<Void, Failure> completionCallback,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    super(STATE_MACHINE_DEFINITION, commandSink, stateMachineSink);
    this.signalAttributes = signalAttributes;
    this.execution = signalAttributes.getExecution();
    this.completionCallback = completionCallback;
    explicitEvent(ExplicitEvent.SCHEDULE);
  }

  enum ExplicitEvent {
    SCHEDULE,
    CANCEL
  }

  enum State {
    CREATED,
    SIGNAL_EXTERNAL_COMMAND_CREATED,
    SIGNAL_EXTERNAL_COMMAND_RECORDED,
    SIGNALED,
    FAILED,
    CANCELED,
  }

  public static final StateMachineDefinition<State, ExplicitEvent, SignalExternalStateMachine>
      STATE_MACHINE_DEFINITION =
          StateMachineDefinition.<State, ExplicitEvent, SignalExternalStateMachine>newInstance(
                  "SignalExternal", State.CREATED, State.SIGNALED, State.FAILED, State.CANCELED)
              .add(
                  State.CREATED,
                  ExplicitEvent.SCHEDULE,
                  State.SIGNAL_EXTERNAL_COMMAND_CREATED,
                  SignalExternalStateMachine::createSignalExternalCommand)
              .add(
                  State.SIGNAL_EXTERNAL_COMMAND_CREATED,
                  CommandType.COMMAND_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION,
                  State.SIGNAL_EXTERNAL_COMMAND_CREATED)
              .add(
                  State.SIGNAL_EXTERNAL_COMMAND_CREATED,
                  ExplicitEvent.CANCEL,
                  State.CANCELED,
                  SignalExternalStateMachine::cancelSignalExternalCommand)
              .add(
                  State.SIGNAL_EXTERNAL_COMMAND_CREATED,
                  EventType.EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED,
                  State.SIGNAL_EXTERNAL_COMMAND_RECORDED,
                  EntityStateMachineInitialCommand::setInitialCommandEventId)
              .add(
                  State.SIGNAL_EXTERNAL_COMMAND_RECORDED,
                  ExplicitEvent.CANCEL,
                  State.SIGNAL_EXTERNAL_COMMAND_RECORDED)
              .add(
                  State.SIGNAL_EXTERNAL_COMMAND_RECORDED,
                  EventType.EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_SIGNALED,
                  State.SIGNALED,
                  SignalExternalStateMachine::notifyCompleted)
              .add(
                  State.SIGNAL_EXTERNAL_COMMAND_RECORDED,
                  EventType.EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_FAILED,
                  State.FAILED,
                  SignalExternalStateMachine::notifyFailed);

  private void createSignalExternalCommand() {
    addCommand(
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION)
            .setSignalExternalWorkflowExecutionCommandAttributes(signalAttributes)
            .build());
    signalAttributes = null; // do not retain
  }

  public void cancel() {
    if (!isFinalState()) {
      explicitEvent(ExplicitEvent.CANCEL);
    }
  }

  private void notifyCompleted() {
    completionCallback.apply(null, null);
  }

  private void notifyFailed() {
    SignalExternalWorkflowExecutionFailedEventAttributes attributes =
        currentEvent.getSignalExternalWorkflowExecutionFailedEventAttributes();
    // TODO(maxim): Special failure type
    Failure failure =
        Failure.newBuilder()
            .setApplicationFailureInfo(
                ApplicationFailureInfo.newBuilder().setType(attributes.getCause().name()).build())
            .setMessage(
                "SignalExternalWorkflowExecution failed with NOT_FOUND. WorkflowId="
                    + execution.getWorkflowId()
                    + ", runId="
                    + execution.getRunId())
            .build();
    completionCallback.apply(null, failure);
  }

  private void cancelSignalExternalCommand() {
    cancelCommand();
    Failure failure =
        Failure.newBuilder()
            .setMessage("Signal external workflow execution canceled")
            .setCanceledFailureInfo(CanceledFailureInfo.newBuilder().build())
            .build();
    completionCallback.apply(null, failure);
  }
}
