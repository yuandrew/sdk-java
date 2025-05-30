package io.temporal.internal.statemachines;

/**
 * Thrown when {@link io.temporal.workflow.Workflow#getVersion(String, int, int)} detects that the
 * workflow history was generated by a code that doesn't comply with specified min and max versions.
 *
 * <p>The reason this class extends Error is for application workflow code to not catch it by
 * mistake. The default behavior of the SDK is to block workflow execution while Error is thrown.
 */
public class UnsupportedVersion extends Error {
  public UnsupportedVersion(String message) {
    super(message);
  }

  public UnsupportedVersion(UnsupportedVersionException e) {
    super(e.getMessage(), e);
  }

  public static class UnsupportedVersionException extends RuntimeException {
    public UnsupportedVersionException(String message) {
      super(message);
    }
  }
}
