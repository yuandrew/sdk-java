package io.temporal.workflow;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that the method is a signal handler method. A signal method gets executed when a
 * workflow receives a signal.
 *
 * <p>A Signal can be received and the corresponding signal method can be triggered before execution
 * of the first workflow task. Workflow and Signal method implementations should be compliant with
 * this possibility.<br>
 * Importantly, this happens when a reset of history to the first workflow task (with signal
 * preservation) is performed. This results in signals being appended to the start of the new
 * history.<br>
 * Techniques to consider:
 *
 * <ul>
 *   <li>Workflow object constructors and initialization blocks should be used to initialize the
 *       internal data structures if possible.
 *   <li>In rare cases signal processing may require initialization to be performed by the workflow
 *       code first. An example is initialization that depends on the workflow input parameters. You
 *       may persist data from the signals received when initialization is incomplete into a
 *       workflow field. This data can be processed in the workflow method itself after the required
 *       initialization is performed.
 * </ul>
 *
 * <p>This annotation applies only to workflow interface methods.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SignalMethod {
  /**
   * Name of the signal type. Default is method name.
   *
   * <p>Be careful about names that contain special characters. These names can be used as metric
   * tags. And systems like prometheus ignore metrics which have tags with unsupported characters.
   *
   * <p>Name cannot start with __temporal_ as it is reserved for internal use.
   */
  String name() default "";

  /** Short description of the signal type. Default is to an empty string. */
  String description() default "";

  /** Sets the actions taken if a workflow exits with a running instance of this handler. */
  HandlerUnfinishedPolicy unfinishedPolicy() default HandlerUnfinishedPolicy.WARN_AND_ABANDON;
}
