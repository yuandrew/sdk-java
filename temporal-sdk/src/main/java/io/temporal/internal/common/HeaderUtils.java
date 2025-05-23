package io.temporal.internal.common;

import io.temporal.api.common.v1.Header;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DataConverterException;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

public class HeaderUtils {

  public static Header toHeaderGrpc(
      io.temporal.common.interceptors.Header header,
      @Nullable io.temporal.common.interceptors.Header overrides) {
    Header.Builder builder = Header.newBuilder().putAllFields(header.getValues());
    if (overrides != null) {
      for (Map.Entry<String, Payload> item : overrides.getValues().entrySet()) {
        builder.putFields(item.getKey(), item.getValue());
      }
    }
    return builder.build();
  }

  /**
   * Converts a {@code Map<String, Object>} into a {@code Map<String, Payload>} by applying
   * specified converter on each value. This util should be used for things like search attributes
   * and memo that need to be converted back from bytes on the server.
   */
  public static Map<String, Payload> intoPayloadMap(
      DataConverter converter, Map<String, Object> map) {
    if (map == null) return null;
    Map<String, Payload> result = new HashMap<>();
    for (Map.Entry<String, Object> item : map.entrySet()) {
      try {
        result.put(item.getKey(), converter.toPayload(item.getValue()).get());
      } catch (DataConverterException e) {
        throw new DataConverterException("Cannot serialize key " + item.getKey(), e.getCause());
      }
    }
    return result;
  }

  private HeaderUtils() {}
}
