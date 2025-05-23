package io.temporal.internal.common;

import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.TextFormat;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;

/** Converts history protos into human readable format */
public final class HistoryProtoTextUtils {

  private HistoryProtoTextUtils() {}

  public static String toProtoText(History history, boolean showWorkflowTasks) {
    TextFormat.Printer printer = TextFormat.printer();
    StringBuilder result = new StringBuilder();
    for (HistoryEvent event : history.getEventsList()) {
      if (!showWorkflowTasks
          && event.getEventType().name().startsWith("EVENT_TYPE_WORKFLOW_TASK")) {
        continue;
      }
      printEvent(printer, result, event);
      result.append("\n");
    }

    return result.toString();
  }

  private static void printEvent(
      TextFormat.Printer printer, StringBuilder result, HistoryEvent event) {
    event
        .getAllFields()
        .forEach(
            (d, v) -> {
              if (d.getName().endsWith("_attributes")) {
                result.append(d.getName()).append(" { \n");
                String printedAttributes = printEventAttributes(printer, (MessageOrBuilder) v);
                for (String attributeField : printedAttributes.split("\\n")) {
                  result.append("  ").append(attributeField).append('\n');
                }
                result.append("}");
              } else {
                result.append(printer.shortDebugString(d, v));
              }
              result.append("\n");
            });
  }

  private static String printEventAttributes(
      TextFormat.Printer printer, MessageOrBuilder attributesValue) {
    StringBuilder result = new StringBuilder();
    attributesValue
        .getAllFields()
        .forEach(
            (d, v) -> {
              String fieldName = d.getName();
              if (fieldName.equals("input")
                  || fieldName.equals("result")
                  || fieldName.equals("details")) {
                result.append(printer.printFieldToString(d, v));
              } else {
                result.append(printer.shortDebugString(d, v));
                result.append("\n");
              }
            });
    if (result.length() > 0 && result.charAt(result.length() - 1) == '\n') {
      // delete trailing \n
      result.deleteCharAt(result.length() - 1);
    }

    return result.toString();
  }
}
