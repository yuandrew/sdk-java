package io.temporal.workflow;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.*;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class MutableSideEffectTest {

  private static final Map<String, Queue<Long>> mutableSideEffectValue =
      Collections.synchronizedMap(new HashMap<>());

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestMutableSideEffectWorkflowImpl.class)
          .build();

  @Test
  public void testMutableSideEffect() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    ArrayDeque<Long> values = new ArrayDeque<Long>();
    values.add(1234L);
    values.add(1234L);
    values.add(123L); // expected to be ignored as it is smaller than 1234.
    values.add(3456L);
    values.add(1234L); // expected to be ignored as it is smaller than 3456L.
    values.add(4234L);
    values.add(4234L);
    values.add(3456L); // expected to be ignored as it is smaller than 4234L.
    mutableSideEffectValue.put(testWorkflowRule.getTaskQueue(), values);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("1234, 1234, 1234, 3456, 3456, 4234, 4234, 4234", result);
  }

  public static class TestMutableSideEffectWorkflowImpl implements TestWorkflow1 {

    @Override
    public String execute(String taskQueue) {
      StringBuilder result = new StringBuilder();
      for (int j = 0; j < 1; j++) {
        for (int i = 0; i < 8; i++) {
          long value =
              Workflow.mutableSideEffect(
                  "id1",
                  Long.class,
                  (o, n) -> n > o,
                  () -> mutableSideEffectValue.get(taskQueue).poll());
          if (result.length() > 0) {
            result.append(", ");
          }
          result.append(value);
          // Sleep is here to ensure that mutableSideEffect works when replaying a history.
          if (i >= 8) {
            Workflow.sleep(Duration.ofSeconds(1));
          }
        }
      }
      return result.toString();
    }
  }
}
