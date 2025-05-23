package io.temporal.payload.codec;

import io.temporal.api.common.v1.Payload;
import io.temporal.payload.context.SerializationContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.ListIterator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Performs encoding/decoding on the payloads via the given codecs. When encoding, the codecs are
 * applied last to first meaning the earlier encoders wrap the later ones. When decoding, the codecs
 * are applied first to last to reverse the effect.
 */
public class ChainCodec implements PayloadCodec {
  private final List<PayloadCodec> codecs;
  private final @Nullable SerializationContext context;

  /**
   * @param codecs to apply. When encoding, the {@code codecs} are applied last to first meaning the
   *     earlier encoders wrap the later ones. When decoding, the {@code codecs} are applied first
   *     to last to reverse the effect
   */
  public ChainCodec(Collection<PayloadCodec> codecs) {
    this(codecs, null);
  }

  ChainCodec(Collection<PayloadCodec> codecs, @Nullable SerializationContext context) {
    this.codecs = new ArrayList<>(codecs);
    this.context = context;
  }

  @Nonnull
  @Override
  public List<Payload> encode(@Nonnull List<Payload> payloads) {
    ListIterator<PayloadCodec> iterator = codecs.listIterator(codecs.size());
    while (iterator.hasPrevious()) {
      PayloadCodec codec = iterator.previous();
      payloads = (context != null ? codec.withContext(context) : codec).encode(payloads);
    }
    return payloads;
  }

  @Nonnull
  @Override
  public List<Payload> decode(@Nonnull List<Payload> payloads) {
    for (PayloadCodec codec : codecs) {
      payloads = (context != null ? codec.withContext(context) : codec).decode(payloads);
    }
    return payloads;
  }

  @Override
  @Nonnull
  public PayloadCodec withContext(@Nonnull SerializationContext context) {
    return new ChainCodec(codecs, context);
  }
}
