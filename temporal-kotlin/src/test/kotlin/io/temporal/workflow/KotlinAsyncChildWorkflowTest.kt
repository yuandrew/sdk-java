
package io.temporal.workflow

import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.common.converter.KotlinObjectMapperFactory
import io.temporal.internal.async.FunctionWrappingUtil
import io.temporal.internal.sync.AsyncInternal
import io.temporal.testing.internal.SDKTestWorkflowRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class KotlinAsyncChildWorkflowTest {

  @Rule
  @JvmField
  var testWorkflowRule: SDKTestWorkflowRule = SDKTestWorkflowRule.newBuilder()
    .setWorkflowTypes(ParentWorkflowImpl::class.java, ChildWorkflowImpl::class.java)
    .setWorkflowClientOptions(
      WorkflowClientOptions.newBuilder()
        .setDataConverter(DefaultDataConverter(JacksonJsonPayloadConverter(KotlinObjectMapperFactory.new())))
        .build()
    )
    .build()

  @WorkflowInterface
  interface ChildWorkflow {
    @WorkflowMethod
    fun execute(): Int
  }

  class ChildWorkflowImpl : ChildWorkflow {
    override fun execute(): Int {
      return 0
    }
  }

  @WorkflowInterface
  interface ParentWorkflow {
    @WorkflowMethod
    fun execute()
  }

  class ParentWorkflowImpl : ParentWorkflow {
    override fun execute() {
      val childWorkflow = Workflow.newChildWorkflowStub(ChildWorkflow::class.java)
      assertTrue(
        "This has to be true to make Async.function(childWorkflow::execute) work correctly as expected",
        AsyncInternal.isAsync(childWorkflow::execute)
      )
      assertTrue(
        "This has to be true to make Async.function(childWorkflow::execute) work correctly as expected",
        AsyncInternal.isAsync(FunctionWrappingUtil.temporalJavaFunctionalWrapper(childWorkflow::execute))
      )
      Async.function(childWorkflow::execute).get()
    }
  }

  @Test
  fun asyncChildWorkflowTest() {
    val client = testWorkflowRule.workflowClient
    val options = WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.taskQueue).build()
    val workflowStub = client.newWorkflowStub(ParentWorkflow::class.java, options)
    workflowStub.execute()
  }
}
