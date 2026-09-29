package com.tikuzhushou.project;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AssessmentMaterialCacheTests {
  @Test void scopesByOwnerModelPurposeAndExactMaterialBytes() {
    var cache = new AssessmentMaterialCache(); var owner = UUID.randomUUID();
    @SuppressWarnings("unchecked") Supplier<String> load = mock(Supplier.class);
    when(load.get()).thenReturn("摘要");
    cache.get(owner, "model", "V1:doc:page", new byte[]{1}, load);
    cache.get(owner, "model", "V1:doc:page", new byte[]{1}, load);
    verify(load, times(1)).get();
    cache.get(UUID.randomUUID(), "model", "V1:doc:page", new byte[]{1}, load);
    cache.get(owner, "other", "V1:doc:page", new byte[]{1}, load);
    cache.get(owner, "model", "V2:doc:page", new byte[]{1}, load);
    cache.get(owner, "model", "V1:doc:page", new byte[]{2}, load);
    verify(load, times(5)).get();
  }

  @Test void failuresAndBlankResultsAreNotCached() {
    var cache = new AssessmentMaterialCache(); var owner = UUID.randomUUID();
    @SuppressWarnings("unchecked") Supplier<String> load = mock(Supplier.class);
    when(load.get()).thenThrow(new IllegalStateException("unavailable")).thenReturn("", "完整摘要");
    assertThrows(IllegalStateException.class, () -> cache.get(owner, "model", "purpose", new byte[]{1}, load));
    assertThat(cache.get(owner, "model", "purpose", new byte[]{1}, load)).isEmpty();
    assertThat(cache.get(owner, "model", "purpose", new byte[]{1}, load)).isEqualTo("完整摘要");
    assertThat(cache.get(owner, "model", "purpose", new byte[]{1}, load)).isEqualTo("完整摘要");
    verify(load, times(3)).get();
  }

  @Test void expiresAndBoundsMemory() {
    var now = new AtomicLong(0); Clock clock = mock(Clock.class);
    when(clock.millis()).thenAnswer(ignored -> now.get());
    var cache = new AssessmentMaterialCache(clock, 2); var owner = UUID.randomUUID();
    @SuppressWarnings("unchecked") Supplier<String> load = mock(Supplier.class); when(load.get()).thenReturn("摘要");
    cache.get(owner, "model", "1", new byte[]{1}, load);
    now.set(31 * 60_000L);
    cache.get(owner, "model", "1", new byte[]{1}, load);
    verify(load, times(2)).get();
    cache.get(owner, "model", "2", new byte[]{1}, load);
    cache.get(owner, "model", "3", new byte[]{1}, load);
    cache.get(owner, "model", "1", new byte[]{1}, load);
    verify(load, times(5)).get();
  }
}
