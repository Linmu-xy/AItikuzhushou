package com.tikuzhushou.question;

import java.math.BigDecimal;
import java.math.MathContext;

/** Numeric-only arithmetic; never executes code, resolves names or performs I/O. */
final class AssessmentCalculator {
  private static final MathContext PRECISION = new MathContext(16);
  private final String expression;
  private int offset;
  private AssessmentCalculator(String expression) { this.expression = expression; }

  static String calculate(String expression) {
    if (expression == null || expression.length() > 240 || !expression.matches("[0-9.()+*/\\s-]+"))
      throw new IllegalArgumentException("只允许数字、小数点、括号及 + - * /，长度不超过240字符");
    if (java.util.regex.Pattern.compile("[0-9.]\\s+[0-9.]").matcher(expression).find())
      throw new IllegalArgumentException("数值之间缺少运算符");
    var parser = new AssessmentCalculator(expression.replaceAll("\\s+", ""));
    BigDecimal value = parser.sum(0);
    if (parser.offset != parser.expression.length()) throw new IllegalArgumentException("算式未完整解析");
    return value.stripTrailingZeros().toPlainString();
  }
  private BigDecimal sum(int depth) {
    BigDecimal value = product(depth);
    while (offset < expression.length()) {
      if (take('+')) value = value.add(product(depth), PRECISION);
      else if (take('-')) value = value.subtract(product(depth), PRECISION);
      else break;
    }
    return value;
  }
  private BigDecimal product(int depth) {
    BigDecimal value = atom(depth);
    while (offset < expression.length()) {
      if (take('*')) value = value.multiply(atom(depth), PRECISION);
      else if (take('/')) value = value.divide(atom(depth), PRECISION);
      else break;
    }
    return value;
  }
  private BigDecimal atom(int depth) {
    if (depth > 24) throw new IllegalArgumentException("算式嵌套过深");
    if (take('+')) return atom(depth + 1);
    if (take('-')) return atom(depth + 1).negate();
    if (take('(')) {
      BigDecimal value = sum(depth + 1);
      if (!take(')')) throw new IllegalArgumentException("括号不匹配");
      return value;
    }
    int start = offset;
    while (offset < expression.length() && (Character.isDigit(expression.charAt(offset)) || expression.charAt(offset) == '.')) offset++;
    if (offset == start) throw new IllegalArgumentException("缺少数值");
    return new BigDecimal(expression.substring(start, offset), PRECISION);
  }
  private boolean take(char value) {
    if (offset < expression.length() && expression.charAt(offset) == value) { offset++; return true; }
    return false;
  }
}
