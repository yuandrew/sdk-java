package io.temporal.internal.statemachines;

import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.workflow.Functions;

final class ContinueAsNewWorkflowStateMachine
    extends EntityStateMachineInitialCommand<
        ContinueAsNewWorkflowStateMachine.State,
        ContinueAsNewWorkflowStateMachine.ExplicitEvent,
        ContinueAsNewWorkflowStateMachine> {

  private final ContinueAsNewWorkflowExecutionCommandAttributes continueAsNewWorkflowAttributes;

  public static void newInstance(
      ContinueAsNewWorkflowExecutionCommandAttributes continueAsNewWorkflowAttributes,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    new ContinueAsNewWorkflowStateMachine(
        continueAsNewWorkflowAttributes, commandSink, stateMachineSink);
  }

  private ContinueAsNewWorkflowStateMachine(
      ContinueAsNewWorkflowExecutionCommandAttributes continueAsNewWorkflowAttributes,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    super(STATE_MACHINE_DEFINITION, commandSink, stateMachineSink);
    this.continueAsNewWorkflowAttributes = continueAsNewWorkflowAttributes;
    explicitEvent(ExplicitEvent.SCHEDULE);
  }

  enum ExplicitEvent {
    SCHEDULE
  }

  enum State {
    CREATED,
    CONTINUE_AS_NEW_WORKFLOW_COMMAND_CREATED,
    CONTINUE_AS_NEW_WORKFLOW_COMMAND_RECORDED,
  }

  public static final StateMachineDefinition<
          State, ExplicitEvent, ContinueAsNewWorkflowStateMachine>
      STATE_MACHINE_DEFINITION =
          StateMachineDefinition
              .<State, ExplicitEvent, ContinueAsNewWorkflowStateMachine>newInstance(
                  "ContinueAsNewWorkflow",
                  State.CREATED,
                  State.CONTINUE_AS_NEW_WORKFLOW_COMMAND_RECORDED)
              .add(
                  State.CREATED,
                  ExplicitEvent.SCHEDULE,
                  State.CONTINUE_AS_NEW_WORKFLOW_COMMAND_CREATED,
                  ContinueAsNewWorkflowStateMachine::createContinueAsNewWorkflowCommand)
              .add(
                  State.CONTINUE_AS_NEW_WORKFLOW_COMMAND_CREATED,
                  CommandType.COMMAND_TYPE_CONTINUE_AS_NEW_WORKFLOW_EXECUTION,
                  State.CONTINUE_AS_NEW_WORKFLOW_COMMAND_CREATED)
              .add(
                  State.CONTINUE_AS_NEW_WORKFLOW_COMMAND_CREATED,
                  EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW,
                  State.CONTINUE_AS_NEW_WORKFLOW_COMMAND_RECORDED);

  private void createContinueAsNewWorkflowCommand() {
    addCommand(
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_CONTINUE_AS_NEW_WORKFLOW_EXECUTION)
            .setContinueAsNewWorkflowExecutionCommandAttributes(continueAsNewWorkflowAttributes)
            .build());
  }
}
