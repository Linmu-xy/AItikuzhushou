package com.tikuzhushou.project;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Small process-local cache of successful material summaries, never questions or answer keys. */
@Component
public class AssessmentMaterialCache {
  private static final long TTL_MS = Duration.ofMinutes(30).toMillis();
  private final Clock clock;
  private final int maximum;
  private final Map<Key, Entry> entries = new LinkedHashMap<>(32, .75f, true);

  public AssessmentMaterialCache() { this(Clock.systemUTC(), 128); }
  AssessmentMaterialCache(Clock clock, int maximum) { this.clock = clock; this.maximum = maximum; }

  public String get(UUID owner, String model, String purpose, byte[] input, Supplier<String> loader) {
    if (owner == null || model == null || model.isBlank()) return loader.get();
    Key key = new Key(owner, model, purpose, digest(input));
    synchronized (entries) {
      Entry existing = entries.get(key);
      if (existing != null && clock.millis() - existing.createdAt() < TTL_MS) return existing.value();
      entries.remove(key);
    }
    // Never hold a global monitor during a provider request. A concurrent cold miss may compute
    // twice; prefer that bounded duplication to serializing unrelated users behind a slow call.
    String result = loader.get();
    if (result != null && !result.isBlank() && result.length() <= 24_000) synchronized (entries) {
      entries.put(key, new Entry(result, clock.millis()));
      while (entries.size() > maximum) entries.remove(entries.keySet().iterator().next());
    }
    return result;
  }

  private static String digest(byte[] input) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input)); }
    catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }
  private record Key(UUID owner, String model, String purpose, String digest) { }
  private record Entry(String value, long createdAt) { }
}
