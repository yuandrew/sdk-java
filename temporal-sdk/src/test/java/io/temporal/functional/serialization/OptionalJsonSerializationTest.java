package io.temporal.functional.serialization;

import static org.junit.Assert.*;

import com.google.common.reflect.TypeToken;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import java.lang.reflect.Type;
import java.util.Optional;
import javax.annotation.Nullable;
import org.junit.Rule;
import org.junit.Test;

public class OptionalJsonSerializationTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(CustomerWorkflowWaitForSignalImpl.class, CustomerWorkflowImpl.class)
          .build();

  @Test
  public void testOptionalArgumentsWorkflowTypedStub() {
    Optional<Customer> customer = Optional.of(new Customer("john", Optional.of("555-55-5555")));
    WorkflowClient client = testWorkflowRule.getWorkflowClient();

    CustomerWorkflow workflow =
        client.newWorkflowStub(
            CustomerWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .validateBuildWithDefaults());

    Optional<Customer> result = workflow.execute(customer);
    assertEquals(customer.get().getFirstName(), result.get().getFirstName());

    // query after completion
    Optional<Customer> queryResult = workflow.getCustomer();
    assertTrue(queryResult.isPresent());
    assertEquals(customer.get().getFirstName(), queryResult.get().getFirstName());
  }

  @Test
  public void testOptionalArgumentsWorkflowUntypedStub() {
    CustomerWorkflowWaitForSignal workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(CustomerWorkflowWaitForSignal.class);
    Optional<Customer> customer1 = Optional.of(new Customer("john", Optional.of("555-55-5555")));
    Optional<Customer> customer2 = Optional.of(new Customer("merry", Optional.of("111-11-1111")));
    WorkflowClient.start(workflow::execute, customer1);

    WorkflowStub workflowStub = WorkflowStub.fromTyped(workflow);
    SDKTestWorkflowRule.waitForOKQuery(workflowStub);

    // send a signal to update customer
    workflow.setCustomer(customer2);

    // checking that untyped stub can unbox Optional into the class directly
    Customer result = workflowStub.getResult(Customer.class);
    assertEquals(customer2.get().getFirstName(), result.getFirstName());

    // checking that untyped stub can return the Original optional too
    Type generifiedType = new TypeToken<Optional<Customer>>() {}.getType();
    Optional<Customer> optionalResult =
        WorkflowStub.fromTyped(workflow).getResult(Optional.class, generifiedType);
    assertTrue(optionalResult.isPresent());
    assertEquals(customer2.get().getFirstName(), optionalResult.get().getFirstName());
  }

  public static class CustomerWorkflowImpl implements CustomerWorkflow {
    private @Nullable Customer customer;

    @Override
    public Optional<Customer> execute(Optional<Customer> customer) {
      this.customer = customer.orElse(null);
      return getCustomer();
    }

    @Override
    public Optional<Customer> getCustomer() {
      return Optional.ofNullable(this.customer);
    }
  }

  public static class CustomerWorkflowWaitForSignalImpl implements CustomerWorkflowWaitForSignal {
    private @Nullable Customer customer;
    private final CompletablePromise<Optional<Customer>> promise = Workflow.newPromise();

    @Override
    public Optional<Customer> execute(Optional<Customer> customer) {
      this.customer = customer.orElse(null);

      promise.get();

      return getCustomer();
    }

    public Optional<Customer> getCustomer() {
      return Optional.ofNullable(this.customer);
    }

    @Override
    public void setCustomer(Optional<Customer> customer) {
      this.customer = customer.orElse(null);
      promise.complete(null);
    }
  }

  @WorkflowInterface
  public interface CustomerWorkflow {

    @WorkflowMethod
    Optional<Customer> execute(Optional<Customer> customer);

    @QueryMethod
    Optional<Customer> getCustomer();
  }

  @WorkflowInterface
  public interface CustomerWorkflowWaitForSignal {

    @WorkflowMethod
    Optional<Customer> execute(Optional<Customer> customer);

    @SignalMethod(name = "setCustomer")
    void setCustomer(Optional<Customer> customer);
  }

  public static class Customer {
    private String firstName;
    // we want ssn in a field here to test serialization of Optional<?> fields into json
    private Optional<String> ssn;

    public Customer() {}

    public Customer(String firstName, Optional<String> ssn) {
      this.firstName = firstName;
      this.ssn = ssn;
    }

    public String getFirstName() {
      return firstName;
    }

    public void setFirstName(String firstName) {
      this.firstName = firstName;
    }

    public Optional<String> getSsn() {
      return ssn;
    }

    public void setSsn(Optional<String> ssn) {
      this.ssn = ssn;
    }
  }
}
