package com.tikuzhushou.ai;

/** A provider response was received and metered, but is not safe to consume. No prompt/content is retained. */
public final class ModelResponseException extends IllegalStateException {
  public enum Reason { EMPTY, TRUNCATED }
  private final Reason reason;

  public ModelResponseException(Reason reason) {
    super(reason == Reason.TRUNCATED ? "模型输出达到长度上限，未接受不完整响应" : "模型响应为空或格式异常");
    this.reason = reason;
  }

  public Reason reason() { return reason; }
}
